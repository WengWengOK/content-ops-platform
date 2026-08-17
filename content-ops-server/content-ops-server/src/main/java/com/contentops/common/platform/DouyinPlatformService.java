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
 * Douyin (抖音) open platform service.
 *
 * <p>Provides real API integration for:
 * <ul>
 *   <li><b>Publishing</b>: Upload images, create image-text posts</li>
 *   <li><b>Analytics</b>: Query video list and video detail statistics</li>
 * </ul>
 *
 * <p>API reference: https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/
 *
 * <p>Note: The "create image text" API ({@code /api/douyin/v1/video/create_image_text/})
 * requires the {@code video.create.bind} scope and is restricted to government/media
 * organizations. Ensure your app has the appropriate permissions before use.
 */
@Slf4j
@Component
public class DouyinPlatformService {

    private final PlatformApiProperties.DouyinConfig config;
    private final RestClient restClient;

    public DouyinPlatformService(PlatformApiProperties properties) {
        this.config = properties.getDouyin();
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .build();
        if (config.isEnabled()) {
            log.info("DouyinPlatformService initialized: clientKey={}", config.getClientKey());
        } else {
            log.info("DouyinPlatformService disabled — set contentops.platform.douyin.enabled=true to enable.");
        }
    }

    public boolean isAvailable() {
        return config.isEnabled()
                && config.getClientKey() != null && !config.getClientKey().isBlank()
                && config.getClientSecret() != null && !config.getClientSecret().isBlank();
    }

    /**
     * Upload an image to Douyin and return the image_id for use in createImageText.
     *
     * @param accessToken user OAuth access token
     * @param openId      user open_id
     * @param imageUrl    URL of the image to upload (or base64 data)
     * @return the image_id, or error message
     */
    public String uploadImage(String accessToken, String openId, String imageUrl) {
        if (!isAvailable()) {
            return "[抖音上传不可用] 未配置 ClientKey/ClientSecret 或平台未启用。";
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("image_url", imageUrl);

            UploadResponse response = restClient.post()
                    .uri("/api/douyin/v1/image/upload/?open_id=" + openId)
                    .header("access-token", accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(UploadResponse.class);

            if (response != null && response.getData() != null && response.getData().getImageId() != null) {
                log.info("Douyin image uploaded: image_id={}", response.getData().getImageId());
                return response.getData().getImageId();
            }
            return "[抖音上传失败] " + (response != null ? response.getExtraDescription() : "未知错误");
        } catch (Exception e) {
            log.error("Failed to upload Douyin image", e);
            return "[抖音上传异常] " + e.getMessage();
        }
    }

    /**
     * Create an image-text post (图文作品) on Douyin.
     *
     * @param accessToken user OAuth access token
     * @param openId      user open_id
     * @param text        post caption (can include #hashtags and @mentions)
     * @param imageIds    list of image_ids from uploadImage
     * @return the item_id of the created post, or error message
     */
    public String createImageText(String accessToken, String openId, String text, List<String> imageIds) {
        if (!isAvailable()) {
            return "[抖音发布不可用] 未配置 ClientKey/ClientSecret 或平台未启用。";
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("text", text);
            body.put("image_list", imageIds);

            CreateImageTextResponse response = restClient.post()
                    .uri("/api/douyin/v1/video/create_image_text/?open_id=" + openId)
                    .header("access-token", accessToken)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .retrieve()
                    .body(CreateImageTextResponse.class);

            if (response != null && response.getData() != null && response.getData().getItemId() != null) {
                log.info("Douyin image-text created: item_id={}", response.getData().getItemId());
                return response.getData().getItemId();
            }
            return "[抖音发布失败] " + (response != null ? response.getExtraDescription() : "未知错误");
        } catch (Exception e) {
            log.error("Failed to create Douyin image-text", e);
            return "[抖音发布异常] " + e.getMessage();
        }
    }

    /**
     * Query the user's video list with statistics.
     *
     * @param accessToken user OAuth access token
     * @param openId      user open_id
     * @param cursor      pagination cursor (0 for first page)
     * @param count       number of items per page (max 20)
     * @return formatted video list with stats
     */
    public String queryVideoList(String accessToken, String openId, int cursor, int count) {
        if (!isAvailable()) {
            return "[抖音数据不可用] 未配置 ClientKey/ClientSecret 或平台未启用。";
        }
        try {
            VideoListResponse response = restClient.get()
                    .uri("/api/douyin/v1/video/list/?open_id=" + openId
                            + "&cursor=" + cursor + "&count=" + Math.min(count, 20))
                    .header("access-token", accessToken)
                    .retrieve()
                    .body(VideoListResponse.class);

            return formatVideoList(response);
        } catch (Exception e) {
            log.error("Failed to query Douyin video list", e);
            return "[抖音数据获取失败] " + e.getMessage();
        }
    }

    private String formatVideoList(VideoListResponse response) {
        if (response == null || response.getData() == null || response.getData().getList() == null) {
            return "[抖音视频列表] 无数据返回。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[抖音视频列表] 共 ").append(response.getData().getList().size()).append(" 条视频\n");
        for (VideoItem item : response.getData().getList()) {
            sb.append("- 标题: ").append(item.getTitle()).append("\n");
            sb.append("  video_id: ").append(item.getVideoId()).append("\n");
            sb.append("  创建时间: ").append(item.getCreateTime()).append("\n");
            if (item.getStatistics() != null) {
                sb.append("  播放: ").append(item.getStatistics().getPlayCount());
                sb.append(", 点赞: ").append(item.getStatistics().getDiggCount());
                sb.append(", 评论: ").append(item.getStatistics().getCommentCount());
                sb.append(", 分享: ").append(item.getStatistics().getShareCount()).append("\n");
            }
        }
        return sb.toString();
    }

    // ──────────────────── Douyin API Response DTOs ────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UploadResponse {
        @JsonProperty("data")
        private UploadData data;
        @JsonProperty("extra")
        private Extra extra;
        public String getExtraDescription() { return extra != null ? extra.getDescription() : null; }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UploadData {
        @JsonProperty("image_id")
        private String imageId;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateImageTextResponse {
        @JsonProperty("data")
        private CreateData data;
        @JsonProperty("extra")
        private Extra extra;
        public String getExtraDescription() { return extra != null ? extra.getDescription() : null; }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateData {
        @JsonProperty("item_id")
        private String itemId;
        @JsonProperty("video_id")
        private String videoId;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Extra {
        @JsonProperty("description")
        private String description;
        @JsonProperty("error_code")
        private int errorCode;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoListResponse {
        @JsonProperty("data")
        private VideoListData data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoListData {
        @JsonProperty("list")
        private List<VideoItem> list;
        @JsonProperty("cursor")
        private long cursor;
        @JsonProperty("has_more")
        private boolean hasMore;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoItem {
        @JsonProperty("title")
        private String title;
        @JsonProperty("video_id")
        private String videoId;
        @JsonProperty("create_time")
        private long createTime;
        @JsonProperty("statistics")
        private Statistics statistics;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Statistics {
        @JsonProperty("play_count")
        private long playCount;
        @JsonProperty("digg_count")
        private long diggCount;
        @JsonProperty("comment_count")
        private long commentCount;
        @JsonProperty("share_count")
        private long shareCount;
    }
}
