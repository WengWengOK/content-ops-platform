package com.contentops.common.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Agent 事件 Outbox 存储（contentops_agent_event，见 schema.sql）。
 * 阶段/Agent 事件先落库（PENDING），drainer 消费后标记 PUBLISHED，
 * 保证事件不丢失、可审计、可回放，后续可平滑迁移到 Kafka。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AgentEventRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO contentops_agent_event "
                    + "(event_id, workflow_id, agent, event_type, payload_json, status, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, 'PENDING', ?)";
    private static final String SQL_RECENT =
            "SELECT event_id, workflow_id, agent, event_type, payload_json, status, created_at, published_at "
                    + "FROM contentops_agent_event "
                    + "WHERE (? IS NULL OR ? = '' OR workflow_id = ?) "
                    + "  AND (? IS NULL OR ? = '' OR agent = ?) "
                    + "ORDER BY created_at DESC LIMIT ?";
    private static final String SQL_PENDING =
            "SELECT event_id FROM contentops_agent_event "
                    + "WHERE status = 'PENDING' AND created_at < ? LIMIT 100";
    private static final String SQL_PENDING_ROWS =
            "SELECT event_id, workflow_id, payload_json FROM contentops_agent_event "
                    + "WHERE status = 'PENDING' AND created_at < ? LIMIT 100";
    private static final String SQL_MARK_PUBLISHED =
            "UPDATE contentops_agent_event SET status = 'PUBLISHED', published_at = ? WHERE event_id = ?";

    public void insert(String eventId, String workflowId, String agent,
                       String eventType, String payloadJson, Timestamp createdAt) {
        try {
            jdbcTemplate.update(SQL_INSERT, eventId, workflowId, agent, eventType, payloadJson, createdAt);
        } catch (Exception e) {
            log.warn("[AgentEvent] outbox 写入失败: eventType={}, err={}", eventType, e.getMessage());
        }
    }

    public List<Map<String, Object>> findRecent(String workflowId, String agent, int limit) {
        try {
            String w = workflowId == null ? "" : workflowId;
            String a = agent == null ? "" : agent;
            return jdbcTemplate.queryForList(SQL_RECENT, w, w, w, a, a, a, limit);
        } catch (Exception e) {
            log.error("[AgentEvent] 查询失败", e);
            return List.of();
        }
    }

    public List<String> findPendingBefore(Timestamp cutoff) {
        try {
            return jdbcTemplate.queryForList(SQL_PENDING, String.class, cutoff);
        } catch (Exception e) {
            log.error("[AgentEvent] 查询 PENDING 失败", e);
            return List.of();
        }
    }

    /** PENDING 事件完整行（含 payload），供 drainer 发送到消息总线 */
    public List<Map<String, Object>> findPendingRowsBefore(Timestamp cutoff) {
        try {
            return jdbcTemplate.queryForList(SQL_PENDING_ROWS, cutoff);
        } catch (Exception e) {
            log.error("[AgentEvent] 查询 PENDING 行失败", e);
            return List.of();
        }
    }

    public void markPublished(String eventId, Timestamp publishedAt) {
        try {
            jdbcTemplate.update(SQL_MARK_PUBLISHED, publishedAt, eventId);
        } catch (Exception e) {
            log.warn("[AgentEvent] 标记 PUBLISHED 失败: id={}", eventId);
        }
    }
}
