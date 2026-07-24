package com.contentops.orchestrator.workflow;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.SubStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.event.StageTransitionEvent;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.kafka.AsyncTaskProducer;
import com.contentops.orchestrator.service.AgentFeignClients.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The Pipeline Orchestrator — the heart of the multi-agent system.
 *
 * Implements a Sequential Pipeline pattern:
 *   Topic → Content → Image → Publish → Analysis → Optimize → (loop back to Topic)
 *
 * <p><b>P1 渐进式生成：</b>CONTENT_CREATION 和 IMAGE_DESIGN 两个阶段各自包含子阶段：
 * <ul>
 *   <li>CONTENT_CREATION: outline → (确认) → draft</li>
 *   <li>IMAGE_DESIGN: styles → (确认) → generate</li>
 * </ul>
 * 子阶段一完成后暂停等待人工确认，确认后再执行子阶段二。
 *
 * Each stage:
 *   1. Loads accumulated artifacts from Redis
 *   2. Calls the appropriate agent microservice via Feign
 *   3. Merges new artifacts back into Redis
 *   4. If human review required, pauses; otherwise advances to next stage
 *   5. Publishes a StageTransitionEvent to Kafka for observability
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final WorkflowStateManager stateManager;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AsyncTaskProducer asyncTaskProducer;

    // Feign clients for each agent
    private final TopicAgentClient topicAgentClient;
    private final ContentAgentClient contentAgentClient;
    private final ImageAgentClient imageAgentClient;
    private final PublishAgentClient publishAgentClient;
    private final AnalysisAgentClient analysisAgentClient;
    private final OptimizeAgentClient optimizeAgentClient;

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
     * Execute the current stage for a workflow.
     *
     * <p>If the stage has sub-stages, starts with the first sub-stage.
     * Otherwise, executes the stage in a single call.
     */
    public void executeStage(TaskContext context) {
        AgentStage stage = AgentStage.fromCode(context.getCurrentStage());
        log.info("[Workflow:{}] Executing stage: {} ({})",
                context.getWorkflowId(), stage.getCode(), stage.getNameCn());

        context.setStatus(TaskStatus.IN_PROGRESS.name());
        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        // Publish start event
        publishEvent(StageTransitionEvent.started(context.getWorkflowId(), stage.getCode()));

        try {
            // Check if this stage has sub-stages (progressive generation)
            if (SubStage.hasSubStages(stage)) {
                SubStage firstSub = SubStage.firstOf(stage);
                log.info("[Workflow:{}] Stage {} has sub-stages. Starting with: {} ({})",
                        context.getWorkflowId(), stage.getCode(), firstSub.getCode(), firstSub.getNameCn());
                context.setCurrentSubStage(firstSub.getCode());
                executeSubStage(context, firstSub);
            } else {
                // No sub-stages: execute in one shot
                AgentTaskRequest request = buildRequest(context);
                AgentResponse<Map<String, Object>> response = routeToAgent(stage, request);

                if (response.isSuccess()) {
                    handleStageSuccess(context, stage, response);
                } else {
                    handleStageFailure(context, stage, response.getError());
                }
            }
        } catch (Exception e) {
            log.error("[Workflow:{}] Stage {} failed with exception",
                    context.getWorkflowId(), stage.getCode(), e);
            handleStageFailure(context, stage, e.getMessage());
        }
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
    private void executeSubStage(TaskContext context, SubStage subStage) {
        log.info("[Workflow:{}] Executing sub-stage: {} ({})",
                context.getWorkflowId(), subStage.fullCode(), subStage.getNameCn());

        // P1: 长耗时子阶段走 Kafka 异步模式
        if (isAsyncSubStage(subStage)) {
            executeSubStageAsync(context, subStage);
            return;
        }

        try {
            AgentTaskRequest request = buildRequest(context);
            AgentResponse<Map<String, Object>> response = routeToSubStage(subStage, request);

            if (response.isSuccess()) {
                handleSubStageSuccess(context, subStage, response);
            } else {
                AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
                handleStageFailure(context, stage, response.getError());
            }
        } catch (Exception e) {
            log.error("[Workflow:{}] Sub-stage {} failed with exception",
                    context.getWorkflowId(), subStage.fullCode(), e);
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            handleStageFailure(context, stage, e.getMessage());
        }
    }

    /**
     * P1: 通过 Kafka 异步执行长耗时子阶段。
     *
     * <p>发送异步任务到 Kafka，设置工作流状态为 AWAITING_ASYNC，
     * 然后立即返回。结果由 {@link AsyncTaskResultConsumer} 处理。
     */
    private void executeSubStageAsync(TaskContext context, SubStage subStage) {
        try {
            AgentTaskRequest request = buildRequest(context);

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
            publishEvent(StageTransitionEvent.started(context.getWorkflowId(),
                    subStage.getParentStageCode()));
        } catch (Exception e) {
            log.error("[Workflow:{}] Async sub-stage {} failed with exception",
                    context.getWorkflowId(), subStage.fullCode(), e);
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            handleStageFailure(context, stage, e.getMessage());
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
            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    stage.getCode(),
                    nextStage.getCode(),
                    null
            ));
        }
    }

    /**
     * Route the task to the appropriate agent microservice (non-sub-stage stages).
     */
    private AgentResponse<Map<String, Object>> routeToAgent(AgentStage stage, AgentTaskRequest request) {
        return switch (stage) {
            case TOPIC_PLANNING -> topicAgentClient.execute(request);
            case CONTENT_CREATION -> contentAgentClient.execute(request);
            case IMAGE_DESIGN -> imageAgentClient.execute(request);
            case PUBLISHING -> publishAgentClient.execute(request);
            case DATA_ANALYSIS -> analysisAgentClient.execute(request);
            case OPTIMIZATION -> optimizeAgentClient.execute(request);
        };
    }

    /**
     * Route the task to the appropriate sub-stage endpoint.
     */
    private AgentResponse<Map<String, Object>> routeToSubStage(SubStage subStage, AgentTaskRequest request) {
        return switch (subStage) {
            case CONTENT_OUTLINE -> contentAgentClient.generateOutline(request);
            case CONTENT_DRAFT -> contentAgentClient.generateDraft(request);
            case IMAGE_STYLES -> imageAgentClient.generateStyleDirections(request);
            case IMAGE_GENERATE -> imageAgentClient.generateImages(request);
        };
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

            publishEvent(StageTransitionEvent.completed(
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

            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    stage.getCode(),
                    nextStage.getCode(),
                    response.getData()
            ));
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

    /**
     * Handle successful stage execution (for stages without sub-stages).
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
        }

        // Check for human review
        if (context.isRequireHumanReview()) {
            context.setStatus(TaskStatus.AWAITING_HUMAN.name());
            log.info("[Workflow:{}] Stage {} completed. Awaiting human approval.",
                    context.getWorkflowId(), stage.getCode());
        } else {
            // Auto-advance to next stage
            AgentStage nextStage = stage.next();
            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());
            log.info("[Workflow:{}] Stage {} → {} (auto-advance)",
                    context.getWorkflowId(), stage.getCode(), nextStage.getCode());
        }

        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        // Publish completion event
        publishEvent(StageTransitionEvent.completed(
                context.getWorkflowId(),
                stage.getCode(),
                context.getCurrentStage(),
                response.getData()
        ));
    }

    /**
     * Handle failed stage execution.
     */
    private void handleStageFailure(TaskContext context, AgentStage stage, String error) {
        context.setStatus(TaskStatus.FAILED.name());
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
     */
    private AgentTaskRequest buildRequest(TaskContext context) {
        AgentTaskRequest request = AgentTaskRequest.of(
                context.getWorkflowId(),
                context.getCurrentStage(),
                context.getAccountProfile(),
                context.getInputs(),
                context.getAccumulatedArtifacts()
        );
        request.setRequireHumanReview(context.isRequireHumanReview());
        return request;
    }

    /**
     * Publish stage transition event to Kafka.
     */
    private void publishEvent(StageTransitionEvent event) {
        try {
            kafkaTemplate.send(AgentConstants.TASK_EVENT_TOPIC, event.getWorkflowId(), event);
        } catch (Exception e) {
            log.warn("Failed to publish Kafka event: {}", e.getMessage());
        }
    }
}
