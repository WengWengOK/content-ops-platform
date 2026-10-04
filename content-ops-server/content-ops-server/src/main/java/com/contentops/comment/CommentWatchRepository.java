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
            + "credential_id, auto_analyze, enabled, last_collected_at, last_new_count, total_collected, "
            + "last_source, last_error, token_state, token_checked_at, token_set_at, observed_ttl_days, "
            + "created_at";

    private static final String SQL_INSERT =
            "INSERT INTO contentops_comment_watch (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
                    + "total_collected = total_collected + ?, last_source = ?, last_error = ?, "
                    + "token_state = CASE WHEN ? IS NULL THEN 'OK' ELSE token_state END, "
                    + "token_checked_at = ? WHERE watch_id = ?";
    private static final String SQL_TOKEN_STATE =
            "UPDATE contentops_comment_watch SET token_state = ?, token_checked_at = ?, last_error = ? "
                    + "WHERE watch_id = ?";
    private static final String SQL_UPDATE_TOKEN =
            "UPDATE contentops_comment_watch SET xsec_token = ?, token_state = 'OK', token_checked_at = ?, "
                    + "token_set_at = ? WHERE watch_id = ?";
    /** 判定过期时：记录过期时刻，并把「从写入到过期」的天数沉淀为观测有效期。 */
    private static final String SQL_MARK_EXPIRED =
            "UPDATE contentops_comment_watch SET token_state = 'EXPIRED', token_checked_at = ?, "
                    + "last_error = ?, observed_ttl_days = COALESCE("
                    + "  CAST(EXTRACT(EPOCH FROM (? - COALESCE(token_set_at, created_at))) / 86400 AS INT), "
                    + "  observed_ttl_days) WHERE watch_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public void insert(CommentWatch w) {
        try {
            jdbcTemplate.update(SQL_INSERT,
                    w.getWatchId(), w.getOwnerId(), w.getPlatform(), w.getWorkId(), w.getWorkflowId(),
                    w.getXsecToken(), w.getCredentialId(), w.isAutoAnalyze(), w.isEnabled(),
                    w.getLastCollectedAt() == null ? null : Timestamp.valueOf(w.getLastCollectedAt()),
                    w.getLastNewCount(), w.getTotalCollected(), w.getLastSource(), w.getLastError(),
                    w.getTokenState() == null ? "UNKNOWN" : w.getTokenState(),
                    w.getTokenCheckedAt() == null ? null : Timestamp.valueOf(w.getTokenCheckedAt()),
                    Timestamp.valueOf(w.getTokenSetAt() == null
                            ? (w.getCreatedAt() == null ? LocalDateTime.now() : w.getCreatedAt())
                            : w.getTokenSetAt()),
                    w.getObservedTtlDays(),
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
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            jdbcTemplate.update(SQL_AFTER_RUN, now, newCount, totalDelta, source,
                    truncate(error, 900), error, now, watchId);
        } catch (Exception e) {
            log.warn("[Comment] 更新监控项采集状态失败: watchId={}, err={}", watchId, e.getMessage());
        }
    }

    /** 标记票据健康状态（EXPIRED / ERROR），用于前端过期提醒。 */
    public void updateTokenState(String watchId, String tokenState, String error) {
        try {
            jdbcTemplate.update(SQL_TOKEN_STATE, tokenState, Timestamp.valueOf(LocalDateTime.now()),
                    truncate(error, 900), watchId);
        } catch (Exception e) {
            log.warn("[Comment] 更新票据状态失败: watchId={}, err={}", watchId, e.getMessage());
        }
    }

    /** 用户更新 xsec_token 后重置为健康，并把「首次使用时间」重置为现在。 */
    public void updateXsecToken(String watchId, String xsecToken) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update(SQL_UPDATE_TOKEN, xsecToken, now, now, watchId);
    }

    /** 票据被判定过期：记录过期时刻并沉淀观测有效期（用于后续提前预警）。 */
    public void markTokenExpired(String watchId, String error) {
        try {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            jdbcTemplate.update(SQL_MARK_EXPIRED, now, truncate(error, 900), now, watchId);
        } catch (Exception e) {
            log.warn("[Comment] 标记票据过期失败: watchId={}, err={}", watchId, e.getMessage());
            updateTokenState(watchId, "EXPIRED", error);
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
                .credentialId(rs.getString("credential_id"))
                .autoAnalyze(rs.getBoolean("auto_analyze"))
                .enabled(rs.getBoolean("enabled"))
                .lastCollectedAt(last == null ? null : last.toLocalDateTime())
                .lastNewCount(rs.getInt("last_new_count"))
                .totalCollected(rs.getInt("total_collected"))
                .lastSource(rs.getString("last_source"))
                .lastError(rs.getString("last_error"))
                .tokenState(rs.getString("token_state"))
                .tokenCheckedAt(rs.getTimestamp("token_checked_at") == null
                        ? null : rs.getTimestamp("token_checked_at").toLocalDateTime())
                .tokenSetAt(rs.getTimestamp("token_set_at") == null
                        ? null : rs.getTimestamp("token_set_at").toLocalDateTime())
                .observedTtlDays(rs.getObject("observed_ttl_days") == null
                        ? null : rs.getInt("observed_ttl_days"))
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