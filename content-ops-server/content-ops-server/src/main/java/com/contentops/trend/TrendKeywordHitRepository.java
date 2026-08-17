package com.contentops.trend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 关键词命中记录存储（contentops_trend_keyword_hit，见 schema.sql）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TrendKeywordHitRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO contentops_trend_keyword_hit "
                    + "(hit_id, owner_id, keyword, platform, title, url, heat, rank, category, summary, captured_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_RECENT =
            "SELECT hit_id, owner_id, keyword, platform, title, url, heat, rank, category, summary, captured_at "
                    + "FROM contentops_trend_keyword_hit "
                    + "WHERE owner_id = ? "
                    + "  AND (? IS NULL OR ? = '' OR keyword = ?) "
                    + "ORDER BY captured_at DESC LIMIT ?";
    private static final String SQL_RECENT_SINCE =
            "SELECT hit_id, owner_id, keyword, platform, title, url, heat, rank, category, summary, captured_at "
                    + "FROM contentops_trend_keyword_hit "
                    + "WHERE owner_id = ? "
                    + "  AND (? IS NULL OR ? = '' OR keyword = ?) "
                    + "  AND captured_at >= ? "
                    + "ORDER BY captured_at DESC LIMIT ?";

    public void saveAll(List<TrendKeywordHit> hits) {
        for (TrendKeywordHit h : hits) {
            try {
                jdbcTemplate.update(SQL_INSERT,
                        h.getHitId(),
                        h.getOwnerId(),
                        h.getKeyword(),
                        h.getPlatform(),
                        h.getTitle(),
                        h.getUrl(),
                        h.getHeat(),
                        h.getRank(),
                        h.getCategory(),
                        h.getSummary(),
                        Timestamp.valueOf(h.getCapturedAt()));
            } catch (Exception e) {
                log.warn("[Trend] 保存关键词命中失败: keyword={}, title={}, err={}",
                        h.getKeyword(), h.getTitle(), e.getMessage());
            }
        }
    }

    public List<TrendKeywordHit> findRecent(String ownerId, String keyword, int limit) {
        try {
            String kw = (keyword == null || keyword.isBlank()) ? "" : keyword;
            return jdbcTemplate.query(SQL_RECENT,
                    (rs, i) -> mapRow(rs), ownerId, kw, kw, kw, limit);
        } catch (Exception e) {
            log.error("[Trend] 查询关键词命中失败: owner={}, keyword={}", ownerId, keyword, e);
            return List.of();
        }
    }

    public List<TrendKeywordHit> findRecentSince(
            String ownerId, String keyword, java.sql.Timestamp since, int limit) {
        try {
            String kw = (keyword == null || keyword.isBlank()) ? "" : keyword;
            return jdbcTemplate.query(SQL_RECENT_SINCE,
                    (rs, i) -> mapRow(rs), ownerId, kw, kw, kw, since, limit);
        } catch (Exception e) {
            log.error("[Trend] 查询关键词命中失败(时间窗口): owner={}, keyword={}", ownerId, keyword, e);
            return List.of();
        }
    }

    private TrendKeywordHit mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp captured = rs.getTimestamp("captured_at");
        return TrendKeywordHit.builder()
                .hitId(rs.getString("hit_id"))
                .ownerId(rs.getString("owner_id"))
                .keyword(rs.getString("keyword"))
                .platform(rs.getString("platform"))
                .title(rs.getString("title"))
                .url(rs.getString("url"))
                .heat(rs.getLong("heat") == 0 && rs.wasNull() ? null : rs.getLong("heat"))
                .rank(rs.getInt("rank") == 0 && rs.wasNull() ? null : rs.getInt("rank"))
                .category(rs.getString("category"))
                .summary(rs.getString("summary"))
                .capturedAt(captured == null ? null : captured.toLocalDateTime())
                .build();
    }
}
