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
 * 场景客服回复 Agent 配置仓储。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ReplyAgentProfileRepository {

    private static final String COLS = "profile_id, owner_id, name, scenario, description, tone, system_prompt, "
            + "product_facts, forbidden_claims, escalate_keywords, reply_max_chars, allow_links, enabled, "
            + "is_default, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public void insert(ReplyAgentProfile profile) {
        String sql = "INSERT INTO contentops_reply_agent_profile (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            jdbcTemplate.update(sql, profile.getProfileId(), profile.getOwnerId(), profile.getName(),
                    profile.getScenario(), profile.getDescription(), profile.getTone(), profile.getSystemPrompt(),
                    profile.getProductFacts(), profile.getForbiddenClaims(), profile.getEscalateKeywords(),
                    profile.getReplyMaxChars() == null ? 120 : profile.getReplyMaxChars(),
                    profile.isAllowLinks(), profile.isEnabled(), profile.isDefaultProfile(), now, now);
        } catch (Exception e) {
            log.error("[ReplyAgent] 写入 profile 失败: name={}, err={}", profile.getName(), e.getMessage());
            throw e;
        }
    }

    public void update(ReplyAgentProfile profile) {
        String sql = "UPDATE contentops_reply_agent_profile SET name = ?, scenario = ?, description = ?, "
                + "tone = ?, system_prompt = ?, product_facts = ?, forbidden_claims = ?, escalate_keywords = ?, "
                + "reply_max_chars = ?, allow_links = ?, enabled = ?, is_default = ?, updated_at = ? "
                + "WHERE profile_id = ?";
        try {
            jdbcTemplate.update(sql, profile.getName(), profile.getScenario(), profile.getDescription(),
                    profile.getTone(), profile.getSystemPrompt(), profile.getProductFacts(),
                    profile.getForbiddenClaims(), profile.getEscalateKeywords(),
                    profile.getReplyMaxChars() == null ? 120 : profile.getReplyMaxChars(),
                    profile.isAllowLinks(), profile.isEnabled(), profile.isDefaultProfile(),
                    Timestamp.valueOf(LocalDateTime.now()), profile.getProfileId());
        } catch (Exception e) {
            log.warn("[ReplyAgent] 更新 profile 失败: id={}, err={}", profile.getProfileId(), e.getMessage());
        }
    }

    public List<ReplyAgentProfile> list(String ownerId, int limit) {
        String o = ownerId == null ? "" : ownerId;
        try {
            return jdbcTemplate.query("SELECT " + COLS + " FROM contentops_reply_agent_profile "
                            + "WHERE (? = '' OR owner_id IS NULL OR owner_id = ?) "
                            + "ORDER BY is_default DESC, scenario, name LIMIT ?",
                    this::mapRow, o, o, Math.max(1, limit));
        } catch (Exception e) {
            log.error("[ReplyAgent] 查询 profile 失败", e);
            return List.of();
        }
    }

    public Optional<ReplyAgentProfile> findById(String profileId) {
        try {
            return jdbcTemplate.query("SELECT " + COLS + " FROM contentops_reply_agent_profile "
                    + "WHERE profile_id = ?", this::mapRow, profileId).stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<ReplyAgentProfile> findDefault(String ownerId, String scenario) {
        String o = ownerId == null ? "" : ownerId;
        String s = scenario == null ? "" : scenario;
        try {
            return jdbcTemplate.query("SELECT " + COLS + " FROM contentops_reply_agent_profile "
                            + "WHERE enabled = TRUE AND is_default = TRUE "
                            + "AND (? = '' OR owner_id = ?) AND (? = '' OR scenario = ?) LIMIT 1",
                    this::mapRow, o, o, s, s).stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public long count() {
        try {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM contentops_reply_agent_profile", Long.class);
            return count == null ? 0 : count;
        } catch (Exception e) {
            return 0;
        }
    }

    public void delete(String profileId) {
        jdbcTemplate.update("DELETE FROM contentops_reply_agent_profile WHERE profile_id = ?", profileId);
    }

    public void clearDefault(String ownerId) {
        String o = ownerId == null ? "" : ownerId;
        try {
            jdbcTemplate.update("UPDATE contentops_reply_agent_profile SET is_default = FALSE "
                    + "WHERE (? = '' OR owner_id = ?)", o, o);
        } catch (Exception e) {
            log.debug("[ReplyAgent] 清理默认标记失败: {}", e.getMessage());
        }
    }

    private ReplyAgentProfile mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return ReplyAgentProfile.builder()
                .profileId(rs.getString("profile_id"))
                .ownerId(rs.getString("owner_id"))
                .name(rs.getString("name"))
                .scenario(rs.getString("scenario"))
                .description(rs.getString("description"))
                .tone(rs.getString("tone"))
                .systemPrompt(rs.getString("system_prompt"))
                .productFacts(rs.getString("product_facts"))
                .forbiddenClaims(rs.getString("forbidden_claims"))
                .escalateKeywords(rs.getString("escalate_keywords"))
                .replyMaxChars(rs.getObject("reply_max_chars") == null ? 120 : rs.getInt("reply_max_chars"))
                .allowLinks(rs.getBoolean("allow_links"))
                .enabled(rs.getBoolean("enabled"))
                .defaultProfile(rs.getBoolean("is_default"))
                .createdAt(created == null ? null : created.toLocalDateTime())
                .updatedAt(updated == null ? null : updated.toLocalDateTime())
                .build();
    }
}