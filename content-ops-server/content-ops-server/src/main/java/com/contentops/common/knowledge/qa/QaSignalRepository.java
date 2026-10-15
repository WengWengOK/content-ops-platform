package com.contentops.common.knowledge.qa;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 问答反馈信号仓储（点踩 / 追问 / 重复提问 / 引用秒退 / 转人工确认）。
 */
@Repository
@RequiredArgsConstructor
public class QaSignalRepository {

    private static final String COLS = "signal_id, qa_id, owner_id, signal_type, confidence, detail, "
            + "related_qa_id, created_at";

    private static final String SQL_INSERT =
            "INSERT INTO contentops_kb_qa_signal (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_BY_QA =
            "SELECT " + COLS + " FROM contentops_kb_qa_signal WHERE qa_id = ? ORDER BY created_at DESC";

    private static final String SQL_BY_OWNER =
            "SELECT " + COLS + " FROM contentops_kb_qa_signal "
                    + "WHERE 1 = 1";

    private static final String SQL_TYPE_STATS =
            "SELECT signal_type, COUNT(1) AS cnt FROM contentops_kb_qa_signal WHERE 1 = 1";

    private final JdbcTemplate jdbcTemplate;

    public void insert(QaSignal signal) {
        jdbcTemplate.update(SQL_INSERT,
                signal.getSignalId(), signal.getQaId(), signal.getOwnerId(), signal.getSignalType(),
                signal.getConfidence(), signal.getDetail(), signal.getRelatedQaId(),
                Timestamp.valueOf(signal.getCreatedAt()));
    }

    public List<QaSignal> listByQa(String qaId) {
        return jdbcTemplate.query(SQL_BY_QA, this::mapRow, qaId);
    }

    public List<QaSignal> listByOwner(String ownerId, int limit) {
        if (ownerId == null || ownerId.isBlank()) {
            return jdbcTemplate.query(SQL_BY_OWNER + " ORDER BY created_at DESC LIMIT ?", this::mapRow,
                    Math.max(1, Math.min(limit, 500)));
        }
        return jdbcTemplate.query(SQL_BY_OWNER + " AND owner_id = ? ORDER BY created_at DESC LIMIT ?",
                this::mapRow, ownerId, Math.max(1, Math.min(limit, 500)));
    }

    public List<Map<String, Object>> typeStats(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return jdbcTemplate.queryForList(SQL_TYPE_STATS + " GROUP BY signal_type ORDER BY cnt DESC");
        }
        return jdbcTemplate.queryForList(
                SQL_TYPE_STATS + " AND owner_id = ? GROUP BY signal_type ORDER BY cnt DESC", ownerId);
    }

    private QaSignal mapRow(ResultSet rs, int rowNum) throws SQLException {
        return QaSignal.builder()
                .signalId(rs.getString("signal_id"))
                .qaId(rs.getString("qa_id"))
                .ownerId(rs.getString("owner_id"))
                .signalType(rs.getString("signal_type"))
                .confidence(rs.getObject("confidence") == null ? null : rs.getDouble("confidence"))
                .detail(rs.getString("detail"))
                .relatedQaId(rs.getString("related_qa_id"))
                .createdAt(rs.getTimestamp("created_at") == null ? null
                        : rs.getTimestamp("created_at").toLocalDateTime())
                .build();
    }
}
