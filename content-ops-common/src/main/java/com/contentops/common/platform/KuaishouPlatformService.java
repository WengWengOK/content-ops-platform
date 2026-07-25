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
 * Kuaishou (快手) open platform service.
 *
 * <p>Provides real API integration for:
 * <ul>
 *   <li><b>Publishing</b>: Start upload, upload video, publish video</li>
 *   <li><b>Analytics</b>: Query user info, video list, video detail stats</li>
 * </ul>
 *
 * <p>API reference: https://mp.kuaishou.com/platformDocs/develop/serverSDK
 *
 * <p>The Kuaishou publish flow has three steps:
 * <ol>
 *   <li>Start upload ({@code /openapi/photo/start_upload}) → get upload_token + endpoint</li>
 *   <li>Upload video (binary or multipart to the returned endpoint)</li>
 *   <li>Publish video ({@code /openapi/photo/publish}) with caption, cover, etc.</li>
 * </ol>
 */
@Slf4j
@Component
public class KuaishouPlatformService {

    private final PlatformApiProperties.KuaishouConfig config;
    private final RestClient restClient;

    public KuaishouPlatformService(PlatformApiProperties properties) {
        this.config = properties.getKuaishou();
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .build();
        if (config.isEnabled()) {
            log.info("KuaishouPlatformService initialized: appId={}", config.getAppId());
        } else {
            log.info("KuaishouPlatformService disabled — set contentops.platform.kuaishou.enabled=true to enable.");
        }
    }

    public boolean isAvailable() {
        return config.isEnabled()
                && config.getAppId() != null && !config.getAppId().isBlank()
                && config.getAppSecret() != null && !config.getAppSecret().isBlank();
    }

    /**
     * Step 1: Start the upload process to get an upload_token and endpoint.
     *
     * @param accessToken OAuth access token
     * @return UploadTokenResponse containing upload_token and endpoint, or null on failure
     */
    public StartUploadResponse startUpload(String accessToken) {
        if (!isAvailable()) return null;
        try {
            StartUploadResponse response = restClient.post()
                    .uri("/openapi/photo/start_upload?app_id=" + config.getAppId()
                            + "&access_token=" + accessToken)
                    .retrieve()
                    .body(StartUploadResponse.class);

            if (response != null && response.getResult() == 1) {
                log.info("Kuaishou upload started: upload_token={}", response.getUploadToken());
                return response;
            }
            log.error("Failed to start Kuaishou upload: {}", response);
            return null;
        } catch (Exception e) {
            log.error("Failed to start Kuaishou upload", e);
            return null;
        }
    }

    /**
     * Step 3: Publish a video after the file has been uploaded.
     *
     * @param accessToken OAuth access token
     * @param caption      video caption
     * @param coverUrl     cover image URL
     * @return the photo_id of the published video, or error message
     */
    public String publishVideo(String accessToken, String caption, String coverUrl) {
        if (!isAvailable()) {
            return "[快手发布不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("caption", caption);
            if (coverUrl != null && !coverUrl.isBlank()) {
                body.put("cover_url", coverUrl);
            }

            PublishResponse response = restClient.post()
                    .uri("/openapi/photo/publish?app_id=" + config.getAppId()
                            + "&access_token=" + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PublishResponse.class);

            if (response != null && response.getResult() == 1 && response.getPhotoId() != null) {
                log.info("Kuaishou video published: photo_id={}", response.getPhotoId());
                return response.getPhotoId();
            }
            return "[快手发布失败] " + (response != null ? response.getError() : "未知错误");
        } catch (Exception e) {
            log.error("Failed to publish Kuaishou video", e);
            return "[快手发布异常] " + e.getMessage();
        }
    }

    /**
     * Get user public info (nickname, fans, follows, avatar).
     *
     * @param accessToken OAuth access token
     * @return formatted user info
     */
    public String getUserInfo(String accessToken) {
        if (!isAvailable()) {
            return "[快手数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            UserInfoResponse response = restClient.get()
                    .uri("/openapi/user_info?app_id=" + config.getAppId()
                            + "&access_token=" + accessToken)
                    .retrieve()
                    .body(UserInfoResponse.class);

            return formatUserInfo(response);
        } catch (Exception e) {
            log.error("Failed to get Kuaishou user info", e);
            return "[快手数据获取失败] " + e.getMessage();
        }
    }

    /**
     * Query the user's video list with statistics.
     *
     * @param accessToken OAuth access token
     * @param page         page number (starts from 1)
     * @param count        items per page (max 20)
     * @return formatted video list with stats
     */
    public String queryVideoList(String accessToken, int page, int count) {
        if (!isAvailable()) {
            return "[快手数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            VideoListResponse response = restClient.get()
                    .uri("/openapi/photo/list?app_id=" + config.getAppId()
                            + "&access_token=" + accessToken
                            + "&page=" + page + "&count=" + Math.min(count, 20))
                    .retrieve()
                    .body(VideoListResponse.class);

            return formatVideoList(response);
        } catch (Exception e) {
            log.error("Failed to query Kuaishou video list", e);
            return "[快手数据获取失败] " + e.getMessage();
        }
    }

    /**
     * Query details of a single video including view count, likes, comments.
     *
     * @param accessToken OAuth access token
     * @param photoId      the video/photo ID
     * @return formatted video detail
     */
    public String queryVideoDetail(String accessToken, String photoId) {
        if (!isAvailable()) {
            return "[快手数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            VideoDetailResponse response = restClient.get()
                    .uri("/openapi/photo/info?app_id=" + config.getAppId()
                            + "&access_token=" + accessToken
                            + "&photo_id=" + photoId)
                    .retrieve()
                    .body(VideoDetailResponse.class);

            return formatVideoDetail(response, photoId);
        } catch (Exception e) {
            log.error("Failed to query Kuaishou video detail", e);
            return "[快手数据获取失败] " + e.getMessage();
        }
    }

    // ════════════════ Formatting ════════════════

    private String formatUserInfo(UserInfoResponse response) {
        if (response == null || response.getUserInfo() == null) {
            return "[快手用户信息] 无数据返回。";
        }
        UserInfo info = response.getUserInfo();
        StringBuilder sb = new StringBuilder();
        sb.append("[快手用户信息]\n");
        sb.append("- 昵称: ").append(info.getName()).append("\n");
        sb.append("- 性别: ").append(info.getSex() == 1 ? "男" : info.getSex() == 2 ? "女" : "未知").append("\n");
        sb.append("- 粉丝数: ").append(info.getFan()).append("\n");
        sb.append("- 关注数: ").append(info.getFollow()).append("\n");
        sb.append("- 地区: ").append(info.getCity() != null ? info.getCity() : "未知").append("\n");
        return sb.toString();
    }

    private String formatVideoList(VideoListResponse response) {
        if (response == null || response.getVideoList() == null || response.getVideoList().isEmpty()) {
            return "[快手视频列表] 无数据返回。";
        }
        StringBuilder sb = new StringBuilder();
        List<VideoItem> videos = response.getVideoList();
        sb.append("[快手视频列表] 共 ").append(videos.size()).append(" 条视频\n");
        for (VideoItem item : videos) {
            sb.append("- 标题: ").append(item.getCaption() != null ? item.getCaption() : "无标题").append("\n");
            sb.append("  photo_id: ").append(item.getPhotoId()).append("\n");
            sb.append("  创建时间: ").append(item.getCreateTime()).append("\n");
            sb.append("  播放: ").append(item.getViewCount());
            sb.append(", 点赞: ").append(item.getLikeCount());
            sb.append(", 评论: ").append(item.getCommentCount()).append("\n");
        }
        return sb.toString();
    }

    private String formatVideoDetail(VideoDetailResponse response, String photoId) {
        if (response == null || response.getVideoInfo() == null) {
            return "[快手视频详情] photo_id: " + photoId + "\n无数据返回。";
        }
        VideoInfo info = response.getVideoInfo();
        StringBuilder sb = new StringBuilder();
        sb.append("[快手视频详情] photo_id: ").append(photoId).append("\n");
        sb.append("- 标题: ").append(info.getCaption()).append("\n");
        sb.append("- 创建时间: ").append(info.getCreateTime()).append("\n");
        sb.append("- 播放量: ").append(info.getViewCount()).append("\n");
        sb.append("- 点赞数: ").append(info.getLikeCount()).append("\n");
        sb.append("- 评论数: ").append(info.getCommentCount()).append("\n");
        return sb.toString();
    }

    // ──────────────────── Kuaishou API Response DTOs ────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StartUploadResponse {
        @JsonProperty("result")
        private int result;
        @JsonProperty("upload_token")
        private String uploadToken;
        @JsonProperty("endpoint")
        private String endpoint;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PublishResponse {
        @JsonProperty("result")
        private int result;
        @JsonProperty("photo_id")
        private String photoId;
        @JsonProperty("error")
        private String error;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserInfoResponse {
        @JsonProperty("result")
        private int result;
        @JsonProperty("user_info")
        private UserInfo userInfo;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserInfo {
        @JsonProperty("name")
        private String name;
        @JsonProperty("sex")
        private int sex;
        @JsonProperty("fan")
        private long fan;
        @JsonProperty("follow")
        private long follow;
        @JsonProperty("head")
        private String head;
        @JsonProperty("city")
        private String city;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoListResponse {
        @JsonProperty("result")
        private int result;
        @JsonProperty("video_list")
        private List<VideoItem> videoList;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoItem {
        @JsonProperty("photo_id")
        private String photoId;
        @JsonProperty("caption")
        private String caption;
        @JsonProperty("create_time")
        private long createTime;
        @JsonProperty("view_count")
        private long viewCount;
        @JsonProperty("like_count")
        private long likeCount;
        @JsonProperty("comment_count")
        private long commentCount;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoDetailResponse {
        @JsonProperty("result")
        private int result;
        @JsonProperty("video_info")
        private VideoInfo videoInfo;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoInfo {
        @JsonProperty("photo_id")
        private String photoId;
        @JsonProperty("caption")
        private String caption;
        @JsonProperty("create_time")
        private long createTime;
        @JsonProperty("view_count")
        private long viewCount;
        @JsonProperty("like_count")
        private long likeCount;
        @JsonProperty("comment_count")
        private long commentCount;
    }
}
