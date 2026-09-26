package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 评论采集监控项仓储（PostgreSQL / H2 通用 SQL）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CommentWatchRepository {

    private static final String COLS = "watch_id, owner_id, platform, work_id, workflow_id, xsec_token, "
            + "auto_analyze, enabled, last_collected_at, last_new_count, total_collected, last_source, "
            + "last_error, created_at";

    private static final String SQL_INSERT =
            "INSERT INTO contentops_comment_watch (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_LIST =
            "SELECT " + COLS + " FROM contentops_comment_watch "
                    + "WHERE (? = '' OR owner_id = ?) ORDER BY created_at DESC LIMIT ?";
    private static final String SQL_BY_ID =
            "SELECT " + COLS + " FROM contentops_comment_watch WHERE watch_id = ?";
    private static final String SQL_BY_WORK =
            "SELECT " + COLS + " FROM contentops_comment_watch "
                    + "WHERE platform = ? AND work_id = ? AND (? = '' OR owner_id = ?) ORDER BY created_at DESC LIMIT 1";
    private static final String SQL_DUE =
            "SELECT " + COLS + " FROM contentops_comment_watch WHERE enabled = TRUE "
                    + "AND (last_collected_at IS NULL OR last_collected_at <= ?) "
                    + "ORDER BY COALESCE(last_collected_at, TIMESTAMP '1970-01-01 00:00:00') ASC LIMIT ?";
    private static final String SQL_UPDATE_ENABLED =
            "UPDATE contentops_comment_watch SET enabled = ? WHERE watch_id = ?";
    private static final String SQL_DELETE =
            "DELETE FROM contentops_comment_watch WHERE watch_id = ?";
    private static final String SQL_AFTER_RUN =
            "UPDATE contentops_comment_watch SET last_collected_at = ?, last_new_count = ?, "
                    + "total_collected = total_collected + ?, last_source = ?, last_error = ? WHERE watch_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public void insert(CommentWatch w) {
        try {
            jdbcTemplate.update(SQL_INSERT,
                    w.getWatchId(), w.getOwnerId(), w.getPlatform(), w.getWorkId(), w.getWorkflowId(),
                    w.getXsecToken(), w.isAutoAnalyze(), w.isEnabled(),
                    w.getLastCollectedAt() == null ? null : Timestamp.valueOf(w.getLastCollectedAt()),
                    w.getLastNewCount(), w.getTotalCollected(), w.getLastSource(), w.getLastError(),
                    Timestamp.valueOf(w.getCreatedAt() == null ? LocalDateTime.now() : w.getCreatedAt()));
        } catch (Exception e) {
            log.error("[Comment] 新增监控项失败: workId={}, err={}", w.getWorkId(), e.getMessage());
            throw e;
        }
    }

    public List<CommentWatch> list(String ownerId, int limit) {
        String o = ownerId == null ? "" : ownerId;
        try {
            return jdbcTemplate.query(SQL_LIST, this::mapRow, o, o, Math.max(1, limit));
        } catch (Exception e) {
            log.error("[Comment] 查询监控项失败", e);
            return List.of();
        }
    }

    public Optional<CommentWatch> findById(String watchId) {
        try {
            return jdbcTemplate.query(SQL_BY_ID, this::mapRow, watchId).stream().findFirst();
        } catch (Exception e) {
            log.error("[Comment] 按 ID 查询监控项失败: {}", watchId, e);
            return Optional.empty();
        }
    }

    public Optional<CommentWatch> findByWork(String platform, String workId, String ownerId) {
        String o = ownerId == null ? "" : ownerId;
        try {
            return jdbcTemplate.query(SQL_BY_WORK, this::mapRow, platform, workId, o, o).stream().findFirst();
        } catch (Exception e) {
            log.error("[Comment] 按作品查询监控项失败: {}", workId, e);
            return Optional.empty();
        }
    }

    /** 取「到期需要采集」的监控项：启用中且距上次采集已超过最小间隔。 */
    public List<CommentWatch> listDue(int minIntervalSeconds, int limit) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(Math.max(0, minIntervalSeconds));
        try {
            return jdbcTemplate.query(SQL_DUE, this::mapRow,
                    Timestamp.valueOf(threshold), Math.max(1, limit));
        } catch (Exception e) {
            log.error("[Comment] 查询待采集监控项失败", e);
            return List.of();
        }
    }

    public void updateEnabled(String watchId, boolean enabled) {
        jdbcTemplate.update(SQL_UPDATE_ENABLED, enabled, watchId);
    }

    public void delete(String watchId) {
        jdbcTemplate.update(SQL_DELETE, watchId);
    }

    public void updateAfterRun(String watchId, int newCount, int totalDelta, String source, String error) {
        try {
            jdbcTemplate.update(SQL_AFTER_RUN, Timestamp.valueOf(LocalDateTime.now()),
                    newCount, totalDelta, source, truncate(error, 900), watchId);
        } catch (Exception e) {
            log.warn("[Comment] 更新监控项采集状态失败: watchId={}, err={}", watchId, e.getMessage());
        }
    }

    private CommentWatch mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp last = rs.getTimestamp("last_collected_at");
        Timestamp created = rs.getTimestamp("created_at");
        return CommentWatch.builder()
                .watchId(rs.getString("watch_id"))
                .ownerId(rs.getString("owner_id"))
                .platform(rs.getString("platform"))
                .workId(rs.getString("work_id"))
                .workflowId(rs.getString("workflow_id"))
                .xsecToken(rs.getString("xsec_token"))
                .autoAnalyze(rs.getBoolean("auto_analyze"))
                .enabled(rs.getBoolean("enabled"))
                .lastCollectedAt(last == null ? null : last.toLocalDateTime())
                .lastNewCount(rs.getInt("last_new_count"))
                .totalCollected(rs.getInt("total_collected"))
                .lastSource(rs.getString("last_source"))
                .lastError(rs.getString("last_error"))
                .createdAt(created == null ? null : created.toLocalDateTime())
                .build();
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}