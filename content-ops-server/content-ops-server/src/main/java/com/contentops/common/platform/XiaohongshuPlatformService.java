package com.contentops.common.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Xiaohongshu (小红书) open platform service.
 *
 * <p>Provides real API integration for analytics:
 * <ul>
 *   <li>Get note (笔记) detail with interaction data (likes, collects, comments)</li>
 *   <li>Get note comment list for sentiment analysis</li>
 * </ul>
 *
 * <p>API reference: https://open.xiaohongshu.com
 *
 * <p>Note: Xiaohongshu's open platform currently provides read APIs (note detail,
 * comment list) but does not provide a public write/publish API. Publishing
 * to Xiaohongshu still requires manual operation through the app.
 */
@Slf4j
@Component
public class XiaohongshuPlatformService {

    private final PlatformApiProperties.XiaohongshuConfig config;
    private final RestClient restClient;

    public XiaohongshuPlatformService(PlatformApiProperties properties) {
        this.config = properties.getXiaohongshu();
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .build();
        if (config.isEnabled()) {
            log.info("XiaohongshuPlatformService initialized: appId={}", config.getAppId());
        } else {
            log.info("XiaohongshuPlatformService disabled — set contentops.platform.xiaohongshu.enabled=true to enable.");
        }
    }

    public boolean isAvailable() {
        return config.isEnabled()
                && config.getAppId() != null && !config.getAppId().isBlank()
                && config.getAppSecret() != null && !config.getAppSecret().isBlank();
    }

    /**
     * Get note (笔记) detail including interaction metrics.
     *
     * @param accessToken OAuth access token
     * @param noteId      the note ID to query
     * @return formatted note detail with likes, collects, comments
     */
    public String getNoteDetail(String accessToken, String noteId) {
        if (!isAvailable()) {
            return "[小红书数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("note_id", noteId);

            NoteDetailResponse response = restClient.post()
                    .uri("/api/v1/note/info")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(NoteDetailResponse.class);

            return formatNoteDetail(response, noteId);
        } catch (Exception e) {
            log.error("Failed to get Xiaohongshu note detail", e);
            return "[小红书数据获取失败] " + e.getMessage();
        }
    }

    /**
     * Get note comments for sentiment analysis and engagement insights.
     *
     * @param accessToken OAuth access token
     * @param noteId      the note ID
     * @param cursor      pagination cursor (empty string for first page)
     * @param limit       number of comments per page (max 20)
     * @return formatted comment list
     */
    public String getNoteComments(String accessToken, String noteId, String cursor, int limit) {
        if (!isAvailable()) {
            return "[小红书数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("note_id", noteId);
            body.put("cursor", cursor != null ? cursor : "");
            body.put("limit", Math.min(limit, 20));

            CommentListResponse response = restClient.post()
                    .uri("/api/v1/note/comment/list")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(CommentListResponse.class);

            return formatCommentList(response, noteId);
        } catch (Exception e) {
            log.error("Failed to get Xiaohongshu note comments", e);
            return "[小红书数据获取失败] " + e.getMessage();
        }
    }

    private String formatNoteDetail(NoteDetailResponse response, String noteId) {
        if (response == null || response.getData() == null) {
            return "[小红书笔记详情] note_id: " + noteId + "\n无数据返回。";
        }
        NoteData data = response.getData();
        StringBuilder sb = new StringBuilder();
        sb.append("[小红书笔记详情] note_id: ").append(noteId).append("\n");
        sb.append("- 标题: ").append(data.getTitle() != null ? data.getTitle() : "无标题").append("\n");
        sb.append("- 作者: ").append(data.getUserName() != null ? data.getUserName() : "未知").append("\n");
        sb.append("- 点赞数: ").append(data.getLikes()).append("\n");
        sb.append("- 收藏数: ").append(data.getCollects()).append("\n");
        sb.append("- 评论数: ").append(data.getCommentsTotal()).append("\n");
        if (data.getImgList() != null && !data.getImgList().isEmpty()) {
            sb.append("- 图片数: ").append(data.getImgList().size()).append("\n");
        }
        return sb.toString();
    }

    private String formatCommentList(CommentListResponse response, String noteId) {
        if (response == null || response.getData() == null || response.getData().getList() == null) {
            return "[小红书评论列表] note_id: " + noteId + "\n无评论数据返回。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[小红书评论列表] note_id: ").append(noteId).append("\n");
        List<CommentItem> comments = response.getData().getList();
        sb.append("共获取 ").append(comments.size()).append(" 条评论:\n");
        int i = 1;
        for (CommentItem comment : comments) {
            sb.append(i++).append(". [").append(comment.getUserName() != null ? comment.getUserName() : "匿名");
            sb.append("] ").append(comment.getContent() != null ? comment.getContent() : "");
            sb.append(" (赞").append(comment.getLikeNum()).append(")\n");
        }
        if (response.getData().isHasMore()) {
            sb.append("\n还有更多评论，可使用 cursor='").append(response.getData().getCursor()).append("' 翻页。");
        }
        return sb.toString();
    }

    // ──────────────────── Xiaohongshu API Response DTOs ────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NoteDetailResponse {
        @JsonProperty("code")
        private int code;
        @JsonProperty("msg")
        private String msg;
        @JsonProperty("data")
        private NoteData data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NoteData {
        @JsonProperty("note_id")
        private String noteId;
        @JsonProperty("title")
        private String title;
        @JsonProperty("desc")
        private String desc;
        @JsonProperty("user_name")
        private String userName;
        @JsonProperty("like_count")
        private long likes;
        @JsonProperty("collect_count")
        private long collects;
        @JsonProperty("comment_count")
        private long commentsTotal;
        @JsonProperty("image_list")
        private List<String> imgList;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommentListResponse {
        @JsonProperty("code")
        private int code;
        @JsonProperty("msg")
        private String msg;
        @JsonProperty("data")
        private CommentData data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommentData {
        @JsonProperty("list")
        private List<CommentItem> list;
        @JsonProperty("cursor")
        private String cursor;
        @JsonProperty("has_more")
        private boolean hasMore;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommentItem {
        @JsonProperty("comment_id")
        private String commentId;
        @JsonProperty("user_name")
        private String userName;
        @JsonProperty("content")
        private String content;
        @JsonProperty("like_count")
        private int likeNum;
        @JsonProperty("create_time")
        private long createTime;
    }
}
