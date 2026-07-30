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
import com.contentops.orchestrator.gateway.AgentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 单阶段执行器 — 负责单个 AgentStage 的执行逻辑（无子阶段的阶段）。
 *
 * <p>从 {@link PipelineOrchestrator} 拆分而来（P2-13），保持原有逻辑不变。
 * 职责：构建请求、路由到 Agent、处理成功/失败、发布阶段事件，并在阶段完成时
 * 委托 {@link QualityEnricher} 做质量评估、委托 {@link CycleHandler} 做循环边界检查。
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

    // 单体模式下 Kafka 可能不存在，设为可选（用 Object 避免依赖 spring-kafka）
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private Object kafkaTemplate;

    public StageExecutor(WorkflowStateManager stateManager,
                         AgentGateway agentGateway,
                         QualityEnricher qualityEnricher,
                         @Lazy SubStageExecutor subStageExecutor,
                         @Lazy CycleHandler cycleHandler) {
        this.stateManager = stateManager;
        this.agentGateway = agentGateway;
        this.qualityEnricher = qualityEnricher;
        this.subStageExecutor = subStageExecutor;
        this.cycleHandler = cycleHandler;
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
                subStageExecutor.executeSubStage(context, firstSub);
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
        }

        // P2 集成：质量评估 + 人工行动清单
        qualityEnricher.assessAndEnrich(context, stage, response.getData());

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
    AgentTaskRequest buildRequest(TaskContext context) {
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
    void publishEvent(StageTransitionEvent event) {
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
