package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CommentRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO contentops_comment "
                    + "(comment_id, owner_id, platform, work_id, workflow_id, author, content, likes, "
                    + " comment_time, reply_to, intent, sentiment, ai_summary, ai_reply, "
                    + " reply_status, dialog_history, collected_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SQL_LIST =
            "SELECT comment_id, owner_id, platform, work_id, workflow_id, author, content, likes, "
                    + "comment_time, reply_to, intent, sentiment, ai_summary, ai_reply, "
                    + "reply_status, dialog_history, collected_at FROM contentops_comment "
                    + "WHERE (? = '' OR owner_id = ?) "
                    + "  AND (? IS NULL OR ? = '' OR platform = ?) "
                    + "  AND (? IS NULL OR ? = '' OR work_id = ?) "
                    + "  AND (? IS NULL OR ? = '' OR intent = ?) "
                    + "  AND (? IS NULL OR ? = '' OR sentiment = ?) "
                    + "ORDER BY collected_at DESC LIMIT ?";
    private static final String SQL_BY_ID =
            "SELECT comment_id, owner_id, platform, work_id, workflow_id, author, content, likes, "
                    + "comment_time, reply_to, intent, sentiment, ai_summary, ai_reply, "
                    + "reply_status, dialog_history, collected_at FROM contentops_comment "
                    + "WHERE comment_id = ?";
    private static final String SQL_UPDATE_ANALYSIS =
            "UPDATE contentops_comment SET intent = ?, sentiment = ?, ai_summary = ?, ai_reply = ? "
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

    public void insert(Comment c) {
        try {
            jdbcTemplate.update(SQL_INSERT,
                    c.getCommentId(), c.getOwnerId(), c.getPlatform(), c.getWorkId(), c.getWorkflowId(),
                    c.getAuthor(), c.getContent(), c.getLikes(),
                    c.getCommentTime() == null ? null : Timestamp.valueOf(c.getCommentTime()),
                    c.getReplyTo(), c.getIntent(), c.getSentiment(), c.getAiSummary(), c.getAiReply(),
                    c.getReplyStatus() == null ? "NONE" : c.getReplyStatus(),
                    c.getDialogHistory(), Timestamp.valueOf(java.time.LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[Comment] 插入失败: err={}", e.getMessage());
        }
    }

    public List<Comment> list(String ownerId, String platform, String workId,
                              String intent, String sentiment, int limit) {
        try {
            String o = ownerId == null ? "" : ownerId;
            String p = platform == null ? "" : platform;
            String w = workId == null ? "" : workId;
            String i = intent == null ? "" : intent;
            String s = sentiment == null ? "" : sentiment;
            return jdbcTemplate.query(SQL_LIST, (rs, n) -> mapRow(rs),
                    o, o, p, p, p, w, w, w, i, i, i, s, s, s, limit);
        } catch (Exception e) {
            log.error("[Comment] 查询失败", e);
            return List.of();
        }
    }

    public Optional<Comment> findById(String commentId) {
        try {
            return jdbcTemplate.query(SQL_BY_ID, (rs, n) -> mapRow(rs), commentId).stream().findFirst();
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

    private Comment mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp commentTime = rs.getTimestamp("comment_time");
        Timestamp collected = rs.getTimestamp("collected_at");
        return Comment.builder()
                .commentId(rs.getString("comment_id"))
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
