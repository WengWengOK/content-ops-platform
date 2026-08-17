package com.contentops.orchestrator.service;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.platform.ContentPlatform;
import com.contentops.common.platform.PlatformSpecRegistry;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.workflow.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 多平台并行编排器（P 平台化工作流改造）。
 *
 * <p>选题规划只跑一次并暂停等待用户选择平台；确认多个平台后，从内容创作阶段扇出为 N 条平台分支并行执行（共享父级选题产物）：
 * <pre>
 *   父工作流（聚合视图）
 *     ├── :xiaohongshu  Content→Image→Publish→Analysis→Optimize（共享选题）
 *     ├── :wechat       Content→Image→Publish→Analysis→Optimize（共享选题）
 *     └── :douyin       ...
 * </pre>
 *
 * <p>每条分支都是独立的 {@link TaskContext}（workflowId = 父ID + ":" + 平台code），
 * 复用现有 {@link PipelineOrchestrator} 全流程；父工作流只负责分支元数据与状态聚合。
 * 平台差异通过 {@link PlatformSpecRegistry} 生成的 platformGuidance 注入各分支 inputs，
 * 从而驱动内容 Agent 产出不同风格。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformWorkflowOrchestrator {

    /** 分支 ID 分隔符，例如 {parentId}:xiaohongshu */
    public static final String BRANCH_SEPARATOR = ":";
    /** inputs 中分支元数据 key */
    public static final String INPUT_BRANCHES = "branches";
    /** inputs 中标记分支归属父工作流的 key */
    public static final String INPUT_BRANCH_OF = "branchOf";
    /** inputs 中平台 code key */
    public static final String INPUT_PLATFORM = "platform";
    /** inputs 中平台中文名 key */
    public static final String INPUT_PLATFORM_NAME = "platformName";
    /** inputs 中平台适配指令 key */
    public static final String INPUT_PLATFORM_GUIDANCE = "platformGuidance";
    /** inputs 中已解析的平台 code 列表 key */
    public static final String INPUT_PLATFORMS = "platforms";
    /** inputs 中平台账号映射 key */
    public static final String INPUT_PLATFORM_ACCOUNTS = "platformAccounts";
    /** inputs 中"选题完成后暂停等待用户选择平台"标记 */
    public static final String INPUT_PAUSE_FOR_PLATFORM_SELECTION = "pauseForPlatformSelection";
    /** inputs 中"平台选择已完成"标记 */
    public static final String INPUT_PLATFORM_SELECTION_DONE = "platformSelectionDone";

    private final WorkflowStateManager stateManager;
    private final PipelineOrchestrator orchestrator;
    private final PlatformSpecRegistry platformRegistry;

    /**
     * 是否为多平台工作流（存在分支元数据）。
     */
    public boolean hasBranches(TaskContext context) {
        if (context == null || context.getInputs() == null) {
            return false;
        }
        Object branches = context.getInputs().get(INPUT_BRANCHES);
        return branches instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 读取分支元数据列表。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> branchMeta(TaskContext context) {
        if (!hasBranches(context)) {
            return List.of();
        }
        return (List<Map<String, Object>>) context.getInputs().get(INPUT_BRANCHES);
    }

    /**
     * 扇出并行执行：为每个平台创建独立分支工作流并在共享线程池中并行执行。
     *
     * <p>分支任务返回不代表分支完成（人机协同时会暂停在 AWAITING_HUMAN），
     * 父工作流状态由 {@link #aggregateParent} 在查询时实时聚合。
     *
     * @param parent    父工作流（含已解析的 inputs.platforms）
     * @param platforms 平台列表（长度 ≥ 2 时扇出）
     * @param executor  并行执行线程池
     */
    @SuppressWarnings("unchecked")
    public void startWithPlatformBranches(TaskContext parent,
                                          List<ContentPlatform> platforms,
                                          ExecutorService executor) {
        if (platforms == null || platforms.size() < 2) {
            throw new IllegalArgumentException("Multi-platform fan-out requires at least 2 platforms");
        }

        List<Map<String, Object>> branchMeta = new ArrayList<>();
        for (ContentPlatform platform : platforms) {
            Map<String, Object> accountInfo = resolveAccountInfo(parent, platform);
            TaskContext branch = buildBranchContext(parent, platform, accountInfo);
            stateManager.saveWorkflowState(branch.getWorkflowId(), branch);

            Map<String, Object> meta = new HashMap<>();
            meta.put("platform", platform.getCode());
            meta.put("platformName", platform.getDisplayName());
            meta.put("workflowId", branch.getWorkflowId());
            meta.put("status", branch.getStatus());
            meta.put("accountName", accountInfo.get("accountName"));
            branchMeta.add(meta);

            executor.submit(() -> runBranch(branch));
            log.info("[MultiPlatform] branch started: parent={}, branch={}",
                    parent.getWorkflowId(), branch.getWorkflowId());
        }

        parent.getInputs().put(INPUT_BRANCHES, branchMeta);
        parent.setStatus(TaskStatus.IN_PROGRESS.name());
        parent.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(parent.getWorkflowId(), parent);
    }

    /**
     * 执行单条平台分支流水线（含异常兜底，防止单分支失败影响其他分支）。
     */
    private void runBranch(TaskContext branch) {
        try {
            orchestrator.executeStage(branch);
            stateManager.saveWorkflowState(branch.getWorkflowId(), branch);
        } catch (Throwable e) {
            log.error("[MultiPlatform] branch failed: {} error: {}",
                    branch.getWorkflowId(), e.getMessage(), e);
            branch.setStatus(TaskStatus.FAILED.name());
            branch.setErrorMessage(e.getMessage());
            branch.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(branch.getWorkflowId(), branch);
        }
    }

    /**
     * 构建单平台分支上下文。
     *
     * <ul>
     *   <li>workflowId = 父ID + ":" + 平台code，天然可追溯</li>
     *   <li>inputs 注入 platform / platformName / platformGuidance / targetPlatforms / branchOf</li>
     *   <li>accountProfile 替换为该平台的账号（未提供时沿用父级账号）</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public TaskContext buildBranchContext(TaskContext parent,
                                          ContentPlatform platform,
                                          Map<String, Object> accountInfo) {
        String branchId = parent.getWorkflowId() + BRANCH_SEPARATOR + platform.getCode();

        Map<String, Object> inputs = new HashMap<>();
        if (parent.getInputs() != null) {
            inputs.putAll(parent.getInputs());
        }
        inputs.put(INPUT_PLATFORM, platform.getCode());
        inputs.put(INPUT_PLATFORM_NAME, platform.getDisplayName());
        inputs.put(INPUT_PLATFORM_GUIDANCE, platformRegistry.guidance(platform));
        // 图片/发布 Agent 读取 targetPlatforms 或 profile.platforms
        inputs.put("targetPlatforms", List.of(platform.getDisplayName()));
        inputs.put(INPUT_BRANCH_OF, parent.getWorkflowId());
        // 选题已在父工作流跑过一次并共享，分支直接从内容创作起步，不再重复选题
        inputs.remove(INPUT_PAUSE_FOR_PLATFORM_SELECTION);
        inputs.put(INPUT_PLATFORM_SELECTION_DONE, true);

        TaskContext.AccountProfile parentProfile = parent.getAccountProfile();
        TaskContext.AccountProfile branchProfile = parentProfile;
        if (parentProfile != null) {
            branchProfile = TaskContext.AccountProfile.builder()
                    .accountId(str(accountInfo.get("accountId"), parentProfile.getAccountId()))
                    .accountName(str(accountInfo.get("accountName"), parentProfile.getAccountName()))
                    .niche(parentProfile.getNiche())
                    .targetAudience(parentProfile.getTargetAudience())
                    .tone(parentProfile.getTone())
                    .platforms(new ArrayList<>(List.of(platform.getDisplayName())))
                    .personalExperience(parentProfile.getPersonalExperience())
                    .build();
        }

        Map<String, Object> artifacts = new HashMap<>();
        if (parent.getAccumulatedArtifacts() != null) {
            artifacts.putAll(parent.getAccumulatedArtifacts());
        }

        return TaskContext.builder()
                .workflowId(branchId)
                .ownerId(parent.getOwnerId())
                .currentStage(AgentStage.CONTENT_CREATION.getCode())
                .currentSubStage(parent.getCurrentSubStage())
                .accountProfile(branchProfile)
                .inputs(inputs)
                .outputs(new HashMap<>())
                .accumulatedArtifacts(artifacts)
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .requireHumanReview(parent.isRequireHumanReview())
                .cycleCount(Math.max(1, parent.getCycleCount()))
                .maxCycles(parent.getMaxCycles())
                .build();
    }

    /**
     * 聚合父工作流视图：实时读取各分支状态，计算父级整体状态并合并产物。
     *
     * <p>注意：返回的是新对象，不会覆盖数据库中的父记录；分支状态变化后再次查询即自动更新。
     */
    public TaskContext aggregateParent(TaskContext parent) {
        if (!hasBranches(parent)) {
            return parent;
        }

        TaskContext view = copyForView(parent);
        List<Map<String, Object>> meta = branchMeta(parent);
        Map<String, Object> mergedArtifacts = new HashMap<>();
        Map<String, Object> mergedOutputs = new HashMap<>();
        if (parent.getAccumulatedArtifacts() != null) {
            mergedArtifacts.putAll(parent.getAccumulatedArtifacts());
        }
        if (parent.getOutputs() != null) {
            mergedOutputs.putAll(parent.getOutputs());
        }

        boolean anyFailed = false;
        boolean anyWaiting = false;
        boolean anyAsync = false;
        boolean anyRunning = false;
        String firstError = null;

        for (Map<String, Object> m : meta) {
            String branchId = String.valueOf(m.get("workflowId"));
            String platformCode = String.valueOf(m.get("platform"));
            java.util.Optional<TaskContext> branchOpt = stateManager.loadWorkflowState(branchId);
            if (branchOpt.isEmpty()) {
                anyRunning = true;
                m.put("status", TaskStatus.PENDING.name());
                continue;
            }
            TaskContext branch = branchOpt.get();
            String status = branch.getStatus();
            m.put("status", status);
            m.put("currentStage", branch.getCurrentStage());

            if (TaskStatus.COMPLETED.name().equals(status)) {
                if (branch.getAccumulatedArtifacts() != null) {
                    mergedArtifacts.put("platform:" + platformCode, branch.getAccumulatedArtifacts());
                }
                if (branch.getOutputs() != null) {
                    mergedOutputs.put("platform:" + platformCode, branch.getOutputs());
                }
            } else if (TaskStatus.FAILED.name().equals(status)) {
                anyFailed = true;
                if (firstError == null) {
                    firstError = branch.getErrorMessage();
                }
            } else if (TaskStatus.AWAITING_HUMAN.name().equals(status)) {
                anyWaiting = true;
                view.setCurrentStage(branch.getCurrentStage());
                view.setCurrentSubStage(branch.getCurrentSubStage());
            } else if (TaskStatus.AWAITING_ASYNC.name().equals(status)) {
                anyAsync = true;
            } else {
                anyRunning = true;
                view.setCurrentStage(branch.getCurrentStage());
            }
        }

        view.getInputs().put(INPUT_BRANCHES, meta);
        view.setAccumulatedArtifacts(mergedArtifacts);
        view.setOutputs(mergedOutputs);

        if (anyFailed) {
            view.setStatus(TaskStatus.FAILED.name());
            view.setErrorMessage(firstError);
        } else if (meta.stream().allMatch(m -> TaskStatus.COMPLETED.name().equals(m.get("status")))) {
            view.setStatus(TaskStatus.COMPLETED.name());
            // 主流程收敛为 4 阶段：全部平台分支发布完成即工作流完成
            view.setCurrentStage(AgentStage.PUBLISHING.getCode());
            view.setCurrentSubStage(null);
        } else if (anyWaiting) {
            view.setStatus(TaskStatus.AWAITING_HUMAN.name());
        } else if (anyAsync) {
            view.setStatus(TaskStatus.AWAITING_ASYNC.name());
        } else {
            view.setStatus(TaskStatus.IN_PROGRESS.name());
        }
        view.setUpdatedAt(LocalDateTime.now());
        return view;
    }

    /**
     * 判断是否为分支工作流（用于列表过滤，避免分支刷屏）。
     */
    public boolean isBranch(TaskContext context) {
        return context != null
                && context.getInputs() != null
                && context.getInputs().containsKey(INPUT_BRANCH_OF);
    }

    /**
     * 从 inputs.platformAccounts 中解析某平台的账号信息，支持 code / 中文名 / 短码作为 key。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveAccountInfo(TaskContext parent, ContentPlatform platform) {
        Map<String, Object> result = new HashMap<>();
        if (parent.getInputs() == null) {
            return result;
        }
        Object raw = parent.getInputs().get(INPUT_PLATFORM_ACCOUNTS);
        if (!(raw instanceof Map<?, ?> accounts)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) accounts).entrySet()) {
            String key = String.valueOf(entry.getKey());
            ContentPlatform matched = ContentPlatform.from(key);
            if (platform == matched) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> accountMap) {
                    result.put("accountId", accountMap.get("accountId"));
                    result.put("accountName", accountMap.get("accountName"));
                } else if (value != null) {
                    result.put("accountName", String.valueOf(value));
                }
                break;
            }
        }
        return result;
    }

    /**
     * 复制父上下文用于聚合视图，避免污染持久化数据。
     */
    private TaskContext copyForView(TaskContext parent) {
        return TaskContext.builder()
                .workflowId(parent.getWorkflowId())
                .ownerId(parent.getOwnerId())
                .currentStage(parent.getCurrentStage())
                .currentSubStage(parent.getCurrentSubStage())
                .accountProfile(parent.getAccountProfile())
                .inputs(parent.getInputs() == null ? new HashMap<>() : new HashMap<>(parent.getInputs()))
                .outputs(new HashMap<>())
                .accumulatedArtifacts(new HashMap<>())
                .status(parent.getStatus())
                .errorMessage(parent.getErrorMessage())
                .createdAt(parent.getCreatedAt())
                .updatedAt(parent.getUpdatedAt())
                .requireHumanReview(parent.isRequireHumanReview())
                .cycleCount(parent.getCycleCount())
                .maxCycles(parent.getMaxCycles())
                .lastOptimizationFeedback(parent.getLastOptimizationFeedback())
                .build();
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
