package com.contentops.orchestrator.graph;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.event.AsyncTaskEvent;
import com.contentops.orchestrator.kafka.AsyncTaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Kafka 异步桥接：将 Kafka 异步模式适配为 LangGraph4j 的同步等待模式。
 *
 * <p>原理：节点内部发送 Kafka 消息后，通过 {@link CompletableFuture} + 超时等待 + 结果回调实现伪同步。
 * 当 {@link com.contentops.orchestrator.kafka.AsyncTaskResultConsumer} 消费到结果后，
 * 调用 {@link #completeTask(String, AsyncTaskEvent.AsyncTaskResult)} 回调完成 Future。
 *
 * <p>超时时间默认 5 分钟，超时后抛出 {@link java.util.concurrent.TimeoutException}。
 */
@Slf4j
@Component
public class KafkaAsyncBridge {

    private final AsyncTaskProducer producer;
    private final ConcurrentHashMap<String, CompletableFuture<AsyncTaskEvent.AsyncTaskResult>> pendingTasks
            = new ConcurrentHashMap<>();

    public KafkaAsyncBridge(AsyncTaskProducer producer) {
        this.producer = producer;
    }

    /**
     * 注册结果回调。
     *
     * <p>由 {@link com.contentops.orchestrator.kafka.AsyncTaskResultConsumer} 在消费到结果后调用，
     * 完成 {@link CompletableFuture}，使等待中的节点继续执行。
     *
     * @param taskId 异步任务 ID
     * @param result 异步任务结果
     */
    public void completeTask(String taskId, AsyncTaskEvent.AsyncTaskResult result) {
        CompletableFuture<AsyncTaskEvent.AsyncTaskResult> future = pendingTasks.remove(taskId);
        if (future != null) {
            future.complete(result);
            log.info("[AsyncBridge] Task completed: taskId={}", taskId);
        } else {
            log.warn("[AsyncBridge] Unknown taskId (may have timed out): {}", taskId);
        }
    }

    /**
     * 发送异步任务到 Kafka 并等待结果。
     *
     * <p>超时时间默认 5 分钟，超时后抛出 {@link java.util.concurrent.TimeoutException}。
     *
     * @param workflowId    工作流 ID
     * @param stageCode     Agent 阶段代码
     * @param subStageCode  子阶段代码
     * @param request       Agent 任务请求
     * @return 异步任务结果
     */
    public CompletableFuture<AsyncTaskEvent.AsyncTaskResult> sendAndWait(
            String workflowId, String stageCode, String subStageCode,
            AgentTaskRequest request) {

        TaskContext contextStub = TaskContext.builder()
                .workflowId(workflowId)
                .accountProfile(request.getAccountProfile())
                .inputs(request.getInputs())
                .accumulatedArtifacts(request.getAccumulatedArtifacts())
                .build();

        String taskId = producer.sendAsyncTask(contextStub, stageCode, subStageCode, request);

        CompletableFuture<AsyncTaskEvent.AsyncTaskResult> future = new CompletableFuture<>();
        pendingTasks.put(taskId, future);

        log.info("[AsyncBridge] Sent async task: taskId={}, stage={}:{}",
                taskId, stageCode, subStageCode);

        return future.orTimeout(5, TimeUnit.MINUTES);
    }

    /**
     * 获取当前等待中的任务数量（用于监控）。
     */
    public int pendingCount() {
        return pendingTasks.size();
    }
}
