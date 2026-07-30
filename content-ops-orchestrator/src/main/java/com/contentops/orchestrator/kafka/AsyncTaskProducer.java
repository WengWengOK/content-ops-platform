package com.contentops.orchestrator.kafka;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.event.AsyncTaskEvent;
import com.contentops.common.event.AgentTaskRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 异步任务生产者（P1: 弹性与可观测性 — Kafka 异步模式）。
 *
 * <p>当编排器需要调用长耗时的 Agent 子阶段（内容初稿、批量生图）时，
 * 通过此组件发送 Kafka 消息而非同步 Feign HTTP 调用，避免 Feign 超时阻塞。
 *
 * <p>流程：
 * <ol>
 *   <li>编排器调用 {@link #sendAsyncTask} 发送请求到 {@code content-ops.async.tasks}</li>
 *   <li>目标 Agent 消费消息，执行 LLM 调用</li>
 *   <li>Agent 完成后发送结果到 {@code content-ops.async.results}</li>
 *   <li>编排器的 {@link AsyncTaskResultConsumer} 消费结果并推进工作流</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 发送异步任务请求到 Kafka。
     *
     * @param context   工作流上下文
     * @param agentStage Agent 阶段代码（如 "content-creation"）
     * @param subStage  子阶段代码（如 "draft"、"generate"）
     * @param request   原始 AgentTaskRequest（包含 inputs、accumulatedArtifacts 等）
     * @return 任务 ID，用于关联请求和结果
     */
    public String sendAsyncTask(TaskContext context, String agentStage,
                                String subStage, AgentTaskRequest request) {
        String taskId = UUID.randomUUID().toString();

        AsyncTaskEvent.AsyncTaskRequest asyncRequest = AsyncTaskEvent.AsyncTaskRequest.builder()
                .taskId(taskId)
                .workflowId(context.getWorkflowId())
                .agentStage(agentStage)
                .subStage(subStage)
                .accountProfile(context.getAccountProfile())
                .inputs(request.getInputs())
                .accumulatedArtifacts(request.getAccumulatedArtifacts())
                .timestamp(LocalDateTime.now())
                .build();

        log.info("[AsyncTask] 发送异步任务: taskId={}, workflowId={}, stage={}, subStage={}",
                taskId, context.getWorkflowId(), agentStage, subStage);

        try {
            kafkaTemplate.send(AgentConstants.ASYNC_TASK_REQUEST_TOPIC,
                    context.getWorkflowId(), asyncRequest);
            log.info("[AsyncTask] 异步任务已发送到 Kafka topic={}",
                    AgentConstants.ASYNC_TASK_REQUEST_TOPIC);
        } catch (Exception e) {
            log.error("[AsyncTask] 发送异步任务失败: taskId={}, workflowId={}",
                    taskId, context.getWorkflowId(), e);
            throw new RuntimeException("Failed to send async task to Kafka", e);
        }

        return taskId;
    }
}
