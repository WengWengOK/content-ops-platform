package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评论附件仓储：按评论维度批量写入与查询（列表页一次查回，避免 N+1）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CommentMediaRepository {

    private static final String COLS = "media_id, comment_id, media_type, url, thumb_url, width, height, "
            + "sort_order, owner_user_id, owner_name, extra_json, created_at";

    private final JdbcTemplate jdbcTemplate;

    public void insert(CommentMedia media) {
        String sql = "INSERT INTO contentops_comment_media (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try {
            jdbcTemplate.update(sql,
                    media.getMediaId() == null ? UUID.randomUUID().toString() : media.getMediaId(),
                    media.getCommentId(),
                    media.getMediaType() == null ? "IMAGE" : media.getMediaType(),
                    media.getUrl(), media.getThumbUrl(), media.getWidth(), media.getHeight(),
                    media.getSortOrder() == null ? 0 : media.getSortOrder(),
                    media.getOwnerUserId(), media.getOwnerName(), media.getExtraJson(),
                    Timestamp.valueOf(LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[CommentMedia] 写入附件失败: commentId={}, err={}", media.getCommentId(), e.getMessage());
        }
    }

    /** 批量写入某条评论的附件。 */
    public int insertAll(List<CommentMedia> mediaList) {
        if (mediaList == null || mediaList.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (CommentMedia media : mediaList) {
            insert(media);
            count++;
        }
        return count;
    }

    /** 按评论批量查附件（用于评论列表一次取回）。 */
    public Map<String, List<CommentMedia>> mapByCommentIds(List<String> commentIds) {
        Map<String, List<CommentMedia>> result = new LinkedHashMap<>();
        if (commentIds == null || commentIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", commentIds.stream().map(id -> "?").toList());
        String sql = "SELECT " + COLS + " FROM contentops_comment_media WHERE comment_id IN ("
                + placeholders + ") ORDER BY comment_id, sort_order";
        try {
            List<CommentMedia> all = jdbcTemplate.query(sql, this::mapRow, commentIds.toArray());
            for (CommentMedia media : all) {
                result.computeIfAbsent(media.getCommentId(), key -> new ArrayList<>()).add(media);
            }
        } catch (Exception e) {
            log.warn("[CommentMedia] 批量查询附件失败: {}", e.getMessage());
        }
        return result;
    }

    public List<CommentMedia> listByComment(String commentId) {
        try {
            return jdbcTemplate.query("SELECT " + COLS + " FROM contentops_comment_media "
                    + "WHERE comment_id = ? ORDER BY sort_order", this::mapRow, commentId);
        } catch (Exception e) {
            return List.of();
        }
    }

    public void deleteByComment(String commentId) {
        try {
            jdbcTemplate.update("DELETE FROM contentops_comment_media WHERE comment_id = ?", commentId);
        } catch (Exception e) {
            log.debug("[CommentMedia] 删除附件失败: {}", e.getMessage());
        }
    }

    private CommentMedia mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return CommentMedia.builder()
                .mediaId(rs.getString("media_id"))
                .commentId(rs.getString("comment_id"))
                .mediaType(rs.getString("media_type"))
                .url(rs.getString("url"))
                .thumbUrl(rs.getString("thumb_url"))
                .width(rs.getObject("width") == null ? null : rs.getInt("width"))
                .height(rs.getObject("height") == null ? null : rs.getInt("height"))
                .sortOrder(rs.getInt("sort_order"))
                .ownerUserId(rs.getString("owner_user_id"))
                .ownerName(rs.getString("owner_name"))
                .extraJson(rs.getString("extra_json"))
                .createdAt(created == null ? null : created.toLocalDateTime())
                .build();
    }
}