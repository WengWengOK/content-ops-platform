package com.contentops.orchestrator.kafka;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.event.AgentTaskRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 单体模式下的异步任务生产者占位符。
 *
 * <p>微服务模式下此组件通过 Kafka 发送异步任务；单体模式下
 * PipelineOrchestrator 检测到 asyncTaskProducer 为 null 时会回退到同步执行。
 * 此类仅在显式启用时注册（防止单体模式下被扫描），实际不会被使用。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "contentops.mode", havingValue = "microservice")
public class AsyncTaskProducer {

    public String sendAsyncTask(TaskContext context, String stageCode, String subStageCode, AgentTaskRequest request) {
        throw new UnsupportedOperationException("AsyncTaskProducer should not be used in monolithic mode");
    }
}
