package com.contentops.common.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * LLM 追踪存储（contentops_llm_trace，见 schema.sql）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LlmTraceRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO contentops_llm_trace "
                    + "(trace_id, workflow_id, stage, agent, model, tokens_in, tokens_out, "
                    + " prompt_chars, output_chars, latency_ms, status, error_message, created_at, "
                    + " otel_trace_id, otel_span_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_RECENT =
            "SELECT trace_id, workflow_id, stage, agent, model, tokens_in, tokens_out, "
                    + "prompt_chars, output_chars, latency_ms, status, error_message, created_at, "
                    + "otel_trace_id, otel_span_id "
                    + "FROM contentops_llm_trace "
                    + "WHERE (? IS NULL OR ? = '' OR stage = ?) "
                    + "  AND (? IS NULL OR ? = '' OR agent = ?) "
                    + "  AND (? IS NULL OR ? = '' OR workflow_id = ?) "
                    + "ORDER BY created_at DESC LIMIT ?";

    private static final String SQL_DELETE_OLD =
            "DELETE FROM contentops_llm_trace WHERE created_at < ?";

    private static final String SQL_STATS =
            "SELECT stage, agent, COUNT(*) AS calls, "
                    + "COALESCE(SUM(tokens_in), 0) AS tokens_in, "
                    + "COALESCE(SUM(tokens_out), 0) AS tokens_out, "
                    + "COALESCE(AVG(latency_ms), 0) AS avg_latency_ms, "
                    + "COALESCE(SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END), 0) AS errors "
                    + "FROM contentops_llm_trace "
                    + "WHERE created_at >= ? "
                    + "GROUP BY stage, agent ORDER BY calls DESC";

    private static final String SQL_TIMESERIES =
            "SELECT date_trunc('hour', created_at) AS bucket, COUNT(*) AS calls, "
                    + "COALESCE(SUM(tokens_in), 0) AS tokens_in, "
                    + "COALESCE(SUM(tokens_out), 0) AS tokens_out "
                    + "FROM contentops_llm_trace "
                    + "WHERE created_at >= ? "
                    + "GROUP BY 1 ORDER BY 1";
    private static final String SQL_MODEL_COST =
            "SELECT model, COALESCE(SUM(tokens_in), 0) AS tokens_in, "
                    + "COALESCE(SUM(tokens_out), 0) AS tokens_out "
                    + "FROM contentops_llm_trace "
                    + "WHERE created_at >= ? "
                    + "GROUP BY model";

    public void insert(LlmTrace trace) {
        try {
            jdbcTemplate.update(SQL_INSERT,
                    trace.getTraceId(),
                    trace.getWorkflowId(),
                    trace.getStage(),
                    trace.getAgent(),
                    trace.getModel(),
                    trace.getTokensIn(),
                    trace.getTokensOut(),
                    trace.getPromptChars(),
                    trace.getOutputChars(),
                    trace.getLatencyMs(),
                    trace.getStatus(),
                    trace.getErrorMessage(),
                    Timestamp.valueOf(trace.getCreatedAt()),
                    trace.getOtelTraceId(),
                    trace.getOtelSpanId());
        } catch (Exception e) {
            log.warn("[Observability] 保存 LLM trace 失败: err={}", e.getMessage());
        }
    }

    public List<LlmTrace> findRecent(String stage, String agent, String workflowId, int limit) {
        try {
            String s = stage == null ? "" : stage;
            String a = agent == null ? "" : agent;
            String w = workflowId == null ? "" : workflowId;
            return jdbcTemplate.query(SQL_RECENT, (rs, i) -> mapRow(rs),
                    s, s, s, a, a, a, w, w, w, limit);
        } catch (Exception e) {
            log.error("[Observability] 查询 LLM trace 失败", e);
            return List.of();
        }
    }

    public List<Map<String, Object>> stats(java.sql.Timestamp since) {
        try {
            return jdbcTemplate.queryForList(SQL_STATS, since);
        } catch (Exception e) {
            log.error("[Observability] 统计 LLM trace 失败", e);
            return List.of();
        }
    }

    public List<Map<String, Object>> timeseries(java.sql.Timestamp since) {
        try {
            return jdbcTemplate.queryForList(SQL_TIMESERIES, since);
        } catch (Exception e) {
            log.error("[Observability] 查询 LLM 时间序列失败", e);
            return List.of();
        }
    }

    public List<Map<String, Object>> modelCost(java.sql.Timestamp since) {
        try {
            return jdbcTemplate.queryForList(SQL_MODEL_COST, since);
        } catch (Exception e) {
            log.error("[Observability] 查询 LLM 模型成本失败", e);
            return List.of();
        }
    }

    public int deleteOlderThan(java.sql.Timestamp cutoff) {
        return jdbcTemplate.update(SQL_DELETE_OLD, cutoff);
    }

    private LlmTrace mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp created = rs.getTimestamp("created_at");
        return LlmTrace.builder()
                .traceId(rs.getString("trace_id"))
                .workflowId(rs.getString("workflow_id"))
                .stage(rs.getString("stage"))
                .agent(rs.getString("agent"))
                .model(rs.getString("model"))
                .tokensIn(rs.getLong("tokens_in") == 0 && rs.wasNull() ? null : rs.getLong("tokens_in"))
                .tokensOut(rs.getLong("tokens_out") == 0 && rs.wasNull() ? null : rs.getLong("tokens_out"))
                .promptChars(rs.getInt("prompt_chars") == 0 && rs.wasNull() ? null : rs.getInt("prompt_chars"))
                .outputChars(rs.getInt("output_chars") == 0 && rs.wasNull() ? null : rs.getInt("output_chars"))
                .latencyMs(rs.getLong("latency_ms") == 0 && rs.wasNull() ? null : rs.getLong("latency_ms"))
                .status(rs.getString("status"))
                .errorMessage(rs.getString("error_message"))
                .createdAt(created == null ? null : created.toLocalDateTime())
                .traceId(rs.getString("trace_id"))
                .otelTraceId(rs.getString("otel_trace_id"))
                .otelSpanId(rs.getString("otel_span_id"))
                .build();
    }
}
