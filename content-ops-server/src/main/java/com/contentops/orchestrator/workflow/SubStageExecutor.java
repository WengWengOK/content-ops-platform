package com.contentops.orchestrator.workflow;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.SubStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.event.StageTransitionEvent;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.kafka.AsyncTaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 子阶段执行器 — 负责渐进式生成（progressive generation）的子阶段执行逻辑。
 *
 * <p>从 {@link PipelineOrchestrator} 拆分而来（P2-13），保持原有逻辑不变。
 *
 * <p><b>P1 渐进式生成：</b>CONTENT_CREATION 和 IMAGE_DESIGN 两个阶段各自包含子阶段：
 * <ul>
 *   <li>CONTENT_CREATION: outline → (确认) → draft</li>
 *   <li>IMAGE_DESIGN: styles → (确认) → generate</li>
 * </ul>
 * 子阶段一完成后暂停等待人工确认，确认后再执行子阶段二。
 *
 * <p><b>P1 Kafka 异步模式：</b>长耗时的子阶段（CONTENT_DRAFT、IMAGE_GENERATE）
 * 通过 Kafka 异步执行，避免 Feign HTTP 超时。
 */
@Slf4j
@Component
public class SubStageExecutor {

    private final WorkflowStateManager stateManager;
    private final StageExecutor stageExecutor;

    // 单体模式下可能不存在，设为可选
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AsyncTaskProducer asyncTaskProducer;

    public SubStageExecutor(WorkflowStateManager stateManager,
                            StageExecutor stageExecutor) {
        this.stateManager = stateManager;
        this.stageExecutor = stageExecutor;
    }

    /**
     * P1: 判断子阶段是否应通过 Kafka 异步执行。
     *
     * <p>长耗时的子阶段（内容初稿、批量生图）通过 Kafka 异步执行，
     * 避免 Feign HTTP 超时阻塞整个流水线。
     */
    private static boolean isAsyncSubStage(SubStage subStage) {
        return subStage == SubStage.CONTENT_DRAFT || subStage == SubStage.IMAGE_GENERATE;
    }

    /**
     * Execute a specific sub-stage (called by executeStage or confirmSubStage).
     *
     * <p><b>P1 Kafka 异步模式：</b>长耗时的子阶段（CONTENT_DRAFT、IMAGE_GENERATE）
     * 通过 Kafka 异步执行，避免 Feign HTTP 超时：
     * <ol>
     *   <li>发送 AsyncTaskRequest 到 Kafka</li>
     *   <li>设置工作流状态为 AWAITING_ASYNC</li>
     *   <li>立即返回（不阻塞等待）</li>
     *   <li>目标 Agent 消费消息后执行 LLM 调用，发送结果到结果 topic</li>
     *   <li>{@link com.contentops.orchestrator.kafka.AsyncTaskResultConsumer} 消费结果并推进工作流</li>
     * </ol>
     */
    void executeSubStage(TaskContext context, SubStage subStage) {
        log.info("[Workflow:{}] Executing sub-stage: {} ({})",
                context.getWorkflowId(), subStage.fullCode(), subStage.getNameCn());

        // P1: 长耗时子阶段走 Kafka 异步模式
        if (isAsyncSubStage(subStage)) {
            executeSubStageAsync(context, subStage);
            return;
        }

        try {
            AgentTaskRequest request = stageExecutor.buildRequest(context);
            AgentResponse<Map<String, Object>> response = stageExecutor.routeToSubStage(subStage, request);

            if (response.isSuccess()) {
                handleSubStageSuccess(context, subStage, response);
            } else {
                AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
                stageExecutor.handleStageFailure(context, stage, response.getError());
            }
        } catch (Exception e) {
            log.error("[Workflow:{}] Sub-stage {} failed with exception",
                    context.getWorkflowId(), subStage.fullCode(), e);
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            stageExecutor.handleStageFailure(context, stage, e.getMessage());
        }
    }

    /**
     * P1: 通过 Kafka 异步执行长耗时子阶段。
     *
     * <p>发送异步任务到 Kafka，设置工作流状态为 AWAITING_ASYNC，
     * 然后立即返回。结果由 {@link com.contentops.orchestrator.kafka.AsyncTaskResultConsumer} 处理。
     */
    private void executeSubStageAsync(TaskContext context, SubStage subStage) {
        // 单体模式下无 Kafka，回退到同步执行
        if (asyncTaskProducer == null) {
            log.info("[Workflow:{}] Kafka not available. Falling back to synchronous execution for sub-stage {}",
                    context.getWorkflowId(), subStage.fullCode());
            try {
                AgentTaskRequest request = stageExecutor.buildRequest(context);
                AgentResponse<Map<String, Object>> response = stageExecutor.routeToSubStage(subStage, request);
                if (response.isSuccess()) {
                    handleSubStageSuccess(context, subStage, response);
                } else {
                    AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
                    stageExecutor.handleStageFailure(context, stage, response.getError());
                }
            } catch (Exception e) {
                log.error("[Workflow:{}] Sync sub-stage {} failed with exception",
                        context.getWorkflowId(), subStage.fullCode(), e);
                AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
                stageExecutor.handleStageFailure(context, stage, e.getMessage());
            }
            return;
        }

        try {
            AgentTaskRequest request = stageExecutor.buildRequest(context);

            // 发送异步任务到 Kafka
            String taskId = asyncTaskProducer.sendAsyncTask(
                    context, subStage.getParentStageCode(), subStage.getCode(), request);

            // 设置工作流状态为 AWAITING_ASYNC
            context.setStatus(TaskStatus.AWAITING_ASYNC.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Sub-stage {} sent to Kafka async. taskId={}, status=AWAITING_ASYNC",
                    context.getWorkflowId(), subStage.fullCode(), taskId);

            // 发布阶段开始事件
            stageExecutor.publishEvent(StageTransitionEvent.started(context.getWorkflowId(),
                    subStage.getParentStageCode()));
        } catch (Exception e) {
            log.error("[Workflow:{}] Async sub-stage {} failed with exception",
                    context.getWorkflowId(), subStage.fullCode(), e);
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            stageExecutor.handleStageFailure(context, stage, e.getMessage());
        }
    }

    /**
     * Confirm a sub-stage and proceed to the next sub-stage or the next AgentStage.
     *
     * <p>Called by the WorkflowController when a user confirms the sub-stage output
     * (e.g., confirms an outline before draft generation).
     *
     * @param context   the workflow context
     * @param feedback  optional feedback/modifications from the user (e.g., edited outline)
     */
    public void confirmAndProceedSubStage(TaskContext context, Map<String, Object> feedback) {
        String currentSub = context.getCurrentSubStage();
        if (currentSub == null || currentSub.isBlank()) {
            log.warn("[Workflow:{}] No current sub-stage to confirm", context.getWorkflowId());
            return;
        }

        SubStage subStage = SubStage.fromCode(currentSub);
        log.info("[Workflow:{}] Confirming sub-stage: {} ({})",
                context.getWorkflowId(), subStage.fullCode(), subStage.getNameCn());

        // Merge feedback into accumulated artifacts (e.g., confirmedOutline, confirmedStyle)
        if (feedback != null && !feedback.isEmpty()) {
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            // Store feedback as inputs for the next sub-stage
            if (context.getInputs() == null) {
                context.setInputs(new java.util.HashMap<>());
            }
            context.getInputs().putAll(feedback);
        }

        // Check if there's a next sub-stage
        SubStage nextSub = subStage.next();
        if (nextSub != null) {
            // Proceed to next sub-stage
            log.info("[Workflow:{}] Sub-stage {} → {} (proceeding)",
                    context.getWorkflowId(), subStage.getCode(), nextSub.getCode());
            context.setCurrentSubStage(nextSub.getCode());
            context.setStatus(TaskStatus.IN_PROGRESS.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
            executeSubStage(context, nextSub);
        } else {
            // Last sub-stage completed → advance to next AgentStage
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            context.setCurrentSubStage(null);
            AgentStage nextStage = stage.next();
            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Stage {} → {} (all sub-stages done)",
                    context.getWorkflowId(), stage.getCode(), nextStage.getCode());
            stageExecutor.publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    stage.getCode(),
                    nextStage.getCode(),
                    null
            ));

            // FIX: 修复自动推进断裂 —— 立即触发下一阶段执行
            stageExecutor.executeStage(context);
        }
    }

    /**
     * Handle successful sub-stage execution.
     *
     * <p>If this is NOT the last sub-stage, pause for human confirmation.
     * If it IS the last sub-stage, advance to the next AgentStage.
     */
    private void handleSubStageSuccess(TaskContext context, SubStage subStage,
                                        AgentResponse<Map<String, Object>> response) {
        // Merge sub-stage artifacts into accumulated artifacts
        if (response.getData() != null) {
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            context.getAccumulatedArtifacts().put(subStage.fullCode(), response.getData());

            // Also store key outputs at a flat level for easy access by next sub-stage
            // (e.g., outline result → confirmedOutline, style result → confirmedStyle)
            storeSubStageOutput(context, subStage, response.getData());
        }

        SubStage nextSub = subStage.next();
        if (nextSub != null) {
            // Pause for human confirmation before next sub-stage
            context.setStatus(TaskStatus.AWAITING_HUMAN.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Sub-stage {} completed. Awaiting confirmation for {}.",
                    context.getWorkflowId(), subStage.getCode(), nextSub.getCode());

            stageExecutor.publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    subStage.getParentStageCode(),
                    subStage.getParentStageCode(),  // stays on same stage
                    response.getData()
            ));
        } else {
            // Last sub-stage → advance to next AgentStage
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            context.setCurrentSubStage(null);

            AgentStage nextStage = stage.next();
            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Sub-stage {} completed. Stage {} → {} (auto-advance)",
                    context.getWorkflowId(), subStage.getCode(), stage.getCode(), nextStage.getCode());

            stageExecutor.publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    stage.getCode(),
                    nextStage.getCode(),
                    response.getData()
            ));

            // FIX: 修复自动推进断裂 —— 立即触发下一阶段执行
            stageExecutor.executeStage(context);
        }
    }

    /**
     * Store sub-stage output at a flat level for easy access by the next sub-stage.
     *
     * <p>For example, the outline sub-stage's result is stored as "confirmedOutline"
     * so the draft sub-stage can pick it up via inputs.
     */
    @SuppressWarnings("unchecked")
    private void storeSubStageOutput(TaskContext context, SubStage subStage, Map<String, Object> data) {
        if (context.getInputs() == null) {
            context.setInputs(new java.util.HashMap<>());
        }
        switch (subStage) {
            case CONTENT_OUTLINE -> {
                // Store the outline result as confirmedOutline for the draft sub-stage
                Object outlineObj = data.get("outline");
                if (outlineObj != null) {
                    context.getInputs().put("confirmedOutline", outlineObj.toString());
                }
                // Also carry forward the topic
                Object topic = data.get("topic");
                if (topic != null) {
                    context.getInputs().putIfAbsent("topic", topic);
                }
            }
            case IMAGE_STYLES -> {
                // Store the style directions result as confirmedStyle for the generate sub-stage
                Object stylesObj = data.get("styleDirections");
                if (stylesObj != null) {
                    context.getInputs().put("confirmedStyle", stylesObj.toString());
                }
            }
            default -> {
                // For CONTENT_DRAFT and IMAGE_GENERATE, outputs are final stage outputs
                // and are already stored in accumulatedArtifacts
            }
        }
    }
}
