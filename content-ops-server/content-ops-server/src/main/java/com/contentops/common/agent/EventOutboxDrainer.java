package com.contentops.common.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 事件 Outbox 消费器：把已落库的 PENDING 事件标记 PUBLISHED。
 *
 * <p>当前单体模式下本地 Spring 事件已即时广播（SSE 依赖它），Outbox 承担
 * 持久化/审计/回放职责；迁移 Kafka 时，把此处的标记逻辑替换为发送到 topic。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventOutboxDrainer {

    private final AgentEventRepository repository;

    // 可选 Kafka：单体模式无 Kafka 时仅落库标记；迁移 Kafka 后自动发送
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("kafkaTemplate")
    private Object kafkaTemplate;

    @Scheduled(fixedDelayString = "${contentops.agents.outbox-drain-ms:15000}")
    public void drain() {
        try {
            // 只消费 2 秒前的事件，避免与写入竞态
            Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusSeconds(2));
            List<Map<String, Object>> pending = repository.findPendingRowsBefore(cutoff);
            if (pending.isEmpty()) {
                return;
            }
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            for (Map<String, Object> row : pending) {
                String eventId = String.valueOf(row.get("event_id"));
                String workflowId = String.valueOf(row.get("workflow_id"));
                Object payload = row.get("payload_json");
                sendToKafka(workflowId, payload);
                repository.markPublished(eventId, now);
            }
            log.info("[AgentEvent] Outbox 消费完成: {}", pending.size());
        } catch (Exception e) {
            log.warn("[AgentEvent] Outbox 消费失败: {}", e.getMessage());
        }
    }

    private void sendToKafka(String workflowId, Object payload) {
        if (kafkaTemplate == null || payload == null) {
            return;
        }
        try {
            kafkaTemplate.getClass()
                    .getMethod("send", String.class, Object.class, Object.class)
                    .invoke(kafkaTemplate,
                            com.contentops.common.constant.AgentConstants.TASK_EVENT_TOPIC,
                            workflowId, payload);
            log.debug("[AgentEvent] 已发送到 Kafka topic: workflowId={}", workflowId);
        } catch (Exception e) {
            log.warn("[AgentEvent] Kafka 发送失败（不影响标记 PUBLISHED）: {}", e.getMessage());
        }
    }
}
