package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评论消息入库：把一条评论（消息）与其附件（图片/视频）作为一个整体落库。
 *
 * <p>消息模型：发送者（author + author_user_id）、时间（comment_time）、正文（content）、
 * 附件（contentops_comment_media，逐条记录所属用户）、归属作品与账号（work_id/credential_id）。
 * 附件与其「所属用户」在此统一写入，保证任何来源（按笔记采集 / 通知增量 / 手动）行为一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentMessageService {

    private final CommentRepository commentRepository;
    private final CommentMediaRepository mediaRepository;

    /** 保存一条评论消息（含附件）；已存在返回 false（幂等去重）。 */
    public boolean save(Comment comment) {
        if (comment == null || comment.getCommentId() == null) {
            return false;
        }
        if (commentRepository.exists(comment.getCommentId())) {
            return false;
        }
        commentRepository.insert(comment);
        if (!commentRepository.exists(comment.getCommentId())) {
            return false;
        }
        saveMedia(comment);
        return true;
    }

    /** 写入附件明细，并把数量回写到评论行（用于「只看带图」筛选）。 */
    public int saveMedia(Comment comment) {
        List<CommentMedia> mediaList = comment.getMedia();
        if (mediaList == null || mediaList.isEmpty()) {
            return 0;
        }
        int order = 0;
        List<CommentMedia> rows = new ArrayList<>();
        for (CommentMedia media : mediaList) {
            if (media == null || media.getUrl() == null || media.getUrl().isBlank()) {
                continue;
            }
            rows.add(CommentMedia.builder()
                    .mediaId(media.getMediaId() == null ? UUID.randomUUID().toString() : media.getMediaId())
                    .commentId(comment.getCommentId())
                    .mediaType(media.getMediaType() == null ? "IMAGE" : media.getMediaType())
                    .url(media.getUrl())
                    .thumbUrl(media.getThumbUrl())
                    .width(media.getWidth())
                    .height(media.getHeight())
                    .sortOrder(order++)
                    .ownerUserId(comment.getAuthorUserId())
                    .ownerName(comment.getAuthor())
                    .extraJson(media.getExtraJson())
                    .build());
        }
        if (rows.isEmpty()) {
            return 0;
        }
        mediaRepository.deleteByComment(comment.getCommentId());
        int count = mediaRepository.insertAll(rows);
        commentRepository.updateMediaCount(comment.getCommentId(), count);
        comment.setMediaCount(count);
        log.info("[CommentMessage] 附件已入库: commentId={}, 数量={}, 所属={}",
                comment.getCommentId(), count, comment.getAuthor());
        return count;
    }

    /** 批量取回附件（评论列表用，避免 N+1）。 */
    public Map<String, List<CommentMedia>> mediaByCommentIds(List<String> commentIds) {
        return mediaRepository.mapByCommentIds(commentIds == null ? List.of() : commentIds);
    }

    /** 给评论列表填充附件明细与数量。 */
    public void attachMedia(List<Comment> comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }
        List<String> ids = comments.stream().map(Comment::getCommentId).toList();
        Map<String, List<CommentMedia>> mediaMap = mediaRepository.mapByCommentIds(ids);
        Map<String, List<CommentMedia>> safe = new LinkedHashMap<>(mediaMap);
        for (Comment comment : comments) {
            List<CommentMedia> list = safe.getOrDefault(comment.getCommentId(), List.of());
            comment.setMedia(list);
            comment.setMediaCount(comment.getMediaCount() == null ? list.size() : comment.getMediaCount());
        }
    }
}