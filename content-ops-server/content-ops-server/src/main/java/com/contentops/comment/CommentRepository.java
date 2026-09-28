package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 评论仓储：采集入库（按 comment_id 去重）、过滤查询、意图/情感统计、分析与回复状态更新。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CommentRepository {

    private static final String COLS = "comment_id, platform_comment_id, collected_via, owner_id, platform, "
            + "work_id, workflow_id, author, content, likes, comment_time, reply_to, intent, sentiment, "
            + "ai_summary, ai_reply, reply_status, dialog_history, collected_at";

    private static final String SQL_INSERT =
            "INSERT INTO contentops_comment "
                    + "(comment_id, platform_comment_id, collected_via, owner_id, platform, work_id, "
                    + " workflow_id, author, content, likes, comment_time, reply_to, intent, sentiment, "
                    + " ai_summary, ai_reply, reply_status, dialog_history, collected_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SQL_LIST =
            "SELECT " + COLS + " FROM contentops_comment "
                    + "WHERE (? = '' OR owner_id = ?) "
                    + "  AND (? IS NULL OR ? = '' OR platform = ?) "
                    + "  AND (? IS NULL OR ? = '' OR work_id = ?) "
                    + "  AND (? IS NULL OR ? = '' OR intent = ?) "
                    + "  AND (? IS NULL OR ? = '' OR sentiment = ?) "
                    + "ORDER BY collected_at DESC LIMIT ?";
    private static final String SQL_BY_ID =
            "SELECT " + COLS + " FROM contentops_comment WHERE comment_id = ?";
    private static final String SQL_EXISTS =
            "SELECT COUNT(1) FROM contentops_comment WHERE comment_id = ?";
    private static final String SQL_UPDATE_ANALYSIS =
            "UPDATE contentops_comment SET intent = ?, sentiment = ?, ai_summary = ?, ai_reply = ? "
                    + "WHERE comment_id = ?";
    private static final String SQL_UPDATE_ANALYSIS_DRAFT =
            "UPDATE contentops_comment SET intent = ?, sentiment = ?, ai_summary = ?, ai_reply = ?, "
                    + "reply_status = CASE WHEN reply_status = 'NONE' THEN 'DRAFT' ELSE reply_status END "
                    + "WHERE comment_id = ?";
    private static final String SQL_UPDATE_REPLY =
            "UPDATE contentops_comment SET ai_reply = ?, reply_status = ? WHERE comment_id = ?";
    private static final String SQL_UPDATE_DIALOG =
            "UPDATE contentops_comment SET dialog_history = ? WHERE comment_id = ?";
    private static final String SQL_STATS =
            "SELECT COALESCE(intent, '未识别') AS intent, COUNT(*) AS cnt FROM contentops_comment "
                    + "WHERE (? = '' OR owner_id = ?) AND (? IS NULL OR ? = '' OR platform = ?) "
                    + "  AND (? IS NULL OR ? = '' OR work_id = ?) "
                    + "GROUP BY intent ORDER BY cnt DESC";
    private static final String SQL_STATS_SENTIMENT =
            "SELECT COALESCE(sentiment, 'UNKNOWN') AS sentiment, COUNT(*) AS cnt FROM contentops_comment "
                    + "WHERE (? = '' OR owner_id = ?) AND (? IS NULL OR ? = '' OR platform = ?) "
                    + "  AND (? IS NULL OR ? = '' OR work_id = ?) "
                    + "GROUP BY sentiment ORDER BY cnt DESC";

    private final JdbcTemplate jdbcTemplate;

    public void insert(Comment c) {
        try {
            jdbcTemplate.update(SQL_INSERT,
                    c.getCommentId(), c.getPlatformCommentId(),
                    c.getCollectedVia() == null ? "note" : c.getCollectedVia(),
                    c.getOwnerId(), c.getPlatform(), c.getWorkId(), c.getWorkflowId(),
                    c.getAuthor(), c.getContent(), c.getLikes(),
                    c.getCommentTime() == null ? null : Timestamp.valueOf(c.getCommentTime()),
                    c.getReplyTo(), c.getIntent(), c.getSentiment(), c.getAiSummary(), c.getAiReply(),
                    c.getReplyStatus() == null ? "NONE" : c.getReplyStatus(),
                    c.getDialogHistory(), Timestamp.valueOf(java.time.LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[Comment] 插入失败(可能是重复评论): id={}, err={}", c.getCommentId(), e.getMessage());
        }
    }

    /** 评论是否已存在（采集去重，避免重复调用模型分析）。 */
    public boolean exists(String commentId) {
        if (commentId == null || commentId.isBlank()) {
            return false;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(SQL_EXISTS, Integer.class, commentId);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("[Comment] 存在性检查失败: id={}, err={}", commentId, e.getMessage());
            return false;
        }
    }

    public List<Comment> list(String ownerId, String platform, String workId,
                              String intent, String sentiment, int limit) {
        String o = ownerId == null ? "" : ownerId;
        String p = platform == null ? "" : platform;
        String w = workId == null ? "" : workId;
        String i = intent == null ? "" : intent;
        String s = sentiment == null ? "" : sentiment;
        try {
            return jdbcTemplate.query(SQL_LIST, this::mapRow,
                    o, o, p, p, p, w, w, w, i, i, i, s, s, s, limit);
        } catch (Exception e) {
            log.error("[Comment] 查询失败", e);
            return List.of();
        }
    }

    public Optional<Comment> findById(String commentId) {
        try {
            return jdbcTemplate.query(SQL_BY_ID, this::mapRow, commentId).stream().findFirst();
        } catch (Exception e) {
            log.error("[Comment] 按 ID 查询失败", e);
            return Optional.empty();
        }
    }

    public void updateAnalysis(String commentId, String intent, String sentiment,
                               String summary, String reply) {
        try {
            jdbcTemplate.update(SQL_UPDATE_ANALYSIS, intent, sentiment, summary, reply, commentId);
        } catch (Exception e) {
            log.warn("[Comment] 更新分析失败: id={}", commentId);
        }
    }

    /** 更新分析结果，并把尚未处理的评论置为「草稿」状态（有待发回复）。 */
    public void updateAnalysisAndStatus(String commentId, String intent, String sentiment,
                                        String summary, String reply) {
        try {
            jdbcTemplate.update(SQL_UPDATE_ANALYSIS_DRAFT, intent, sentiment, summary, reply, commentId);
        } catch (Exception e) {
            log.warn("[Comment] 更新分析(含状态)失败: id={}, err={}", commentId, e.getMessage());
        }
    }

    public void updateReply(String commentId, String reply, String status) {
        try {
            jdbcTemplate.update(SQL_UPDATE_REPLY, reply, status, commentId);
        } catch (Exception e) {
            log.warn("[Comment] 更新回复失败: id={}", commentId);
        }
    }

    public void updateDialog(String commentId, String dialogHistory) {
        try {
            jdbcTemplate.update(SQL_UPDATE_DIALOG, dialogHistory, commentId);
        } catch (Exception e) {
            log.warn("[Comment] 更新对话失败: id={}", commentId);
        }
    }

    public List<Map<String, Object>> statsIntent(String ownerId, String platform, String workId) {
        String o = ownerId == null ? "" : ownerId;
        String p = platform == null ? "" : platform;
        String w = workId == null ? "" : workId;
        return jdbcTemplate.queryForList(SQL_STATS, o, o, p, p, p, w, w, w);
    }

    public List<Map<String, Object>> statsSentiment(String ownerId, String platform, String workId) {
        String o = ownerId == null ? "" : ownerId;
        String p = platform == null ? "" : platform;
        String w = workId == null ? "" : workId;
        return jdbcTemplate.queryForList(SQL_STATS_SENTIMENT, o, o, p, p, p, w, w, w);
    }

    private Comment mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp commentTime = rs.getTimestamp("comment_time");
        Timestamp collected = rs.getTimestamp("collected_at");
        return Comment.builder()
                .commentId(rs.getString("comment_id"))
                .platformCommentId(rs.getString("platform_comment_id"))
                .collectedVia(rs.getString("collected_via"))
                .ownerId(rs.getString("owner_id"))
                .platform(rs.getString("platform"))
                .workId(rs.getString("work_id"))
                .workflowId(rs.getString("workflow_id"))
                .author(rs.getString("author"))
                .content(rs.getString("content"))
                .likes(rs.getInt("likes"))
                .commentTime(commentTime == null ? null : commentTime.toLocalDateTime())
                .replyTo(rs.getString("reply_to"))
                .intent(rs.getString("intent"))
                .sentiment(rs.getString("sentiment"))
                .aiSummary(rs.getString("ai_summary"))
                .aiReply(rs.getString("ai_reply"))
                .replyStatus(rs.getString("reply_status"))
                .dialogHistory(rs.getString("dialog_history"))
                .collectedAt(collected == null ? null : collected.toLocalDateTime())
                .build();
    }
}