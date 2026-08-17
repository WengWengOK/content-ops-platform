package com.contentops.trend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 热点监控方向订阅存储（contentops_trend_subscription，见 schema.sql）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TrendSubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO contentops_trend_subscription (subscription_id, owner_id, keyword, enabled) "
                    + "VALUES (?, ?, ?, TRUE)";
    private static final String SQL_FIND =
            "SELECT subscription_id, owner_id, keyword, enabled, created_at "
                    + "FROM contentops_trend_subscription WHERE subscription_id = ? AND owner_id = ?";
    private static final String SQL_LIST =
            "SELECT subscription_id, owner_id, keyword, enabled, created_at "
                    + "FROM contentops_trend_subscription WHERE owner_id = ? "
                    + "ORDER BY created_at DESC";
    private static final String SQL_LIST_ENABLED =
            "SELECT subscription_id, owner_id, keyword, enabled, created_at "
                    + "FROM contentops_trend_subscription WHERE owner_id = ? AND enabled = TRUE "
                    + "ORDER BY created_at DESC";
    private static final String SQL_LIST_ALL_ENABLED =
            "SELECT subscription_id, owner_id, keyword, enabled, created_at "
                    + "FROM contentops_trend_subscription WHERE enabled = TRUE "
                    + "ORDER BY created_at DESC";
    private static final String SQL_DELETE =
            "DELETE FROM contentops_trend_subscription WHERE subscription_id = ? AND owner_id = ?";
    private static final String SQL_EXISTS =
            "SELECT COUNT(*) FROM contentops_trend_subscription WHERE owner_id = ? AND keyword = ?";
    private static final String SQL_UPDATE_ENABLED =
            "UPDATE contentops_trend_subscription SET enabled = ? "
                    + "WHERE subscription_id = ? AND owner_id = ?";

    public boolean create(String subscriptionId, String ownerId, String keyword) {
        try {
            Integer count = jdbcTemplate.queryForObject(SQL_EXISTS, Integer.class, ownerId, keyword);
            if (count != null && count > 0) {
                return false;
            }
            jdbcTemplate.update(SQL_INSERT, subscriptionId, ownerId, keyword);
            return true;
        } catch (Exception e) {
            log.error("[Trend] 创建监控方向失败: owner={}, keyword={}", ownerId, keyword, e);
            return false;
        }
    }

    public Optional<TrendSubscription> findById(String subscriptionId, String ownerId) {
        try {
            List<TrendSubscription> rows = jdbcTemplate.query(SQL_FIND,
                    (rs, i) -> mapRow(rs), subscriptionId, ownerId);
            return rows.stream().findFirst();
        } catch (Exception e) {
            log.error("[Trend] 查询监控方向失败: id={}", subscriptionId, e);
            return Optional.empty();
        }
    }

    public List<TrendSubscription> listByOwner(String ownerId) {
        try {
            return jdbcTemplate.query(SQL_LIST, (rs, i) -> mapRow(rs), ownerId);
        } catch (Exception e) {
            log.error("[Trend] 列出监控方向失败: owner={}", ownerId, e);
            return List.of();
        }
    }

    /** 仅返回启用中的监控方向（用于 watch 过滤与关键词命中记录） */
    public List<TrendSubscription> listEnabledByOwner(String ownerId) {
        try {
            return jdbcTemplate.query(SQL_LIST_ENABLED, (rs, i) -> mapRow(rs), ownerId);
        } catch (Exception e) {
            log.error("[Trend] 列出启用监控方向失败: owner={}", ownerId, e);
            return List.of();
        }
    }

    /** 全部用户的启用监控方向（供全局轮询做关键词命中记录） */
    public List<TrendSubscription> listAllEnabled() {
        try {
            return jdbcTemplate.query(SQL_LIST_ALL_ENABLED, (rs, i) -> mapRow(rs));
        } catch (Exception e) {
            log.error("[Trend] 列出全部启用监控方向失败", e);
            return List.of();
        }
    }

    public int delete(String subscriptionId, String ownerId) {
        return jdbcTemplate.update(SQL_DELETE, subscriptionId, ownerId);
    }

    /**
     * 启用/暂停监控方向（鱼皮式关键词启停）。
     *
     * @return 是否更新成功（订阅不存在时返回 false）
     */
    public boolean updateEnabled(String subscriptionId, String ownerId, boolean enabled) {
        try {
            return jdbcTemplate.update(SQL_UPDATE_ENABLED, enabled, subscriptionId, ownerId) > 0;
        } catch (Exception e) {
            log.error("[Trend] 更新监控方向状态失败: id={}, owner={}", subscriptionId, ownerId, e);
            return false;
        }
    }

    private TrendSubscription mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        return TrendSubscription.builder()
                .subscriptionId(rs.getString("subscription_id"))
                .ownerId(rs.getString("owner_id"))
                .keyword(rs.getString("keyword"))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(createdAt == null ? null : createdAt.toLocalDateTime())
                .build();
    }
}
