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
import java.util.Map;
import java.util.Optional;

/**
 * 评论用户关系库：记录每个评论者的身份标签（粉丝/关注/好友/常客/路人/自己）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CommentUserRepository {

    private static final String COLS = "user_key, owner_id, platform, user_id, nickname, relation, "
            + "relation_source, comment_count, note, first_seen_at, last_seen_at";

    private final JdbcTemplate jdbcTemplate;

    public void upsert(String userKey, String ownerId, String platform, String userId, String nickname) {
        String sql = "INSERT INTO contentops_comment_user (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (user_key) DO UPDATE SET nickname = ?, comment_count = "
                + "contentops_comment_user.comment_count + 1, last_seen_at = CURRENT_TIMESTAMP";
        try {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            jdbcTemplate.update(sql, userKey, ownerId, platform, userId, nickname, "STRANGER", "DERIVED",
                    1, null, now, now, nickname);
        } catch (Exception e) {
            // H2 等不支持 ON CONFLICT 时退化为「存在则更新、否则插入」
            log.debug("[CommentUser] upsert 失败，改用兼容路径: {}", e.getMessage());
            try {
                if (find(userKey).isPresent()) {
                    jdbcTemplate.update("UPDATE contentops_comment_user SET nickname = ?, "
                            + "comment_count = comment_count + 1, last_seen_at = ? WHERE user_key = ?",
                            nickname, Timestamp.valueOf(LocalDateTime.now()), userKey);
                } else {
                    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
                    jdbcTemplate.update("INSERT INTO contentops_comment_user (" + COLS
                                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                            userKey, ownerId, platform, userId, nickname, "STRANGER", "DERIVED", 1, null,
                            now, now);
                }
            } catch (Exception inner) {
                log.debug("[CommentUser] 记录失败: {}", inner.getMessage());
            }
        }
    }

    public Optional<Map<String, Object>> find(String userKey) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT " + COLS + " FROM contentops_comment_user WHERE user_key = ?", userKey)
                    .stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<Map<String, Object>> list(String ownerId, String relation, int limit) {
        String o = ownerId == null ? "" : ownerId;
        String r = relation == null ? "" : relation;
        try {
            return jdbcTemplate.queryForList("SELECT " + COLS + " FROM contentops_comment_user "
                            + "WHERE (? = '' OR owner_id = ?) AND (? = '' OR relation = ?) "
                            + "ORDER BY comment_count DESC, last_seen_at DESC LIMIT ?",
                    o, o, r, r, Math.max(1, limit));
        } catch (Exception e) {
            log.warn("[CommentUser] 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> statsByRelation(String ownerId) {
        String o = ownerId == null ? "" : ownerId;
        try {
            return jdbcTemplate.queryForList("SELECT relation, COUNT(*) AS cnt, SUM(comment_count) AS comments "
                    + "FROM contentops_comment_user WHERE (? = '' OR owner_id = ?) "
                    + "GROUP BY relation ORDER BY cnt DESC", o, o);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 显式设置关系标签（手工维护或外部关系源同步，如 connections 通知 → FAN）。 */
    public void setRelation(String userKey, String relation, String source, String note) {
        try {
            jdbcTemplate.update("UPDATE contentops_comment_user SET relation = ?, relation_source = ?, "
                    + "note = ?, last_seen_at = ? WHERE user_key = ?",
                    relation, source, note, Timestamp.valueOf(LocalDateTime.now()), userKey);
        } catch (Exception e) {
            log.warn("[CommentUser] 更新关系失败: {}", e.getMessage());
        }
    }

    public String relationOf(String userKey) {
        try {
            return jdbcTemplate.query("SELECT relation FROM contentops_comment_user WHERE user_key = ?",
                    (ResultSet rs, int rowNum) -> rs.getString("relation"), userKey)
                    .stream().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public Integer commentCount(String userKey) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT comment_count FROM contentops_comment_user WHERE user_key = ?",
                    Integer.class, userKey);
        } catch (Exception e) {
            return null;
        }
    }

    private String mapRelation(ResultSet rs) throws SQLException {
        return rs.getString("relation");
    }
}