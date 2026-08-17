package com.contentops.orchestrator.service;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.collection.WorkCollectionService;
import com.contentops.common.render.PngRenderClient;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.PublishMode;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.cost.CostGuardBlockedException;
import com.contentops.common.platform.ContentPlatform;
import com.contentops.common.platform.MarkdownConverter;
import com.contentops.common.platform.PlatformSpecRegistry;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.gateway.AgentGateway;
import com.contentops.orchestrator.graph.LangGraphWorkflowEngine;
import com.contentops.orchestrator.workflow.PipelineOrchestrator;
import com.contentops.publish.render.SocialCardRenderer;
import com.contentops.topic.service.DiscussionSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Collectors;

/**
 * 工作流服务 — 双引擎切换入口。
 *
 * <p><b>B计划：</b>通过配置 {@code contentops.orchestrator.engine} 控制引擎选择：
 * <ul>
 *   <li>{@code legacy}（默认）：使用原 {@link PipelineOrchestrator}，A计划修复后的循环控制</li>
 *   <li>{@code langgraph}：使用 {@link LangGraphWorkflowEngine}，LangGraph4j 原生图编排</li>
 * </ul>
 *
 * <p>灰度切换：通过 Nacos 配置中心动态修改 engine 值即可切换，无需重启（配合 @RefreshScope）。
 * 回滚：改回 legacy 即可，原引擎代码完全保留。
 *
 * <p><b>P0 修复：</b>
 * <ul>
 *   <li>使用自定义有界线程池替代 ForkJoinPool.commonPool()，防止 I/O 任务耗尽默认线程池</li>
 *   <li>approveAndProceed / confirmSubStage 使用分布式锁保护，防止并发状态修改</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowStateManager stateManager;
    private final PipelineOrchestrator orchestrator;       // 原引擎（A计划）
    private final LangGraphWorkflowEngine langGraphEngine;  // 新引擎（B计划）

    @Value("${contentops.orchestrator.engine:legacy}")
    private String engineType;
    private final PlatformWorkflowOrchestrator platformOrchestrator;
    private final PlatformSpecRegistry platformSpecRegistry;
    private final ObjectMapper objectMapper;
    private final MarkdownConverter markdownConverter;
    private final SocialCardRenderer socialCardRenderer;
    private final AgentGateway agentGateway;
    private final DiscussionSessionService discussionSessionService;
    private final WorkCollectionService workCollectionService;
    private final PngRenderClient pngRenderClient;

    /** 自定义线程池：用于异步执行工作流管线 */
    private ExecutorService workflowExecutor;

    @PostConstruct
    void initExecutor() {
        int corePoolSize = 4;
        int maxPoolSize = 16;
        int queueCapacity = 50;
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "workflow-exec-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        this.workflowExecutor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时由调用线程执行，实现背压
        );
        log.info("Workflow executor initialized: core={}, max={}, queue={}",
                corePoolSize, maxPoolSize, queueCapacity);
    }

    @PreDestroy
    void shutdownExecutor() {
        if (workflowExecutor != null) {
            workflowExecutor.shutdown();
            try {
                if (!workflowExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    workflowExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                workflowExecutor.shutdownNow();
            }
            log.info("Workflow executor shutdown complete");
        }
    }

    /**
     * 判断是否使用 LangGraph4j 引擎。
     */
    private boolean useLangGraph() {
        return "langgraph".equalsIgnoreCase(engineType);
    }

    /**
     * Start a new workflow by executing the first stage.
     *
     * <p>根据配置选择执行引擎。工作流执行为异步——先保存状态并立即返回，
     * 管线在后台线程中执行。若 Agent 服务不可用，工作流状态会被标记为 FAILED。
     *
     * <p><b>P0 修复：</b>使用自定义有界线程池替代 ForkJoinPool.commonPool()，
     * 避免 I/O 密集型任务耗尽 JVM 默认线程池。
     */
    public void startWorkflow(TaskContext context) {
        // 平台为“预选”：选题规划只跑一次，完成后暂停等待用户确认平台
        java.util.List<ContentPlatform> preSelected = resolvePlatforms(context);
        if (context.getInputs() == null) {
            context.setInputs(new java.util.HashMap<>());
        }
        context.getInputs().put(PlatformWorkflowOrchestrator.INPUT_PLATFORMS,
                preSelected.stream().map(ContentPlatform::getCode).toList());
        context.getInputs().put("platformNames",
                preSelected.stream().map(ContentPlatform::getDisplayName).toList());
        boolean pauseForSelection =
                com.contentops.common.enums.AgentStage.TOPIC_PLANNING.getCode().equals(context.getCurrentStage())
                        && !useLangGraph();
        if (pauseForSelection) {
            context.getInputs().put(PlatformWorkflowOrchestrator.INPUT_PAUSE_FOR_PLATFORM_SELECTION, true);
            context.getInputs().put(PlatformWorkflowOrchestrator.INPUT_PLATFORM_SELECTION_DONE, false);
        }
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        // 创建时指定作品合集：把新作品归入用户选择的合集（按类型区分存放）
        if (context.getInputs() != null) {
            Object rawIds = context.getInputs().get("collectionIds");
            if (rawIds instanceof List<?> list && !list.isEmpty()) {
                workCollectionService.addWorkToCollections(context.getWorkflowId(),
                        list.stream().map(String::valueOf).toList());
            }
        }

        // 使用自定义线程池异步执行管线，避免阻塞 HTTP 请求
        workflowExecutor.submit(() -> runPipeline(context));
    }

    /**
     * 崩溃恢复：把停留在 IN_PROGRESS/RUNNING 的工作流重新提交执行（幂等：状态校验 + 锁）。
     * 由 {@link com.contentops.common.agent.WorkflowRecoveryService} 定时扫描触发。
     */
    public void resumeWorkflow(String workflowId) {
        java.util.Optional<TaskContext> stateOpt = stateManager.loadWorkflowState(workflowId);
        if (stateOpt.isEmpty()) {
            log.info("[Workflow:{}] 恢复跳过：状态不存在", workflowId);
            return;
        }
        TaskContext context = stateOpt.get();
        String status = context.getStatus() == null ? "" : context.getStatus();
        if (!TaskStatus.IN_PROGRESS.name().equals(status) && !"RUNNING".equals(status)) {
            log.info("[Workflow:{}] 恢复跳过：状态={}", workflowId, status);
            return;
        }
        log.warn("[Workflow:{}] 检测到中断，恢复执行（当前阶段={}）", workflowId, context.getCurrentStage());
        workflowExecutor.submit(() -> runPipeline(context));
    }

    /**
     * 选题确认后选择发布平台：单平台直接在原工作流继续，多平台扇出并行分支。
     */
    public void selectPlatforms(String workflowId,
                                java.util.List<String> platformNames,
                                java.util.Map<String, java.util.Map<String, String>> platformAccounts) {
        selectPlatforms(workflowId, platformNames, platformAccounts, null, null);
    }

    /**
     * 选题确认后选择发布平台（支持用户从多个选题中选择或自定义选题）。
     *
     * @param topic        用户从 AI 生成的几个选题中选择的标题（可为 null）
     * @param customTopic  用户自定义的选题（可为 null）
     */
    public void selectPlatforms(String workflowId,
                                java.util.List<String> platformNames,
                                java.util.Map<String, java.util.Map<String, String>> platformAccounts,
                                String topic, String customTopic) {
        stateManager.executeWithLock(workflowId, wfId -> {
            TaskContext context = stateManager.loadWorkflowState(wfId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, wfId));

            boolean awaitingSelection = context.getInputs() != null
                    && Boolean.TRUE.equals(context.getInputs()
                            .get(PlatformWorkflowOrchestrator.INPUT_PAUSE_FOR_PLATFORM_SELECTION));
            if (!awaitingSelection) {
                throw new BusinessException(ErrorCode.INVALID_WORKFLOW_STATE,
                        "当前不在选题确认阶段，无法选择平台");
            }

            java.util.List<ContentPlatform> platforms = platformSpecRegistry.resolveAll(platformNames);
            if (platforms.isEmpty()) {
                throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "platforms");
            }
            if (context.getInputs() == null) {
                context.setInputs(new java.util.HashMap<>());
            }
            if (platformAccounts != null && !platformAccounts.isEmpty()) {
                context.getInputs().put(PlatformWorkflowOrchestrator.INPUT_PLATFORM_ACCOUNTS,
                        new java.util.LinkedHashMap<>(platformAccounts));
            }
            context.getInputs().put(PlatformWorkflowOrchestrator.INPUT_PLATFORMS,
                    platforms.stream().map(ContentPlatform::getCode).toList());
            context.getInputs().put("platformNames",
                    platforms.stream().map(ContentPlatform::getDisplayName).toList());
            context.getInputs().put(PlatformWorkflowOrchestrator.INPUT_PLATFORM_SELECTION_DONE, true);
            context.getInputs().remove(PlatformWorkflowOrchestrator.INPUT_PAUSE_FOR_PLATFORM_SELECTION);

            // 用户从多个选题中选择或自定义：写入 inputs 与选题产物，下游内容创作使用该选题
            String chosen = firstNonBlank(topic, customTopic);
            if (!chosen.isBlank()) {
                context.getInputs().put("topic", chosen);
                context.getInputs().put("selectedTopic", chosen);
                if (customTopic != null && !customTopic.isBlank()) {
                    context.getInputs().put("customTopic", customTopic);
                }
                Map<String, Object> topicArtifact = asMap(
                        context.getAccumulatedArtifacts().get(AgentStage.TOPIC_PLANNING.getCode()));
                if (topicArtifact != null) {
                    topicArtifact.put("topic", chosen);
                    topicArtifact.put("recommendedDirection", chosen);
                }
                log.info("[Workflow:{}] User selected topic: {}", wfId, chosen);
            }

            if (platforms.size() == 1) {
                injectSinglePlatform(context, platforms.get(0));
                context.setCurrentStage(com.contentops.common.enums.AgentStage.CONTENT_CREATION.getCode());
                context.setCurrentSubStage(null);
                context.setStatus(TaskStatus.PENDING.name());
                context.setUpdatedAt(java.time.LocalDateTime.now());
                stateManager.saveWorkflowState(wfId, context);
                log.info("[Workflow:{}] Platform selected: {} (single). Continuing pipeline.",
                        wfId, platforms.get(0).getDisplayName());
                workflowExecutor.submit(() -> runPipeline(context));
            } else {
                context.setCurrentStage(com.contentops.common.enums.AgentStage.CONTENT_CREATION.getCode());
                context.setStatus(TaskStatus.IN_PROGRESS.name());
                context.setUpdatedAt(java.time.LocalDateTime.now());
                stateManager.saveWorkflowState(wfId, context);
                log.info("[Workflow:{}] Platform selected: {} (multi). Fanning out.",
                        wfId, platforms.stream().map(ContentPlatform::getDisplayName).toList());
                platformOrchestrator.startWithPlatformBranches(context, platforms, workflowExecutor);
            }
        });
    }

    /**
     * 用户上传封面图片替换 AI 生成的封面（确定性生效：下载/渲染使用新封面）。
     */
    public void setCoverImage(String workflowId, String coverImageUrl) {
        stateManager.updateWorkflowStateAtomically(workflowId, context -> {
            if (context.getInputs() == null) {
                context.setInputs(new java.util.HashMap<>());
            }
            context.getInputs().put("coverImageUrl", coverImageUrl);
            Map<String, Object> generateArtifact = asMap(
                    context.getAccumulatedArtifacts().get("image-design:generate"));
            if (generateArtifact != null) {
                generateArtifact.put("coverImageUrl", coverImageUrl);
            }
            Map<String, Object> imageArtifact = asMap(
                    context.getAccumulatedArtifacts().get(AgentStage.IMAGE_DESIGN.getCode()));
            if (imageArtifact != null) {
                imageArtifact.put("coverImageUrl", coverImageUrl);
            }
            context.setUpdatedAt(java.time.LocalDateTime.now());
        });
    }

    /**
     * 上传创作素材（文档）：保存引用并把文本内容注入 inputs，供选题/内容 Agent 分析创作。
     */
    @SuppressWarnings("unchecked")
    public void addReferenceMaterial(String workflowId, String name, String url, String content) {
        stateManager.updateWorkflowStateAtomically(workflowId, context -> {
            if (context.getInputs() == null) {
                context.setInputs(new java.util.HashMap<>());
            }
            Object raw = context.getInputs().get("referenceMaterials");
            List<Map<String, Object>> materials = raw instanceof List<?> list
                    ? new java.util.ArrayList<>((List<Map<String, Object>>) list)
                    : new java.util.ArrayList<>();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("url", url);
            if (content != null && !content.isBlank()) {
                entry.put("content", content.length() > 20000 ? content.substring(0, 20000) : content);
            }
            materials.add(entry);
            context.getInputs().put("referenceMaterials", materials);
            // 供内容 Agent 直接读取的汇总文本
            StringBuilder joined = new StringBuilder();
            for (Map<String, Object> m : materials) {
                Object c = m.get("content");
                if (c != null) {
                    joined.append(c).append("\n\n");
                }
            }
            context.getInputs().put("referenceContent",
                    joined.length() > 20000 ? joined.substring(0, 20000) : joined.toString());
            context.setUpdatedAt(java.time.LocalDateTime.now());
        });
    }

    /**
     * 用户确定性修改标题/正文（可视化编辑直接落库，下载/渲染使用新版本）。
     */
    public Map<String, Object> updateWorkflowContent(String workflowId, String title, String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        stateManager.updateWorkflowStateAtomically(workflowId, context -> {
            Map<String, Object> artifacts = context.getAccumulatedArtifacts();
            if (artifacts == null) {
                artifacts = new java.util.HashMap<>();
                context.setAccumulatedArtifacts(artifacts);
            }
            String oldTitle = resolveTitle(context, artifacts);
            String oldContent = resolveContent(context, artifacts);
            String newTitle = firstNonBlank(title, oldTitle);
            String newContent = content == null ? oldContent : content;

            Map<String, Object> draft = new LinkedHashMap<>();
            Map<String, Object> existingDraft = asMap(artifacts.get("content-creation:draft"));
            if (existingDraft != null) {
                draft.putAll(existingDraft);
            }
            draft.put("title", newTitle);
            draft.put("draftContent", newContent);
            draft.put("userEdited", true);
            artifacts.put("content-creation:draft", draft);

            Map<String, Object> stageArtifact = asMap(artifacts.get(AgentStage.CONTENT_CREATION.getCode()));
            if (stageArtifact != null) {
                stageArtifact.put("title", newTitle);
                stageArtifact.put("draftContent", newContent);
                stageArtifact.put("content", newContent);
            }
            context.setUpdatedAt(java.time.LocalDateTime.now());
            result.put("title", newTitle);
            result.put("content", newContent);
        });
        result.put("applied", true);
        return result;
    }

    /**
     * 在工作流线程池中执行整条流水线（含异常兜底）。
     */
    private void runPipeline(TaskContext context) {
        try {
            if (useLangGraph()) {
                log.info("[Workflow:{}] Using LangGraph4j engine", context.getWorkflowId());
                langGraphEngine.executeWorkflow(context);
            } else {
                log.info("[Workflow:{}] Using legacy engine", context.getWorkflowId());
                orchestrator.executeStage(context);
            }
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
        } catch (Throwable e) {
            log.error("[Workflow:{}] Pipeline execution failed: {}", context.getWorkflowId(), e.getMessage(), e);
            boolean budgetExceeded = e.getMessage() != null
                    && e.getMessage().contains(CostGuardBlockedException.BUDGET_MARKER);
            context.setStatus(budgetExceeded
                    ? TaskStatus.BUDGET_EXCEEDED.name()
                    : TaskStatus.FAILED.name());
            context.setErrorMessage(e.getMessage());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
        }
    }

    /**
     * Resolve the target platforms from account profile / inputs (lenient matching).
     */
    private java.util.List<ContentPlatform> resolvePlatforms(TaskContext context) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (context.getAccountProfile() != null && context.getAccountProfile().getPlatforms() != null) {
            names.addAll(context.getAccountProfile().getPlatforms());
        }
        if (context.getInputs() != null
                && context.getInputs().get(PlatformWorkflowOrchestrator.INPUT_PLATFORMS) instanceof java.util.List<?> rawList) {
            for (Object item : rawList) {
                names.add(String.valueOf(item));
            }
        }
        return platformSpecRegistry.resolveAll(names);
    }

    /**
     * Single-platform: inject platform code / guidance into inputs, then run the legacy pipeline.
     */
    private void injectSinglePlatform(TaskContext context, ContentPlatform platform) {
        if (context.getInputs() == null) {
            context.setInputs(new java.util.HashMap<>());
        }
        context.getInputs().put(PlatformWorkflowOrchestrator.INPUT_PLATFORM, platform.getCode());
        context.getInputs().put(PlatformWorkflowOrchestrator.INPUT_PLATFORM_NAME, platform.getDisplayName());
        context.getInputs().put(PlatformWorkflowOrchestrator.INPUT_PLATFORM_GUIDANCE,
                platformSpecRegistry.guidance(platform));
        context.getInputs().put("targetPlatforms", java.util.List.of(platform.getDisplayName()));
        if (context.getAccountProfile() != null) {
            context.getAccountProfile().setPlatforms(new java.util.ArrayList<>(java.util.List.of(platform.getDisplayName())));
        }
    }

    /**
     * Get current workflow status from Redis.
     */
    public TaskContext getWorkflowStatus(String workflowId) {
        TaskContext context = stateManager.loadWorkflowState(workflowId).orElse(null);
        if (context == null) {
            return null;
        }
        return platformOrchestrator.aggregateParent(context);
    }

    /**
     * List all workflows from Redis, sorted by creation time (newest first).
     *
     * @return list of all TaskContext objects, newest first
     */
    public List<TaskContext> listAllWorkflows() {
        List<TaskContext> all = stateManager.listAllWorkflows();
        return all.stream()
                .filter(ctx -> !platformOrchestrator.isBranch(ctx))
                .sorted(Comparator.comparing(
                        TaskContext::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .collect(Collectors.toList());
    }

    /**
     * 列出指定用户的工作流（P0 数据隔离）。
     */
    public List<TaskContext> listWorkflowsByOwner(String ownerId) {
        return stateManager.listWorkflowsByOwner(ownerId).stream()
                .filter(ctx -> !platformOrchestrator.isBranch(ctx))
                .collect(Collectors.toList());
    }

    /**
     * Approve current stage and proceed to the next.
     * This is called after human review.
     *
     * <p><b>P0 修复：</b>使用分布式锁保护状态修改，防止并发 approve 导致状态不一致。
     *
     * <p><b>双引擎支持：</b>
     * <ul>
     *   <li>LangGraph 模式：调用 {@link LangGraphWorkflowEngine#resumeWorkflow}</li>
     *   <li>Legacy 模式：保留 A计划的循环边界检查逻辑</li>
     * </ul>
     */
    public void approveAndProceed(String workflowId, Map<String, Object> feedback) {
        stateManager.executeWithLock(workflowId, wfId -> {
            TaskContext context = stateManager.loadWorkflowState(wfId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, wfId));

            if (context.getInputs() != null
                    && Boolean.TRUE.equals(context.getInputs()
                            .get(PlatformWorkflowOrchestrator.INPUT_PAUSE_FOR_PLATFORM_SELECTION))) {
                throw new BusinessException(ErrorCode.INVALID_WORKFLOW_STATE,
                        "选题已完成，请先调用 select-platforms 选择发布平台");
            }

            if (!TaskStatus.AWAITING_HUMAN.name().equals(context.getStatus())) {
                throw new BusinessException(ErrorCode.WORKFLOW_NOT_AWAITING_REVIEW, context.getStatus());
            }

            if (useLangGraph()) {
                log.info("[Workflow:{}] Resuming via LangGraph engine", wfId);
                langGraphEngine.resumeWorkflow(context, feedback);
                stateManager.saveWorkflowState(wfId, context);
                return;
            }

            // Legacy 模式：保留 A计划的循环边界检查逻辑
            if (feedback != null && !feedback.isEmpty()) {
                if (context.getInputs() == null) {
                    context.setInputs(new java.util.HashMap<>());
                }
                context.getInputs().put("humanFeedback", feedback);
            }

            com.contentops.common.enums.AgentStage currentStage =
                    com.contentops.common.enums.AgentStage.fromCode(context.getCurrentStage());
            com.contentops.common.enums.AgentStage nextStage = currentStage.next();

            if (orchestrator.checkAndHandleCycleBoundary(context, currentStage, nextStage)) {
                log.info("[Workflow:{}] Cycle boundary handled in approveAndProceed. cycle={}",
                        wfId, context.getCycleCount());
                return;
            }

            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());

            log.info("[Workflow:{}] Human approved. Advancing {} → {}",
                    wfId, currentStage.getCode(), nextStage.getCode());

            orchestrator.executeStage(context);
        });
    }

    /**
     * Retry the current stage after a failure.
     *
     * <p><b>P0 修复：</b>使用分布式锁保护状态修改。
     */
    public void retryStage(String workflowId) {
        stateManager.executeWithLock(workflowId, wfId -> {
            TaskContext context = stateManager.loadWorkflowState(wfId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, wfId));

            context.setStatus(TaskStatus.PENDING.name());
            context.setErrorMessage(null);

            log.info("[Workflow:{}] Retrying stage: {}", wfId, context.getCurrentStage());

            if (useLangGraph()) {
                langGraphEngine.executeWorkflow(context);
            } else {
                orchestrator.executeStage(context);
            }
            stateManager.saveWorkflowState(wfId, context);
        });
    }

    /**
     * 独立服务执行：仅运行 data-analysis 或 optimization 单个阶段，
     * 不进入主流水线（发布完成后工作流即 COMPLETED，分析和优化按需单独调用）。
     */
    public void runStandaloneStage(String workflowId, AgentStage stage) {
        if (stage != AgentStage.DATA_ANALYSIS && stage != AgentStage.OPTIMIZATION) {
            throw new BusinessException(ErrorCode.INVALID_WORKFLOW_STATE,
                    "Only data-analysis / optimization can run as standalone services");
        }
        stateManager.executeWithLock(workflowId, wfId -> {
            TaskContext context = stateManager.loadWorkflowState(wfId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, wfId));

            if (context.getInputs() == null) {
                context.setInputs(new java.util.HashMap<>());
            }
            context.setCurrentStage(stage.getCode());
            context.setCurrentSubStage(null);
            context.setStatus(TaskStatus.PENDING.name());
            context.setErrorMessage(null);
            context.setUpdatedAt(java.time.LocalDateTime.now());
            stateManager.saveWorkflowState(wfId, context);

            log.info("[Workflow:{}] Standalone service triggered: {}", wfId, stage.getCode());
            workflowExecutor.submit(() -> runPipeline(context));
        });
    }

    /**
     * 将讨论会话中的最新用户修改意见应用到作品上：
     * 读取会话最后一条用户消息，作为 modificationRequest 调用内容 Agent 重新生成草稿，
     * 并把新标题/正文合并回 accumulatedArtifacts（下载 ZIP 会基于最新版本重新渲染）。
     *
     * @param workflowId 作品（工作流）ID
     * @param sessionId  绑定该作品的讨论会话 ID
     * @return 应用后的标题与正文
     */
    public Map<String, Object> applyDiscussionModification(String workflowId, String sessionId) {
        DiscussionSession session = discussionSessionService.getSession(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_WORKFLOW_STATE,
                        "讨论会话不存在: " + sessionId));
        String modificationRequest = extractLastUserMessage(session);
        if (modificationRequest == null || modificationRequest.isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT,
                    "会话中还没有可应用的修改意见");
        }

        TaskContext context = stateManager.loadWorkflowState(workflowId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, workflowId));
        Map<String, Object> artifacts = context.getAccumulatedArtifacts() != null
                ? context.getAccumulatedArtifacts()
                : new java.util.HashMap<>();

        Map<String, Object> inputs = new LinkedHashMap<>();
        if (context.getInputs() != null) {
            inputs.putAll(context.getInputs());
        }
        inputs.put("articleTitle", resolveTitle(context, artifacts));
        inputs.put("articleContent", resolveContent(context, artifacts));
        inputs.put("modificationRequest", modificationRequest);
        inputs.put("discussionSessionId", sessionId);

        AgentTaskRequest request = AgentTaskRequest.of(
                workflowId,
                AgentStage.CONTENT_CREATION.getCode(),
                context.getAccountProfile(),
                inputs,
                artifacts);

        AgentResponse<Map<String, Object>> response = agentGateway.callContentDraft(request);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new BusinessException(ErrorCode.AGENT_CALL_FAILED,
                    response == null ? "内容 Agent 无响应" : response.getError());
        }

        Map<String, Object> data = response.getData();
        String newTitle = firstNonBlank(
                data.get("title"),
                data.get("articleTitle"),
                resolveTitle(context, artifacts));
        String newContent = firstNonBlank(
                data.get("draftContent"),
                data.get("content"),
                data.get("articleContent"),
                resolveContent(context, artifacts));

        String finalWorkflowId = workflowId;
        String finalTitle = newTitle;
        String finalContent = newContent;
        stateManager.updateWorkflowStateAtomically(workflowId, ctx -> {
            Map<String, Object> draft = new LinkedHashMap<>();
            if (ctx.getAccumulatedArtifacts() != null) {
                Map<String, Object> existingDraft = asMap(
                        ctx.getAccumulatedArtifacts().get("content-creation:draft"));
                if (existingDraft != null) {
                    draft.putAll(existingDraft);
                }
            }
            draft.put("title", finalTitle);
            draft.put("draftContent", finalContent);
            draft.put("modifiedByDiscussion", finalWorkflowId);
            draft.put("discussionSessionId", sessionId);
            ctx.getAccumulatedArtifacts().put("content-creation:draft", draft);

            Map<String, Object> stageArtifact = asMap(
                    ctx.getAccumulatedArtifacts().get(AgentStage.CONTENT_CREATION.getCode()));
            if (stageArtifact != null) {
                stageArtifact.put("title", finalTitle);
                stageArtifact.put("draftContent", finalContent);
                stageArtifact.put("content", finalContent);
                stageArtifact.put("discussionSessionId", sessionId);
            }
            ctx.setDiscussionSessionId(sessionId);
            ctx.setOutputs(data);
            ctx.setUpdatedAt(java.time.LocalDateTime.now());
        });

        log.info("[Workflow:{}] Discussion modification applied: sessionId={}, requestLength={}",
                workflowId, sessionId, modificationRequest.length());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", true);
        result.put("sessionId", sessionId);
        result.put("title", newTitle);
        result.put("content", newContent);
        result.put("modificationRequest", modificationRequest);
        return result;
    }

    private String extractLastUserMessage(DiscussionSession session) {
        if (session.getTurns() == null) {
            return session.getFuzzyIdea();
        }
        for (int i = session.getTurns().size() - 1; i >= 0; i--) {
            DiscussionSession.DiscussionTurn turn = session.getTurns().get(i);
            if (turn != null && "user".equals(turn.getRole())
                    && turn.getContent() != null && !turn.getContent().isBlank()) {
                return turn.getContent();
            }
        }
        return session.getFuzzyIdea();
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    /**
     * 将已完成的作品打包为可下载的 ZIP（markdown + HTML + 封面/配图清单 + manifest）。
     */
    public byte[] exportWorkflowZip(String workflowId) {
        TaskContext context = getWorkflowStatus(workflowId);
        if (context == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, workflowId);
        }
        return buildZip(context);
    }

    @SuppressWarnings("unchecked")
    private byte[] buildZip(TaskContext context) {
        Map<String, Object> artifacts = context.getAccumulatedArtifacts();
        if (artifacts == null) {
            artifacts = new java.util.HashMap<>();
        }

        String title = resolveTitle(context, artifacts);
        String content = resolveContent(context, artifacts);
        String coverImageUrl = resolveCoverImageUrl(context, artifacts);
        List<Map<String, Object>> images = resolveImages(artifacts);
        Map<String, Object> publishing = asMap(artifacts.get(AgentStage.PUBLISHING.getCode()));
        Object publishModeRaw = context.getInputs() != null
                ? context.getInputs().get("publishMode")
                : null;
        String publishMode = PublishMode.fromCode(
                publishModeRaw == null ? null : String.valueOf(publishModeRaw)).getCode();

        String safeTitle = sanitizeFileName(title);
        if (safeTitle == null || safeTitle.isBlank()) {
            safeTitle = "contentops-work";
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            // article.md
            StringBuilder md = new StringBuilder();
            if (title != null && !title.isBlank()) {
                md.append("# ").append(title).append("\n\n");
            }
            md.append(content == null ? "" : content).append("\n");
            putEntry(zip, safeTitle + ".md", md.toString());

            // article.html
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                    .append("<title>").append(escapeHtml(title == null ? "" : title))
                    .append("</title></head><body>");
            if (title != null && !title.isBlank()) {
                html.append("<h1>").append(escapeHtml(title)).append("</h1>");
            }
            if (coverImageUrl != null && !coverImageUrl.isBlank()) {
                html.append("<p><img src=\"").append(escapeHtml(coverImageUrl))
                        .append("\" alt=\"cover\" style=\"max-width:100%;\"/></p>");
            }
            html.append(markdownConverter.convertToHtml(content == null ? "" : content));
            html.append("</body></html>");
            putEntry(zip, safeTitle + ".html", html.toString());

            // README.txt
            putEntry(zip, "README.txt", buildReadme(context, title, publishMode, coverImageUrl));

            // cover-image-url.txt
            putEntry(zip, "cover-image-url.txt",
                    (coverImageUrl == null || coverImageUrl.isBlank()
                            ? "（未生成封面图）" : coverImageUrl) + "\n");

            // images list
            StringBuilder imagesTxt = new StringBuilder();
            int idx = 1;
            for (Map<String, Object> img : images) {
                Object url = img.get("url");
                if (url == null) {
                    url = img.get("imageUrl");
                }
                Object purpose = img.get("purpose");
                imagesTxt.append(idx++).append(". ")
                        .append(purpose == null ? "配图" : purpose)
                        .append(": ")
                        .append(url == null ? "" : url)
                        .append("\n");
            }
            putEntry(zip, "images/images-list.txt", imagesTxt.toString());

            // 平台化渲染产物：小红书轮播卡片 + 公众号排版稿 + 多尺寸封面（21:9 / 1:1 / 16:9）
            List<String> targetPlatformNames = resolvePlatformNames(context);
            boolean renderXiaohongshu = targetPlatformNames.isEmpty()
                    || targetPlatformNames.stream()
                    .anyMatch(n -> ContentPlatform.XIAOHONGSHU.equals(ContentPlatform.from(n)));
            boolean renderWechat = targetPlatformNames.isEmpty()
                    || targetPlatformNames.stream()
                    .anyMatch(n -> ContentPlatform.WECHAT_OFFICIAL_ACCOUNT.equals(ContentPlatform.from(n)));
            boolean renderDouyin = targetPlatformNames.isEmpty()
                    || targetPlatformNames.stream()
                    .anyMatch(n -> ContentPlatform.DOUYIN.equals(ContentPlatform.from(n)));
            boolean renderBilibili = targetPlatformNames.isEmpty()
                    || targetPlatformNames.stream()
                    .anyMatch(n -> ContentPlatform.BILIBILI.equals(ContentPlatform.from(n)));

            // 主题/版式参数化：合集按类型绑定主题，同一作品放进不同类型合集自动换视觉变体
            String themeCode = resolveCollectionTheme(context.getWorkflowId());
            String layout = "classic";

            // 需要转 PNG 的 HTML 画板（渲染服务可用时批量截图打进 ZIP）
            List<PngRenderClient.RenderEntry> pngEntries = new java.util.ArrayList<>();
            List<String> xhsCards = List.of();
            SocialCardRenderer.CarouselResult carousel = null;
            if (renderXiaohongshu) {
                carousel = socialCardRenderer.renderXiaohongshuCarouselDetailed(
                        title, content == null ? "" : content, coverImageUrl,
                        images, publishMode, themeCode, layout);
                xhsCards = carousel.cards();
                for (int i = 0; i < xhsCards.size(); i++) {
                    String cardName = String.format("xiaohongshu/card-%02d", i + 1);
                    putEntry(zip, cardName + ".html", xhsCards.get(i));
                    pngEntries.add(new PngRenderClient.RenderEntry(
                            cardName + ".png", xhsCards.get(i), 1080, 1440));
                }
                putEntry(zip, "xiaohongshu/index.html",
                        socialCardRenderer.renderCarouselPreview(title, xhsCards));
            }
            if (renderWechat) {
                putEntry(zip, "wechat/article.html",
                        socialCardRenderer.renderWechatArticle(
                                title, content == null ? "" : content, coverImageUrl));
                SocialCardRenderer.CoverPair coverPair = socialCardRenderer.renderWechatCoverPair(
                        title, content == null ? "" : content, coverImageUrl, themeCode);
                putEntry(zip, "wechat/cover-wide-21x9.html", coverPair.wideHtml());
                pngEntries.add(new PngRenderClient.RenderEntry(
                        "wechat/cover-wide-21x9.png", coverPair.wideHtml(), 2100, 900));
                putEntry(zip, "wechat/cover-square-1x1.html", coverPair.squareHtml());
                pngEntries.add(new PngRenderClient.RenderEntry(
                        "wechat/cover-square-1x1.png", coverPair.squareHtml(), 1080, 1080));
                putEntry(zip, "wechat/cover-pair-preview.html",
                        socialCardRenderer.renderCoverPairPreview(title, coverPair));
            }
            if (renderDouyin) {
                String douyinHtml = socialCardRenderer.renderDouyinCover(
                        title, content == null ? "" : content, coverImageUrl, themeCode);
                putEntry(zip, "douyin/cover-16x9.html", douyinHtml);
                pngEntries.add(new PngRenderClient.RenderEntry(
                        "douyin/cover-16x9.png", douyinHtml, 1920, 1080));
            }
            if (renderBilibili) {
                String bilibiliHtml = socialCardRenderer.renderDouyinCover(
                        title, content == null ? "" : content, coverImageUrl, themeCode);
                putEntry(zip, "bilibili/cover-16x9.html", bilibiliHtml);
                pngEntries.add(new PngRenderClient.RenderEntry(
                        "bilibili/cover-16x9.png", bilibiliHtml, 1920, 1080));
            }

            // HTML→PNG：渲染服务可用时把画板批量截图打进 ZIP，不可用时自动降级（只出 HTML）
            Map<String, byte[]> pngs = pngRenderClient.renderPngs(pngEntries);
            for (Map.Entry<String, byte[]> png : pngs.entrySet()) {
                zip.putNextEntry(new ZipEntry(png.getKey()));
                zip.write(png.getValue());
                zip.closeEntry();
            }

            // P1：随 ZIP 附校验报告（真实渲染测量/估算 + 每张卡溢出 + 自动重排记录）
            StringBuilder qa = new StringBuilder();
            qa.append("ContentOps 作品校验报告 (qa-report)\n")
                    .append("====================================\n")
                    .append("主题: ").append(themeCode == null ? "默认(xhs)" : themeCode).append("\n")
                    .append("版式: ").append(layout).append("\n")
                    .append("渲染模式: ").append(carousel != null && !carousel.qa().isEmpty()
                            && carousel.qa().get(0).measured() ? "真实渲染测量" : "估算").append("\n")
                    .append("小红书卡片数: ").append(xhsCards.size()).append("\n\n");
            if (carousel != null) {
                for (SocialCardRenderer.CardQa cardQa : carousel.qa()) {
                    qa.append(cardQa.summary()).append("\n");
                }
            }
            qa.append("\n提示: 溢出 > 2px 的卡片会触发自动重排；若仍有溢出，请缩短正文或减少配图。\n");
            putEntry(zip, "qa-report.txt", qa.toString());

            // manifest.json
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("workflowId", context.getWorkflowId());
            manifest.put("title", title);
            manifest.put("publishMode", publishMode);
            manifest.put("status", context.getStatus());
            manifest.put("createdAt", String.valueOf(context.getCreatedAt()));
            manifest.put("coverImageUrl", coverImageUrl);
            manifest.put("images", images);
            manifest.put("publications", publishing == null ? null : publishing.get("publications"));
            manifest.put("platforms", context.getInputs() != null
                    ? context.getInputs().get("platformNames") : null);
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("xiaohongshuCardCount", xhsCards.size());
            rendered.put("wechatArticleHtml", renderWechat);
            rendered.put("wechatCoverPair", renderWechat);
            rendered.put("douyinCover16x9", renderDouyin);
            rendered.put("bilibiliCover16x9", renderBilibili);
            rendered.put("pngExportedCount", pngs.size());
            rendered.put("theme", themeCode == null ? "default" : themeCode);
            rendered.put("layout", layout);
            rendered.put("qaMeasured", carousel != null && !carousel.qa().isEmpty()
                    && carousel.qa().get(0).measured());
            rendered.put("renderer", "SocialCardRenderer v1.2");
            manifest.put("rendered", rendered);
            putEntry(zip, "manifest.json", objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest));
        } catch (Exception e) {
            log.error("[Workflow:{}] Export zip failed", context.getWorkflowId(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "作品打包失败: " + e.getMessage());
        }
        return bos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private List<String> resolvePlatformNames(TaskContext context) {
        if (context.getInputs() == null) {
            return List.of();
        }
        Object raw = context.getInputs().get("platformNames");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * 合集按类型绑定主题：取作品所属第一个合集的类型，映射到主题 code；
     * 无合集时返回 null（渲染器用默认主题）。
     */
    private String resolveCollectionTheme(String workflowId) {
        try {
            List<com.contentops.common.collection.WorkCollection> collections =
                    workCollectionService.listByWorkflow(workflowId);
            if (!collections.isEmpty() && collections.get(0).getType() != null
                    && !collections.get(0).getType().isBlank()) {
                return SocialCardRenderer.themeCodeForCollectionType(collections.get(0).getType());
            }
        } catch (Exception e) {
            log.debug("[Workflow:{}] resolve collection theme failed: {}", workflowId, e.getMessage());
        }
        return null;
    }

    private void putEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String resolveTitle(TaskContext context, Map<String, Object> artifacts) {
        Map<String, Object> draftArtifact = asMap(artifacts.get("content-creation:draft"));
        if (draftArtifact != null) {
            Object title = draftArtifact.get("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                return String.valueOf(title);
            }
        }
        Map<String, Object> contentArtifact = asMap(artifacts.get(AgentStage.CONTENT_CREATION.getCode()));
        if (contentArtifact != null) {
            Object title = contentArtifact.get("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                return String.valueOf(title);
            }
        }
        Map<String, Object> topicArtifact = asMap(artifacts.get(AgentStage.TOPIC_PLANNING.getCode()));
        if (topicArtifact != null) {
            Object topic = topicArtifact.get("topic");
            if (topic != null && !String.valueOf(topic).isBlank()) {
                return String.valueOf(topic);
            }
            Object recommended = topicArtifact.get("recommendedDirection");
            if (recommended != null && !String.valueOf(recommended).isBlank()) {
                return String.valueOf(recommended);
            }
            Object topics = topicArtifact.get("topics");
            if (topics instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
                Object t = ((Map<?, ?>) first).get("title");
                if (t != null) {
                    return String.valueOf(t);
                }
            }
        }
        if (context.getInputs() != null) {
            Object inputTitle = context.getInputs().get("articleTitle");
            if (inputTitle != null && !String.valueOf(inputTitle).isBlank()) {
                return String.valueOf(inputTitle);
            }
            Object topic = context.getInputs().get("topic");
            if (topic != null && !String.valueOf(topic).isBlank()) {
                return String.valueOf(topic);
            }
        }
        return "ContentOps 作品";
    }

    private String resolveContent(TaskContext context, Map<String, Object> artifacts) {
        Map<String, Object> contentArtifact = asMap(artifacts.get(AgentStage.CONTENT_CREATION.getCode()));
        if (contentArtifact != null) {
            for (String key : List.of("content", "draftContent", "articleContent")) {
                Object value = contentArtifact.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
        }
        Map<String, Object> draftArtifact = asMap(artifacts.get("content-creation:draft"));
        if (draftArtifact != null) {
            Object value = draftArtifact.get("draftContent");
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        if (context.getInputs() != null) {
            Object articleContent = context.getInputs().get("articleContent");
            if (articleContent != null && !String.valueOf(articleContent).isBlank()) {
                return String.valueOf(articleContent);
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String resolveCoverImageUrl(TaskContext context, Map<String, Object> artifacts) {
        // 渐进式生成：图片产物在 image-design:generate
        Map<String, Object> generateArtifact = asMap(artifacts.get("image-design:generate"));
        if (generateArtifact != null) {
            Object cover = generateArtifact.get("coverImageUrl");
            if (cover != null && !String.valueOf(cover).isBlank()) {
                return String.valueOf(cover);
            }
            Object covers = generateArtifact.get("covers");
            if (covers instanceof List<?> list && !list.isEmpty()) {
                Map<String, Object> first = asMap(list.get(0));
                if (first != null && first.get("imageUrl") != null) {
                    return String.valueOf(first.get("imageUrl"));
                }
            }
            Object images = generateArtifact.get("images");
            if (images instanceof List<?> list && !list.isEmpty()) {
                for (Object item : list) {
                    Map<String, Object> img = asMap(item);
                    if (img != null && String.valueOf(img.get("purpose")).contains("封面")) {
                        Object url = img.get("url");
                        return url == null ? "" : String.valueOf(url);
                    }
                }
            }
        }
        Map<String, Object> imageArtifact = asMap(artifacts.get(AgentStage.IMAGE_DESIGN.getCode()));
        if (imageArtifact != null) {
            Object cover = imageArtifact.get("coverImageUrl");
            if (cover != null && !String.valueOf(cover).isBlank()) {
                return String.valueOf(cover);
            }
            Object covers = imageArtifact.get("covers");
            if (covers instanceof List<?> list && !list.isEmpty()) {
                Map<String, Object> first = asMap(list.get(0));
                if (first != null && first.get("imageUrl") != null) {
                    return String.valueOf(first.get("imageUrl"));
                }
            }
            Object images = imageArtifact.get("images");
            if (images instanceof List<?> list && !list.isEmpty()) {
                for (Object item : list) {
                    Map<String, Object> img = asMap(item);
                    if (img != null && "封面".equals(String.valueOf(img.get("purpose")))) {
                        Object url = img.get("url");
                        return url == null ? "" : String.valueOf(url);
                    }
                }
            }
        }
        if (context.getInputs() != null) {
            Object cover = context.getInputs().get("coverImageUrl");
            if (cover != null && !String.valueOf(cover).isBlank()) {
                return String.valueOf(cover);
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveImages(Map<String, Object> artifacts) {
        Map<String, Object> generateArtifact = asMap(artifacts.get("image-design:generate"));
        if (generateArtifact != null) {
            Object raw = generateArtifact.get("images");
            if (raw instanceof List<?> list) {
                List<Map<String, Object>> result = new java.util.ArrayList<>();
                for (Object item : list) {
                    Map<String, Object> img = asMap(item);
                    if (img != null) {
                        result.add(img);
                    }
                }
                return result;
            }
        }
        Map<String, Object> imageArtifact = asMap(artifacts.get(AgentStage.IMAGE_DESIGN.getCode()));
        if (imageArtifact == null) {
            return List.of();
        }
        Object raw = imageArtifact.get("images");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : list) {
            Map<String, Object> img = asMap(item);
            if (img != null) {
                result.add(img);
            }
        }
        return result;
    }

    private String buildReadme(TaskContext context, String title, String publishMode,
                               String coverImageUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("ContentOps 作品导出\n")
                .append("====================\n\n")
                .append("工作流 ID: ").append(context.getWorkflowId()).append("\n")
                .append("标题: ").append(title == null ? "" : title).append("\n")
                .append("发布模式: ").append(publishMode).append("\n")
                .append("状态: ").append(context.getStatus()).append("\n");
        if (context.getInputs() != null && context.getInputs().get("platformNames") != null) {
            sb.append("目标平台: ").append(context.getInputs().get("platformNames")).append("\n");
        }
        sb.append("封面图: ").append(coverImageUrl == null || coverImageUrl.isBlank()
                ? "未生成" : coverImageUrl).append("\n\n");
        sb.append("文件说明:\n")
                .append("  - <标题>.md      Markdown 原文\n")
                .append("  - <标题>.html    HTML 排版稿（可直接预览）\n")
                .append("  - wechat/article.html    公众号排版稿（全内联样式，可直接粘贴到公众号编辑器）\n")
                .append("  - wechat/cover-wide-21x9.html    公众号 21:9 头图封面\n")
                .append("  - wechat/cover-square-1x1.html   公众号 1:1 分享卡封面\n")
                .append("  - wechat/cover-pair-preview.html 公众号封面对预览页\n")
                .append("  - xiaohongshu/card-01.html ... card-N.html  小红书 1080×1440 轮播卡片（浏览器打开后截图/导出 PNG）\n")
                .append("  - xiaohongshu/index.html  小红书卡片预览页\n")
                .append("  - douyin/cover-16x9.html   抖音封面（16:9）\n")
                .append("  - bilibili/cover-16x9.html  B站封面（16:9）\n")
                .append("  - *.png   开启 contentops.render-service 后自动渲染生成的 PNG（无需手动截图）\n")
                .append("  - qa-report.txt  版式校验报告（每张卡溢出测量 + 自动重排记录）\n")
                .append("  - cover-image-url.txt  封面图地址\n")
                .append("  - images/images-list.txt  正文配图地址列表\n")
                .append("  - manifest.json  结构化元数据\n\n");
        sb.append("提示: 若需离线图片，可自行下载 images-list.txt 中列出的图片。\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private String sanitizeFileName(String name) {
        if (name == null) {
            return null;
        }
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * 确认当前子阶段并推进到下一个子阶段（渐进式生成）。
     *
     * <p>当工作流处于 AWAITING_HUMAN 状态且有 currentSubStage 时，
     * 用户确认子阶段一（大纲/风格方向）的输出后，调用此方法推进到子阶段二。
     *
     * <p><b>LangGraph 模式</b>下，子阶段确认通过 {@link LangGraphWorkflowEngine#resumeWorkflow} 处理，
     * 因为 LangGraph4j 的 interruptBefore 机制会自动在 content/image 节点前暂停。
     *
     * <p><b>P0 修复：</b>使用分布式锁保护状态修改。
     *
     * @param workflowId 工作流 ID
     * @param feedback   可选的反馈/修改（如修改后的大纲、选择的风格方向）
     */
    public void confirmSubStage(String workflowId, Map<String, Object> feedback) {
        stateManager.executeWithLock(workflowId, wfId -> {
            TaskContext context = stateManager.loadWorkflowState(wfId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, wfId));

            if (!TaskStatus.AWAITING_HUMAN.name().equals(context.getStatus())) {
                throw new BusinessException(ErrorCode.WORKFLOW_NOT_AWAITING_CONFIRMATION, context.getStatus());
            }

            if (useLangGraph()) {
                log.info("[Workflow:{}] Confirming via LangGraph resume", wfId);
                langGraphEngine.resumeWorkflow(context, feedback);
                stateManager.saveWorkflowState(wfId, context);
                return;
            }

            // Legacy 模式
            if (context.getCurrentSubStage() == null || context.getCurrentSubStage().isBlank()) {
                throw new BusinessException(ErrorCode.NO_SUBSTAGE_TO_CONFIRM);
            }

            log.info("[Workflow:{}] Confirming sub-stage: {}", wfId, context.getCurrentSubStage());
            orchestrator.confirmAndProceedSubStage(context, feedback);
        });
    }
}
