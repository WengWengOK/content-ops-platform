package com.contentops.orchestrator.workflow;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.SubStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.event.StageTransitionEvent;
import com.contentops.common.agent.AgentEventRepository;
import com.contentops.common.observability.LlmJudgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import com.contentops.common.observability.LlmTraceContext;
import com.contentops.common.cost.CostGuardBlockedException;
import com.contentops.common.quality.QualityAssessmentService;
import com.contentops.common.quality.QualityScore;
import com.contentops.common.quality.QualityThresholdProperties;
import com.contentops.common.knowledge.AgentOutputIngester;
import com.contentops.common.knowledge.RagRetrievalEnhancer;
import com.contentops.common.memory.ProjectMemoryService;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.gateway.AgentGateway;
import com.contentops.orchestrator.service.PlatformWorkflowOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单阶段执行器 — 负责单个 AgentStage 的执行逻辑（无子阶段的阶段）。
 *
 * <p>从 {@link PipelineOrchestrator} 拆分而来（P2-13），保持原有逻辑不变。
 * 职责：构建请求、路由到 Agent、处理成功/失败、发布阶段事件，并在阶段完成时
 * 委托 {@link QualityEnricher} 做质量评估、委托 {@link CycleHandler} 做循环边界检查。
 *
 * <h3>P2 优化：质量驱动重试</h3>
 * <p>当质量评估启用且自动重试开启时，{@link #routeToAgentWithQualityRetry}
 * 会在 Agent 调用后评估输出质量，低于阈值时追加重试反馈并指数退避重试，
 * 确保关键阶段的输出质量达标。
 *
 * <p>本类持有 {@code publishEvent} 与 {@code kafkaTemplate}（单体模式下可选），
 * 供 {@link SubStageExecutor} 与 {@link CycleHandler} 共享事件发布能力。
 */
@Slf4j
@Component
public class StageExecutor {

    private final WorkflowStateManager stateManager;
    private final AgentGateway agentGateway;
    private final QualityEnricher qualityEnricher;
    private final SubStageExecutor subStageExecutor;
    private final CycleHandler cycleHandler;
    private final QualityAssessmentService qualityAssessmentService;
    private final QualityThresholdProperties qualityProperties;
    private final org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    private final AgentEventRepository agentEventRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final LlmJudgeService llmJudgeService;
    private final AgentOutputIngester agentOutputIngester;
    private final RagRetrievalEnhancer ragRetrievalEnhancer;
    private final ProjectMemoryService projectMemoryService;

    // 单体模式下 Kafka 可能不存在，设为可选（用 Object 避免依赖 spring-kafka）。
    // 必须用 @Qualifier 限定 bean 名称：Object 类型会匹配容器内所有 Bean，
    // 导致 NoUniqueBeanDefinitionException 启动失败（P0 修复）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("kafkaTemplate")
    private Object kafkaTemplate;

    public StageExecutor(WorkflowStateManager stateManager,
                         AgentGateway agentGateway,
                         QualityEnricher qualityEnricher,
                         @Lazy SubStageExecutor subStageExecutor,
                         @Lazy CycleHandler cycleHandler,
                         QualityAssessmentService qualityAssessmentService,
                         QualityThresholdProperties qualityProperties,
                         org.springframework.context.ApplicationEventPublisher applicationEventPublisher,
                         AgentEventRepository agentEventRepository,
                         ObjectMapper objectMapper,
                         Tracer tracer,
                         LlmJudgeService llmJudgeService,
                         AgentOutputIngester agentOutputIngester,
                         RagRetrievalEnhancer ragRetrievalEnhancer,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         ProjectMemoryService projectMemoryService) {
        this.stateManager = stateManager;
        this.agentGateway = agentGateway;
        this.qualityEnricher = qualityEnricher;
        this.subStageExecutor = subStageExecutor;
        this.cycleHandler = cycleHandler;
        this.qualityAssessmentService = qualityAssessmentService;
        this.qualityProperties = qualityProperties;
        this.applicationEventPublisher = applicationEventPublisher;
        this.agentEventRepository = agentEventRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.llmJudgeService = llmJudgeService;
        this.agentOutputIngester = agentOutputIngester;
        this.ragRetrievalEnhancer = ragRetrievalEnhancer;
        this.projectMemoryService = projectMemoryService;
    }

    /**
     * Execute the current stage for a workflow.
     *
     * <p>If the stage has sub-stages, starts with the first sub-stage.
     * Otherwise, executes the stage with quality-driven retry (P2 优化).
     */
    public void executeStage(TaskContext context) {
        AgentStage stage = AgentStage.fromCode(context.getCurrentStage());
        log.info("[Workflow:{}] Executing stage: {} ({})",
                context.getWorkflowId(), stage.getCode(), stage.getNameCn());
        LlmTraceContext.set(stage.getCode(), stage.getCode());

        context.setStatus(TaskStatus.IN_PROGRESS.name());
        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        // Publish start event
        publishEvent(StageTransitionEvent.started(context.getWorkflowId(), stage.getCode()));

        Span span = tracer.nextSpan().name("workflow.stage")
                .tag("workflowId", context.getWorkflowId())
                .tag("stage", stage.getCode())
                .start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
        try {
            // Check if this stage has sub-stages (progressive generation)
            if (SubStage.hasSubStages(stage)) {
                SubStage firstSub = SubStage.firstOf(stage);
                log.info("[Workflow:{}] Stage {} has sub-stages. Starting with: {} ({})",
                        context.getWorkflowId(), stage.getCode(), firstSub.getCode(), firstSub.getNameCn());
                context.setCurrentSubStage(firstSub.getCode());
                subStageExecutor.executeSubStage(context, firstSub);
            } else {
                // No sub-stages: execute with quality-driven retry
                AgentTaskRequest request = buildRequest(context);
                AgentResponse<Map<String, Object>> response = routeToAgentWithQualityRetry(context, stage, request);

                if (response.isSuccess()) {
                    handleStageSuccess(context, stage, response);
                } else {
                    handleStageFailure(context, stage, response.getError());
                }
            }
        } catch (Exception e) {
            log.error("[Workflow:{}] Stage {} failed with exception",
                    context.getWorkflowId(), stage.getCode(), e);
            span.tag("status", "failed");
            span.error(e);
            handleStageFailure(context, stage, e.getMessage());
        } finally {
            LlmTraceContext.clear();
            span.end();
        }
        }
    }

    /**
     * Route the task to the appropriate agent with quality-driven retry (P2 优化).
     *
     * <p>当质量评估启用且自动重试开启时，在 Agent 调用后评估输出质量：
     * <ol>
     *   <li>调用 Agent 获取响应</li>
     *   <li>提取文本内容并评估质量评分</li>
     *   <li>若评分 ≥ 阈值 → 返回当前结果</li>
     *   <li>若评分 < 阈值且仍有重试次数 → 追加质量反馈到请求，指数退避后重试</li>
     *   <li>达到最大重试次数 → 返回评分最高的结果</li>
     * </ol>
     *
     * <p>质量评估或自动重试未启用时，直接调用一次返回。
     *
     * @param context 工作流上下文
     * @param stage   Agent 阶段
     * @param request 原始请求
     * @return Agent 响应（可能经过重试）
     */
    private AgentResponse<Map<String, Object>> routeToAgentWithQualityRetry(
            TaskContext context, AgentStage stage, AgentTaskRequest request) {

        if (!qualityProperties.isEnabled() || !qualityProperties.isAutoRetry()) {
            return routeToAgent(stage, request);
        }

        int maxRetries = qualityProperties.getMaxRetries();
        int minScore = qualityProperties.getMinScore();
        int totalAttempts = maxRetries + 1;

        AgentResponse<Map<String, Object>> bestResponse = null;
        int bestScore = -1;
        int bestRetryIndex = 0;

        AgentTaskRequest currentRequest = request;

        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            boolean isRetry = attempt > 0;
            log.info("[QualityRetry] workflow={}, stage={}, attempt={}/{}, isRetry={}",
                    context.getWorkflowId(), stage.getCode(), attempt + 1, totalAttempts, isRetry);

            // 调用 Agent
            AgentResponse<Map<String, Object>> response = routeToAgent(stage, currentRequest);
            log.debug("[QualityRetry] workflow={}, stage={}, attempt={} agent call returned: success={}, dataNull={}",
                    context.getWorkflowId(), stage.getCode(), attempt + 1,
                    response != null ? response.isSuccess() : "null",
                    response != null && response.getData() == null);

            if (!response.isSuccess() || response.getData() == null) {
                log.warn("[QualityRetry] workflow={}, stage={}, attempt={} agent call failed: {}",
                        context.getWorkflowId(), stage.getCode(), attempt + 1, response.getError());
                if (bestResponse != null) {
                    break;
                }
                // 没有之前的结果，继续重试
                if (attempt < totalAttempts - 1) {
                    long backoff = calculateBackoff(attempt);
                    sleep(backoff);
                    continue;
                }
                return response;
            }

            // 提取文本并评估质量
            String content = extractTextFromResponse(response.getData());
            QualityScore score = qualityAssessmentService.assessQuality(stage, content);

            log.info("[QualityRetry] workflow={}, stage={}, attempt={}/{}, score={}, threshold={}, passed={}",
                    context.getWorkflowId(), stage.getCode(), attempt + 1, totalAttempts,
                    score.getTotalScore(), minScore, score.isAboveThreshold(minScore));

            // 更新最优结果
            if (score.getTotalScore() > bestScore) {
                bestScore = score.getTotalScore();
                bestResponse = response;
                bestRetryIndex = attempt;
            }

            // 质量达标，提前退出
            if (score.isAboveThreshold(minScore)) {
                log.info("[QualityRetry] workflow={}, stage={} quality passed (score={}), retries={}",
                        context.getWorkflowId(), stage.getCode(), score.getTotalScore(), attempt);
                break;
            }

            // 未达标且仍有重试机会，追加质量反馈并指数退避
            if (attempt < totalAttempts - 1) {
                currentRequest = addQualityFeedback(request, score);
                long backoff = calculateBackoff(attempt);
                log.debug("[QualityRetry] workflow={}, stage={} retrying after {}ms with {} suggestions",
                        context.getWorkflowId(), stage.getCode(), backoff, score.getSuggestions().size());
                sleep(backoff);
            }
        }

        if (bestRetryIndex > 0) {
            log.info("[QualityRetry] workflow={}, stage={} completed with {} retries, best score={}",
                    context.getWorkflowId(), stage.getCode(), bestRetryIndex, bestScore);
        }

        return bestResponse != null ? bestResponse : routeToAgent(stage, request);
    }

    /**
     * 将质量评估反馈追加到请求中，供 Agent 在重试时参考。
     *
     * @param original 原始请求
     * @param score    质量评分结果
     * @return 包含质量反馈的新请求
     */
    private AgentTaskRequest addQualityFeedback(AgentTaskRequest original, QualityScore score) {
        Map<String, Object> inputs = new HashMap<>(
                original.getInputs() != null ? original.getInputs() : new HashMap<>());
        inputs.put("qualityFeedback", score.getSuggestions());
        inputs.put("qualityScore", score.getTotalScore());
        inputs.put("qualityWeakestDimension", score.getWeakestDimension());

        return AgentTaskRequest.of(
                original.getWorkflowId(),
                original.getStageCode(),
                original.getAccountProfile(),
                inputs,
                original.getAccumulatedArtifacts()
        );
    }

    /**
     * 从 Agent 响应数据中提取文本内容用于质量评估。
     */
    private String extractTextFromResponse(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object value : data.values()) {
            if (value instanceof String s && !s.isBlank()) {
                sb.append(s).append("\n");
            } else if (value instanceof Map<?, ?> m) {
                extractTextFromMap(m, sb);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        sb.append(s).append("\n");
                    } else if (item instanceof Map<?, ?> m) {
                        extractTextFromMap(m, sb);
                    }
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void extractTextFromMap(Map<?, ?> map, StringBuilder sb) {
        for (Object value : map.values()) {
            if (value instanceof String s && !s.isBlank()) {
                sb.append(s).append("\n");
            } else if (value instanceof Map<?, ?> m) {
                extractTextFromMap(m, sb);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        sb.append(s).append("\n");
                    } else if (item instanceof Map<?, ?> m2) {
                        extractTextFromMap(m2, sb);
                    }
                }
            }
        }
    }

    /**
     * 计算第 attempt 次重试的指数退避等待时间（毫秒）。
     */
    private long calculateBackoff(int attempt) {
        double base = qualityProperties.getRetryBackoffMs();
        double multiplier = qualityProperties.getRetryBackoffMultiplier();
        return (long) (base * Math.pow(multiplier, attempt));
    }

    /**
     * 线程睡眠指定毫秒数。
     */
    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[QualityRetry] 退避等待被中断，继续重试");
        }
    }

    /**
     * Route the task to the appropriate agent (via AgentGateway — 支持 mock / microservice 模式切换).
     */
    private AgentResponse<Map<String, Object>> routeToAgent(AgentStage stage, AgentTaskRequest request) {
        return switch (stage) {
            case TOPIC_PLANNING -> agentGateway.callTopic(request);
            case CONTENT_CREATION -> agentGateway.callContentExecute(request);
            case IMAGE_DESIGN -> agentGateway.callImageExecute(request);
            case PUBLISHING -> agentGateway.callPublish(request);
            case DATA_ANALYSIS -> agentGateway.callAnalysis(request);
            case OPTIMIZATION -> agentGateway.callOptimize(request);
        };
    }

    /**
     * Route the task to the appropriate sub-stage (via AgentGateway).
     */
    AgentResponse<Map<String, Object>> routeToSubStage(SubStage subStage, AgentTaskRequest request) {
        return switch (subStage) {
            case CONTENT_OUTLINE -> agentGateway.callContentOutline(request);
            case CONTENT_DRAFT -> agentGateway.callContentDraft(request);
            case IMAGE_STYLES -> agentGateway.callImageStyles(request);
            case IMAGE_GENERATE -> agentGateway.callImageGenerate(request);
        };
    }

    /**
     * Handle successful stage execution (for stages without sub-stages).
     *
     * <p><b>A计划修复：</b>
     * <ul>
     *   <li>修复自动推进断裂：auto-advance 后立即调用 {@code executeStage(context)} 继续执行</li>
     *   <li>新增循环控制：OPTIMIZATION 完成时检查是否进入新一轮循环</li>
     * </ul>
     */
    private void handleStageSuccess(TaskContext context, AgentStage stage,
                                     AgentResponse<Map<String, Object>> response) {
        // Merge artifacts
        if (response.getData() != null) {
            context.setOutputs(response.getData());
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            context.getAccumulatedArtifacts().put(stage.getCode(), response.getData());

            // 长期记忆 P0：Agent 输出回流知识库 + 落盘审计（失败不阻断主流程）
            try {
                agentOutputIngester.ingest(stage, response.getData(), context);
            } catch (Exception e) {
                log.warn("[Workflow:{}] 输出回流知识库失败 stage={}: {}",
                        context.getWorkflowId(), stage.getCode(), e.getMessage());
            }
        }

        // P2 集成：质量评估 + 人工行动清单
        qualityEnricher.assessAndEnrich(context, stage, response.getData());

        // LLM-as-Judge 评测（Phase 2）：阶段产物异步判分并落库
        try {
            Object input = context.getInputs() == null ? null : context.getInputs().get("topicHint");
            if (input == null && context.getOutputs() != null) {
                input = context.getOutputs().get("topic");
            }
            String artifact = response.getData() == null
                    ? "" : objectMapper.writeValueAsString(response.getData());
            llmJudgeService.judgeAsync(stage.getCode(),
                    input == null ? "" : String.valueOf(input),
                    artifact, context.getWorkflowId());
        } catch (Exception e) {
            log.debug("[Workflow:{}] LLM 判分跳过: {}", context.getWorkflowId(), e.getMessage());
        }

        // 平台选择暂停：选题规划只跑一次，完成后先让用户选平台，再决定单/多平台产出
        if (isAwaitingPlatformSelection(context, stage)) {
            context.setStatus(TaskStatus.AWAITING_HUMAN.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
            log.info("[Workflow:{}] Topic planning completed. Awaiting platform selection.",
                    context.getWorkflowId());
            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(), stage.getCode(), response.getData()));
            return;
        }

        // ── 主流程收敛为 4 个 Agent 阶段：发布完成即工作流完成 ──
        // 数据分析和优化已拆分为独立服务，按需调用，不再自动推进到下一阶段。
        if (stage == AgentStage.PUBLISHING
                || stage == AgentStage.DATA_ANALYSIS
                || stage == AgentStage.OPTIMIZATION) {
            context.setCurrentStage(stage.getCode());
            context.setCurrentSubStage(null);
            context.setStatus(TaskStatus.COMPLETED.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            // 长期记忆 P2：工作流完成时沉淀项目记忆摘要（跨工作流复用，失败不阻断）
            if (projectMemoryService != null) {
                try {
                    projectMemoryService.summarizeWorkflow(context);
                } catch (Exception e) {
                    log.warn("[Workflow:{}] 项目记忆沉淀失败: {}",
                            context.getWorkflowId(), e.getMessage());
                }
            }

            log.info("[Workflow:{}] Stage {} completed. Workflow COMPLETED (4-stage pipeline).",
                    context.getWorkflowId(), stage.getCode());
            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(), "COMPLETED", response.getData()));
            return;
        }

        // ── A计划：循环边界检查（OPTIMIZATION → TOPIC_PLANNING） ──
        AgentStage nextStage = stage.next();
        if (cycleHandler.isCycleBoundary(stage, nextStage)) {
            cycleHandler.handleCycleBoundary(context, stage, response.getData());
            return;
        }

        // ── 正常阶段推进 ──
        if (context.isRequireHumanReview()) {
            context.setStatus(TaskStatus.AWAITING_HUMAN.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
            log.info("[Workflow:{}] Stage {} completed. Awaiting human approval.",
                    context.getWorkflowId(), stage.getCode());
            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(),
                    stage.getCode(), response.getData()));
        } else {
            // Auto-advance to next stage
            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
            log.info("[Workflow:{}] Stage {} → {} (auto-advance)",
                    context.getWorkflowId(), stage.getCode(), nextStage.getCode());
            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(),
                    nextStage.getCode(), response.getData()));

            // FIX: 修复自动推进断裂 —— 立即触发下一阶段执行
            executeStage(context);
        }
    }

    /**
     * Handle failed stage execution.
     */
    void handleStageFailure(TaskContext context, AgentStage stage, String error) {
        boolean budgetExceeded = error != null && error.contains(CostGuardBlockedException.BUDGET_MARKER);
        context.setStatus(budgetExceeded
                ? TaskStatus.BUDGET_EXCEEDED.name()
                : TaskStatus.FAILED.name());
        context.setErrorMessage(error);
        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        publishEvent(StageTransitionEvent.failed(
                context.getWorkflowId(), stage.getCode(), error
        ));

        log.error("[Workflow:{}] Stage {} FAILED: {}",
                context.getWorkflowId(), stage.getCode(), error);
    }

    /**
     * Build an AgentTaskRequest from the workflow context.
     *
     * <p>长期记忆 P1：构建请求前，按 stage 判断是否注入 RAG 历史上下文到 inputs。
     * 统一在编排层处理，无需改动各 Agent 的 Config 与接口签名。
     */
    AgentTaskRequest buildRequest(TaskContext context) {
        // RAG 上下文注入（按 contentops.rag.context-injection.* 开关控制）
        Map<String, Object> inputs = context.getInputs();
        if (inputs == null) {
            inputs = new java.util.HashMap<>();
        }
        injectRagContextIfNeeded(context, inputs);

        AgentTaskRequest request = AgentTaskRequest.of(
                context.getWorkflowId(),
                context.getCurrentStage(),
                context.getAccountProfile(),
                inputs,
                context.getAccumulatedArtifacts()
        );
        request.setRequireHumanReview(context.isRequireHumanReview());
        return request;
    }

    /**
     * 若该 stage 启用了 RAG 上下文注入，检索历史相似内容并塞入 inputs["ragContext"]。
     * 失败时只记日志，不影响请求构建。
     */
    private void injectRagContextIfNeeded(TaskContext context, Map<String, Object> inputs) {
        String stageCode = context.getCurrentStage();
        if (!ragRetrievalEnhancer.shouldInjectContext(stageCode)) {
            return;
        }
        try {
            String query = buildRagQuery(context, stageCode);
            String niche = context.getAccountProfile() != null
                    ? context.getAccountProfile().getNiche() : null;
            String ragContext = ragRetrievalEnhancer.retrieveHistoricalContext(query, niche, 0);
            if (ragContext != null && !ragContext.isBlank()) {
                inputs.put("ragContext", ragContext);
                log.debug("[Workflow:{}] RAG 上下文已注入 stage={}, chars={}",
                        context.getWorkflowId(), stageCode, ragContext.length());
            }
        } catch (Exception e) {
            log.warn("[Workflow:{}] RAG 上下文注入失败 stage={}: {}",
                    context.getWorkflowId(), stageCode, e.getMessage());
        }
    }

    /**
     * 根据 stage 与上下文构建 RAG 检索查询。
     */
    private String buildRagQuery(TaskContext context, String stageCode) {
        Object topic = null;
        if (context.getOutputs() != null) {
            topic = context.getOutputs().get("topic");
        }
        if (topic == null && context.getInputs() != null) {
            topic = context.getInputs().get("topic");
            if (topic == null) {
                topic = context.getInputs().get("topicHint");
            }
        }
        return topic != null ? String.valueOf(topic) : stageCode;
    }

    /**
     * 判断是否为“选题完成待选平台”暂停点：仅选题阶段且标记未清除时暂停。
     */
    private boolean isAwaitingPlatformSelection(TaskContext context, AgentStage stage) {
        return stage == AgentStage.TOPIC_PLANNING
                && context.getInputs() != null
                && Boolean.TRUE.equals(context.getInputs().get(
                        PlatformWorkflowOrchestrator.INPUT_PAUSE_FOR_PLATFORM_SELECTION))
                && !Boolean.TRUE.equals(context.getInputs().get(
                        PlatformWorkflowOrchestrator.INPUT_PLATFORM_SELECTION_DONE));
    }
    /**
     * Publish stage transition event to Kafka.
     */
    void publishEvent(StageTransitionEvent event) {
        // Outbox 先落库（可靠事件总线持久层，可审计/回放/迁移 Kafka）
        try {
            String payload = objectMapper.writeValueAsString(event);
            String agent = event.getToStage() != null ? event.getToStage() : event.getFromStage();
            agentEventRepository.insert(
                    java.util.UUID.randomUUID().toString(),
                    event.getWorkflowId(),
                    agent,
                    event.getEventType(),
                    payload,
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[Workflow] Outbox 写入失败: {}", e.getMessage());
        }
        // 本地 Spring 事件：驱动 SSE 广播等（单体模式核心通道）
        applicationEventPublisher.publishEvent(event);
        if (kafkaTemplate == null) {
            // 单体模式下无 Kafka，仅记录日志
            log.debug("[Monolithic] Skipping Kafka event publish: {}", event.getEventType());
            return;
        }
        try {
            // 使用反射调用，避免编译期依赖 spring-kafka
            kafkaTemplate.getClass()
                    .getMethod("send", String.class, Object.class, Object.class)
                    .invoke(kafkaTemplate, AgentConstants.TASK_EVENT_TOPIC, event.getWorkflowId(), event);
        } catch (Exception e) {
            log.warn("Failed to publish Kafka event: {}", e.getMessage());
        }
    }
}
