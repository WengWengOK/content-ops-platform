package com.contentops.trend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 突发热点事件存储（contentops_trend_burst_event，见 schema.sql）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TrendBurstEventRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO contentops_trend_burst_event "
                    + "(event_id, platform, title, url, heat, prev_heat, rank, prev_rank, "
                    + " heat_delta, rank_delta, burst_label, burst_score, captured_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_RECENT =
            "SELECT event_id, platform, title, url, heat, prev_heat, rank, prev_rank, "
                    + "heat_delta, rank_delta, burst_label, burst_score, captured_at "
                    + "FROM contentops_trend_burst_event "
                    + "WHERE (? IS NULL OR ? = '' OR platform = ?) "
                    + "ORDER BY captured_at DESC LIMIT ?";
    private static final String SQL_RECENT_SINCE =
            "SELECT event_id, platform, title, url, heat, prev_heat, rank, prev_rank, "
                    + "heat_delta, rank_delta, burst_label, burst_score, captured_at "
                    + "FROM contentops_trend_burst_event "
                    + "WHERE (? IS NULL OR ? = '' OR platform = ?) AND captured_at >= ? "
                    + "ORDER BY captured_at DESC LIMIT ?";

    public void saveAll(List<TrendBurstEvent> events) {
        for (TrendBurstEvent e : events) {
            try {
                jdbcTemplate.update(SQL_INSERT,
                        e.getEventId(),
                        e.getPlatform(),
                        e.getTitle(),
                        e.getUrl(),
                        e.getHeat(),
                        e.getPrevHeat(),
                        e.getRank(),
                        e.getPrevRank(),
                        e.getHeatDelta(),
                        e.getRankDelta(),
                        e.getBurstLabel(),
                        e.getBurstScore(),
                        Timestamp.valueOf(e.getCapturedAt()));
            } catch (Exception ex) {
                log.warn("[Trend] 保存突发事件失败: title={}, err={}", e.getTitle(), ex.getMessage());
            }
        }
    }

    public List<TrendBurstEvent> findRecent(String platform, int limit) {
        try {
            String pl = (platform == null || platform.isBlank()) ? "" : platform;
            return jdbcTemplate.query(SQL_RECENT,
                    (rs, i) -> mapRow(rs), pl, pl, pl, limit);
        } catch (Exception e) {
            log.error("[Trend] 查询突发事件失败: platform={}", platform, e);
            return List.of();
        }
    }

    public List<TrendBurstEvent> findRecentSince(String platform, java.sql.Timestamp since, int limit) {
        try {
            String pl = (platform == null || platform.isBlank()) ? "" : platform;
            return jdbcTemplate.query(SQL_RECENT_SINCE,
                    (rs, i) -> mapRow(rs), pl, pl, pl, since, limit);
        } catch (Exception e) {
            log.error("[Trend] 查询突发事件失败(时间窗口): platform={}", platform, e);
            return List.of();
        }
    }

    private TrendBurstEvent mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp captured = rs.getTimestamp("captured_at");
        return TrendBurstEvent.builder()
                .eventId(rs.getString("event_id"))
                .platform(rs.getString("platform"))
                .title(rs.getString("title"))
                .url(rs.getString("url"))
                .heat(rs.getLong("heat") == 0 && rs.wasNull() ? null : rs.getLong("heat"))
                .prevHeat(rs.getLong("prev_heat") == 0 && rs.wasNull() ? null : rs.getLong("prev_heat"))
                .rank(rs.getInt("rank") == 0 && rs.wasNull() ? null : rs.getInt("rank"))
                .prevRank(rs.getInt("prev_rank") == 0 && rs.wasNull() ? null : rs.getInt("prev_rank"))
                .heatDelta(rs.getLong("heat_delta") == 0 && rs.wasNull() ? null : rs.getLong("heat_delta"))
                .rankDelta(rs.getInt("rank_delta") == 0 && rs.wasNull() ? null : rs.getInt("rank_delta"))
                .burstLabel(rs.getString("burst_label"))
                .burstScore(rs.getInt("burst_score") == 0 && rs.wasNull() ? null : rs.getInt("burst_score"))
                .capturedAt(captured == null ? null : captured.toLocalDateTime())
                .build();
    }
}
