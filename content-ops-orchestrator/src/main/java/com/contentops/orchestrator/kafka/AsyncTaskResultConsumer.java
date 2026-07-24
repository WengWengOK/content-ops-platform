package com.contentops.orchestrator.kafka;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.SubStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.event.AsyncTaskEvent;
import com.contentops.common.event.StageTransitionEvent;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.workflow.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 异步任务结果消费者（P1: 弹性与可观测性 — Kafka 异步模式）。
 *
 * <p>监听 {@code content-ops.async.results} topic，当长耗时 Agent（内容初稿、批量生图）
 * 完成异步执行后，消费其结果并推进工作流到下一个子阶段或 AgentStage。
 *
 * <p>结果处理逻辑：
 * <ol>
 *   <li>从 Redis 加载工作流状态</li>
 *   <li>校验 workflowId 匹配</li>
 *   <li>如果成功：合并产物 → 推进到下一子阶段/下一 AgentStage</li>
 *   <li>如果失败：标记工作流失败</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskResultConsumer {

    private final WorkflowStateManager stateManager;
    private final PipelineOrchestrator pipelineOrchestrator;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 消费异步任务结果。
     *
     * <p>消费者组配置为 {@code orchestrator-async-result-group}，
     * 确保编排器集群中只有一个实例消费每条消息。
     */
    @KafkaListener(
            topics = AgentConstants.ASYNC_TASK_RESULT_TOPIC,
            groupId = "orchestrator-async-result-group"
    )
    public void handleAsyncTaskResult(AsyncTaskEvent.AsyncTaskResult result) {
        log.info("[AsyncResult] 收到异步任务结果: taskId={}, workflowId={}, stage={}:{}, success={}",
                result.getTaskId(), result.getWorkflowId(),
                result.getAgentStage(), result.getSubStage(), result.isSuccess());

        try {
            processResult(result);
        } catch (Exception e) {
            log.error("[AsyncResult] 处理异步结果失败: taskId={}, workflowId={}",
                    result.getTaskId(), result.getWorkflowId(), e);
        }
    }

    /**
     * 处理异步任务结果：合并产物并推进工作流。
     */
    private void processResult(AsyncTaskEvent.AsyncTaskResult result) {
        String workflowId = result.getWorkflowId();

        TaskContext context = stateManager.loadWorkflowState(workflowId)
                .orElse(null);
        if (context == null) {
            log.error("[AsyncResult] 工作流不存在: workflowId={}", workflowId);
            return;
        }

        // 校验状态
        if (!TaskStatus.AWAITING_ASYNC.name().equals(context.getStatus())) {
            log.warn("[AsyncResult] 工作流状态不是 AWAITING_ASYNC: workflowId={}, status={}",
                    workflowId, context.getStatus());
            return;
        }

        SubStage subStage = SubStage.fromCode(result.getSubStage());

        if (result.isSuccess()) {
            handleAsyncSuccess(context, subStage, result);
        } else {
            handleAsyncFailure(context, subStage, result.getError());
        }
    }

    /**
     * 异步任务成功：合并产物并推进到下一子阶段或下一 AgentStage。
     *
     * <p><b>A计划修复：</b>修复异步推进断裂 —— 最后一个子阶段完成后，
     * 立即调用 {@code pipelineOrchestrator.executeStage(context)} 继续执行下一阶段。
     */
    private void handleAsyncSuccess(TaskContext context, SubStage subStage,
                                    AsyncTaskEvent.AsyncTaskResult result) {
        // 合并产物
        if (result.getData() != null) {
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            context.getAccumulatedArtifacts().put(subStage.fullCode(), result.getData());

            // 同步存储关键输出（与同步模式的 storeSubStageOutput 一致）
            storeSubStageOutput(context, subStage, result.getData());
        }

        // 检查是否有下一个子阶段
        SubStage nextSub = subStage.next();
        if (nextSub != null) {
            // 有下一个子阶段 → 暂停等待人工确认
            context.setCurrentSubStage(nextSub.getCode());
            context.setStatus(TaskStatus.AWAITING_HUMAN.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[AsyncResult] 子阶段 {} 完成，等待确认: workflowId={}",
                    subStage.getCode(), context.getWorkflowId());

            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    subStage.getParentStageCode(),
                    subStage.getParentStageCode(),
                    result.getData()
            ));
        } else {
            // 最后一个子阶段 → 推进到下一 AgentStage
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            context.setCurrentSubStage(null);
            AgentStage nextStage = stage.next();
            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[AsyncResult] 子阶段 {} 完成，阶段 {} → {}: workflowId={}",
                    subStage.getCode(), stage.getCode(), nextStage.getCode(),
                    context.getWorkflowId());

            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    stage.getCode(),
                    nextStage.getCode(),
                    result.getData()
            ));

            // FIX: 修复异步推进断裂 —— 立即触发下一阶段执行
            pipelineOrchestrator.executeStage(context);
        }
    }

    /**
     * 异步任务失败：标记工作流失败。
     */
    private void handleAsyncFailure(TaskContext context, SubStage subStage, String error) {
        AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
        context.setStatus(TaskStatus.FAILED.name());
        context.setErrorMessage("异步任务失败 [" + subStage.fullCode() + "]: " + error);
        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        publishEvent(StageTransitionEvent.failed(
                context.getWorkflowId(), stage.getCode(),
                "异步任务失败: " + error
        ));

        log.error("[AsyncResult] 异步任务失败: workflowId={}, subStage={}, error={}",
                context.getWorkflowId(), subStage.fullCode(), error);
    }

    /**
     * 存储子阶段输出到 inputs 中（与 PipelineOrchestrator.storeSubStageOutput 逻辑一致）。
     */
    @SuppressWarnings("unchecked")
    private void storeSubStageOutput(TaskContext context, SubStage subStage, Map<String, Object> data) {
        if (context.getInputs() == null) {
            context.setInputs(new java.util.HashMap<>());
        }
        switch (subStage) {
            case CONTENT_OUTLINE -> {
                Object outlineObj = data.get("outline");
                if (outlineObj != null) {
                    context.getInputs().put("confirmedOutline", outlineObj.toString());
                }
                Object topic = data.get("topic");
                if (topic != null) {
                    context.getInputs().putIfAbsent("topic", topic);
                }
            }
            case IMAGE_STYLES -> {
                Object stylesObj = data.get("styleDirections");
                if (stylesObj != null) {
                    context.getInputs().put("confirmedStyle", stylesObj.toString());
                }
            }
            default -> { /* CONTENT_DRAFT and IMAGE_GENERATE outputs are in accumulatedArtifacts */ }
        }
    }

    /**
     * 发布阶段事件到 Kafka（用于可观测性）。
     */
    private void publishEvent(StageTransitionEvent event) {
        try {
            kafkaTemplate.send(AgentConstants.TASK_EVENT_TOPIC, event.getWorkflowId(), event);
        } catch (Exception e) {
            log.warn("Failed to publish Kafka event: {}", e.getMessage());
        }
    }
}
