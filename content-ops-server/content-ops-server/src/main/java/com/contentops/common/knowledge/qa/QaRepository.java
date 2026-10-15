package com.contentops.common.knowledge.qa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库问答仓储：落库问答记录 + 质量信号，并按风险/复核状态检索（人工复核清单 & 高风险检索日志）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class QaRepository {

    private static final String COLS = "qa_id, owner_id, question, answer, refused, source, citations, "
            + "citation_check, retrieval, faithfulness, risk_level, review_state, suspected_error, "
            + "suspected_reason, trace_id, latency_ms, prompt_tokens, completion_tokens, created_at";

    private static final String SQL_INSERT =
            "INSERT INTO contentops_kb_qa (" + COLS + ") "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_BY_ID =
            "SELECT " + COLS + " FROM contentops_kb_qa WHERE qa_id = ?";

    private static final String SQL_SELECT_ALL =
            "SELECT " + COLS + " FROM contentops_kb_qa";

    private static final String SQL_UPDATE_REVIEW =
            "UPDATE contentops_kb_qa SET review_state = ? WHERE qa_id = ?";

    private static final String SQL_UPDATE_MARKERS =
            "UPDATE contentops_kb_qa SET citations = ?, citation_check = ?, retrieval = ?, faithfulness = ?, "
                    + "risk_level = ?, review_state = ? WHERE qa_id = ?";

    private static final String SQL_MARK_SUSPECTED =
            "UPDATE contentops_kb_qa SET suspected_error = TRUE, suspected_reason = ? WHERE qa_id = ?";

    private final JdbcTemplate jdbcTemplate;

    /** 落库一次问答。 */
    public void insert(QaRecord record) {
        jdbcTemplate.update(SQL_INSERT,
                record.getQaId(), record.getOwnerId(), record.getQuestion(), record.getAnswer(),
                record.isRefused(), record.getSource(), record.getCitations(), record.getCitationCheck(),
                record.getRetrieval(), record.getFaithfulness(), record.getRiskLevel(), record.getReviewState(),
                record.isSuspectedError(), record.getSuspectedReason(), record.getTraceId(),
                record.getLatencyMs(), record.getPromptTokens(), record.getCompletionTokens(),
                Timestamp.valueOf(record.getCreatedAt() == null ? LocalDateTime.now() : record.getCreatedAt()));
    }

    public Optional<QaRecord> findById(String qaId) {
        List<QaRecord> rows = jdbcTemplate.query(SQL_BY_ID, this::mapRow, qaId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 列表查询（可按复核状态 / 风险等级 / 是否拒答 / 是否疑似错误过滤），用于复核清单与高风险检索日志。
     */
    public List<QaRecord> list(String ownerId, String reviewState, String riskLevel,
                               boolean refusedOnly, boolean suspectedOnly, int limit) {
        StringBuilder sql = new StringBuilder(SQL_SELECT_ALL).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (isPresent(ownerId)) {
            sql.append(" AND owner_id = ?");
            params.add(ownerId);
        }
        if (isPresent(reviewState)) {
            sql.append(" AND review_state = ?");
            params.add(reviewState.trim().toUpperCase());
        }
        if (isPresent(riskLevel)) {
            sql.append(" AND risk_level = ?");
            params.add(riskLevel.trim().toUpperCase());
        }
        if (refusedOnly) {
            sql.append(" AND refused = TRUE");
        }
        if (suspectedOnly) {
            sql.append(" AND suspected_error = TRUE");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 500)));
        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray());
    }

    /** 取某租户最近一段时间的问答（隐式信号归因要比对上下文）。 */
    public List<QaRecord> findRecent(String ownerId, LocalDateTime since, int limit) {
        StringBuilder sql = new StringBuilder(SQL_SELECT_ALL).append(" WHERE created_at >= ?");
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.valueOf(since));
        if (isPresent(ownerId)) {
            sql.append(" AND owner_id = ?");
            params.add(ownerId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray());
    }

    /** 人工复核完成 / 状态流转。 */
    public void updateReviewState(String qaId, String reviewState) {
        jdbcTemplate.update(SQL_UPDATE_REVIEW, reviewState, qaId);
    }

    /** 复检：覆盖更新标记类字段（引用/检索/忠实度/风险/复核状态），不改答案文本。 */
    public void updateMarkers(String qaId, String citations, String citationCheck, String retrieval,
                              String faithfulness, String riskLevel, String reviewState) {
        jdbcTemplate.update(SQL_UPDATE_MARKERS, citations, citationCheck, retrieval, faithfulness,
                riskLevel, reviewState, qaId);
    }

    /** 隐式信号命中：打「疑似错误」标签（不修改答案本身）。 */
    public void markSuspected(String qaId, String reason) {
        jdbcTemplate.update(SQL_MARK_SUSPECTED, reason, qaId);
    }

    /** 概览统计。 */
    public Map<String, Object> stats(String ownerId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(1) AS total, "
                        + "SUM(CASE WHEN refused THEN 1 ELSE 0 END) AS refused, "
                        + "SUM(CASE WHEN risk_level = 'HIGH' THEN 1 ELSE 0 END) AS high_risk, "
                        + "SUM(CASE WHEN review_state = 'NEEDS_REVIEW' THEN 1 ELSE 0 END) AS needs_review, "
                        + "SUM(CASE WHEN suspected_error THEN 1 ELSE 0 END) AS suspected "
                        + "FROM contentops_kb_qa WHERE 1 = 1");
        if (isPresent(ownerId)) {
            sql.append(" AND owner_id = ?");
            return jdbcTemplate.queryForMap(sql.toString(), ownerId);
        }
        return jdbcTemplate.queryForMap(sql.toString());
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private QaRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return QaRecord.builder()
                .qaId(rs.getString("qa_id"))
                .ownerId(rs.getString("owner_id"))
                .question(rs.getString("question"))
                .answer(rs.getString("answer"))
                .refused(rs.getBoolean("refused"))
                .source(rs.getString("source"))
                .citations(rs.getString("citations"))
                .citationCheck(rs.getString("citation_check"))
                .retrieval(rs.getString("retrieval"))
                .faithfulness(rs.getString("faithfulness"))
                .riskLevel(rs.getString("risk_level"))
                .reviewState(rs.getString("review_state"))
                .suspectedError(rs.getBoolean("suspected_error"))
                .suspectedReason(rs.getString("suspected_reason"))
                .traceId(rs.getString("trace_id"))
                .latencyMs(rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms"))
                .promptTokens(rs.getObject("prompt_tokens") == null ? null : rs.getInt("prompt_tokens"))
                .completionTokens(rs.getObject("completion_tokens") == null ? null : rs.getInt("completion_tokens"))
                .createdAt(rs.getTimestamp("created_at") == null ? null
                        : rs.getTimestamp("created_at").toLocalDateTime())
                .build();
    }
}
