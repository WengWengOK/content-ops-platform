package com.contentops.trend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 热点快照存储（contentops_trend_hotspot，见 schema.sql）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TrendRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO contentops_trend_hotspot "
                    + "(id, platform, title, url, heat, rank, category, summary, captured_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SQL_LATEST =
            "SELECT id, platform, title, url, heat, rank, category, summary, captured_at "
                    + "FROM contentops_trend_hotspot "
                    + "WHERE captured_at = (SELECT MAX(captured_at) FROM contentops_trend_hotspot "
                    + "  WHERE platform = ?) "
                    + "AND platform = ? "
                    + "ORDER BY COALESCE(rank, 99999), COALESCE(heat, 0) DESC "
                    + "LIMIT ?";
    private static final String SQL_LATEST_ALL =
            "SELECT id, platform, title, url, heat, rank, category, summary, captured_at "
                    + "FROM contentops_trend_hotspot t "
                    + "WHERE captured_at = (SELECT MAX(captured_at) FROM contentops_trend_hotspot "
                    + "  WHERE platform = t.platform) "
                    + "ORDER BY platform, COALESCE(rank, 99999), COALESCE(heat, 0) DESC "
                    + "LIMIT ?";
    private static final String SQL_PREVIOUS =
            "SELECT id, platform, title, url, heat, rank, category, summary, captured_at "
                    + "FROM contentops_trend_hotspot "
                    + "WHERE platform = ? AND LOWER(title) = LOWER(?) AND captured_at < ? "
                    + "ORDER BY captured_at DESC LIMIT 1";
    private static final String SQL_FIRST_SEEN =
            "SELECT id, platform, title, url, heat, rank, category, summary, captured_at "
                    + "FROM contentops_trend_hotspot "
                    + "WHERE platform = ? AND LOWER(title) = LOWER(?) "
                    + "ORDER BY captured_at ASC LIMIT 1";
    private static final String SQL_WINDOW =
            "SELECT t.id, t.platform, t.title, t.url, t.heat, t.rank, t.category, t.summary, t.captured_at "
                    + "FROM contentops_trend_hotspot t "
                    + "JOIN (SELECT platform, LOWER(title) AS ltitle, MAX(captured_at) AS max_captured "
                    + "       FROM contentops_trend_hotspot "
                    + "       WHERE captured_at >= ? "
                    + "       GROUP BY platform, LOWER(title)) w "
                    + "  ON w.platform = t.platform AND w.ltitle = LOWER(t.title) "
                    + "  AND t.captured_at = w.max_captured "
                    + "WHERE (? IS NULL OR ? = '' OR t.platform = ?) "
                    + "ORDER BY COALESCE(t.heat, 0) DESC, t.captured_at DESC "
                    + "LIMIT ?";
    private static final String SQL_HISTORY =
            "SELECT id, platform, title, url, heat, rank, category, summary, captured_at "
                    + "FROM contentops_trend_hotspot "
                    + "WHERE platform = ? AND LOWER(title) = LOWER(?) AND captured_at >= ? "
                    + "ORDER BY captured_at ASC";
    private static final String SQL_PLATFORM_HEAT =
            "SELECT t.id, t.platform, t.title, t.url, t.heat, t.rank, t.category, t.summary, t.captured_at "
                    + "FROM contentops_trend_hotspot t "
                    + "JOIN (SELECT platform, MAX(captured_at) AS max_captured "
                    + "       FROM contentops_trend_hotspot "
                    + "       WHERE LOWER(title) = LOWER(?) AND captured_at >= ? "
                    + "       GROUP BY platform) w "
                    + "  ON t.platform = w.platform AND t.captured_at = w.max_captured "
                    + "  AND LOWER(t.title) = LOWER(?) "
                    + "ORDER BY COALESCE(t.heat, 0) DESC";
    private static final String SQL_FIRST_SEEN_ANY =
            "SELECT id, platform, title, url, heat, rank, category, summary, captured_at "
                    + "FROM contentops_trend_hotspot "
                    + "WHERE LOWER(title) = LOWER(?) "
                    + "ORDER BY captured_at ASC LIMIT 1";

    public void saveAll(List<TrendHotspot> hotspots) {
        for (TrendHotspot h : hotspots) {
            try {
                jdbcTemplate.update(SQL_INSERT,
                        h.getId(),
                        h.getPlatform(),
                        h.getTitle(),
                        h.getUrl(),
                        h.getHeat(),
                        h.getRank(),
                        h.getCategory(),
                        h.getSummary(),
                        Timestamp.valueOf(h.getCapturedAt()));
            } catch (Exception e) {
                log.warn("[Trend] 保存热点失败: platform={}, title={}, err={}",
                        h.getPlatform(), h.getTitle(), e.getMessage());
            }
        }
    }

    public List<TrendHotspot> findLatest(String platform, int limit) {
        try {
            return jdbcTemplate.query(SQL_LATEST,
                    (rs, i) -> mapRow(rs), platform, platform, limit);
        } catch (Exception e) {
            log.error("[Trend] 查询最新热点失败: platform={}", platform, e);
            return List.of();
        }
    }

    public List<TrendHotspot> findLatestAll(int limit) {
        try {
            return jdbcTemplate.query(SQL_LATEST_ALL,
                    (rs, i) -> mapRow(rs), limit);
        } catch (Exception e) {
            log.error("[Trend] 查询最新热点失败(全部平台)", e);
            return List.of();
        }
    }

    /**
     * 同主题（平台+标题）上一快照条目，用于热度/排名环比对比（突发热点检测）。
     *
     * @param before 仅取早于该快照时间的记录
     */
    public Optional<TrendHotspot> findPrevious(String platform, String title, java.sql.Timestamp before) {
        try {
            List<TrendHotspot> rows = jdbcTemplate.query(SQL_PREVIOUS,
                    (rs, i) -> mapRow(rs), platform, title, before);
            return rows.stream().findFirst();
        } catch (Exception e) {
            log.warn("[Trend] 查询上一快照失败: platform={}, title={}", platform, title, e);
            return Optional.empty();
        }
    }

    /** 同主题（平台+标题）首次出现时间，用于「新上榜」标记 */
    public Optional<TrendHotspot> findFirstSeen(String platform, String title) {
        try {
            List<TrendHotspot> rows = jdbcTemplate.query(SQL_FIRST_SEEN,
                    (rs, i) -> mapRow(rs), platform, title);
            return rows.stream().findFirst();
        } catch (Exception e) {
            log.warn("[Trend] 查询首次出现失败: platform={}, title={}", platform, title, e);
            return Optional.empty();
        }
    }

    /**
     * 时间窗口查询：返回 since 之后出现在热榜上的主题（按平台+标题去重，
     * 保留最近一次出现），按热度排序——包含已掉出当前榜的历史热点。
     */
    public List<TrendHotspot> findLatestInWindow(String platform, java.sql.Timestamp since, int limit) {
        try {
            String pl = (platform == null || platform.isBlank()) ? "" : platform;
            return jdbcTemplate.query(SQL_WINDOW, (rs, i) -> mapRow(rs), since, pl, pl, pl, limit);
        } catch (Exception e) {
            log.error("[Trend] 查询时间窗口热点失败: platform={}", platform, e);
            return List.of();
        }
    }

    /** 单主题热度/排名时间序列（趋势曲线） */
    public List<TrendHotspot> findHistory(String platform, String title, java.sql.Timestamp since) {
        try {
            return jdbcTemplate.query(SQL_HISTORY, (rs, i) -> mapRow(rs), platform, title, since);
        } catch (Exception e) {
            log.error("[Trend] 查询主题历史失败: platform={}, title={}", platform, title, e);
            return List.of();
        }
    }

    /** 主题在各平台的最近热度/排名/链接（平台对比） */
    public List<TrendHotspot> findPlatformHeat(String title, java.sql.Timestamp since) {
        try {
            return jdbcTemplate.query(SQL_PLATFORM_HEAT, (rs, i) -> mapRow(rs), title, since, title);
        } catch (Exception e) {
            log.error("[Trend] 查询平台对比失败: title={}", title, e);
            return List.of();
        }
    }

    /** 主题首次出现时间（不限平台） */
    public Optional<TrendHotspot> findFirstSeenAny(String title) {
        try {
            List<TrendHotspot> rows = jdbcTemplate.query(SQL_FIRST_SEEN_ANY, (rs, i) -> mapRow(rs), title);
            return rows.stream().findFirst();
        } catch (Exception e) {
            log.warn("[Trend] 查询首次出现失败(任意平台): title={}", title, e);
            return Optional.empty();
        }
    }

    private TrendHotspot mapRow(java.sql.ResultSet rs) {
        try {
            Timestamp captured = rs.getTimestamp("captured_at");
            return TrendHotspot.builder()
                    .id(rs.getString("id"))
                    .platform(rs.getString("platform"))
                    .title(rs.getString("title"))
                    .url(rs.getString("url"))
                    .heat(rs.getLong("heat") == 0 && rs.wasNull() ? null : rs.getLong("heat"))
                    .rank(rs.getInt("rank") == 0 && rs.wasNull() ? null : rs.getInt("rank"))
                    .category(rs.getString("category"))
                    .summary(rs.getString("summary"))
                    .capturedAt(captured == null ? null : captured.toLocalDateTime())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
