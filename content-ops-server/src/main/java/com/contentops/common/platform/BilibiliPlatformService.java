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
 * Bilibili (哔哩哔哩) open platform service.
 *
 * <p>Provides real API integration for:
 * <ul>
 *   <li><b>Publishing</b>: Cover upload, video/article submission</li>
 *   <li><b>Analytics</b>: Video stats (views, likes, coins, favorites, shares)</li>
 * </ul>
 *
 * <p>API reference: https://open.bilibili.com/doc/
 *
 * <p>The Bilibili open platform uses OAuth2.0 authentication. After obtaining
 * an access_token, you can submit videos via the archive API and retrieve
 * video statistics via the data open service.
 */
@Slf4j
@Component
public class BilibiliPlatformService {

    private final PlatformApiProperties.BilibiliConfig config;
    private final RestClient restClient;

    public BilibiliPlatformService(PlatformApiProperties properties) {
        this.config = properties.getBilibili();
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .build();
        if (config.isEnabled()) {
            log.info("BilibiliPlatformService initialized: appId={}", config.getAppId());
        } else {
            log.info("BilibiliPlatformService disabled — set contentops.platform.bilibili.enabled=true to enable.");
        }
    }

    public boolean isAvailable() {
        return config.isEnabled()
                && config.getAppId() != null && !config.getAppId().isBlank()
                && config.getAppSecret() != null && !config.getAppSecret().isBlank();
    }

    /**
     * Query the video category (分区) list for submission.
     *
     * @param accessToken OAuth access token
     * @return formatted category list
     */
    public String queryCategoryList(String accessToken) {
        if (!isAvailable()) {
            return "[B站数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            CategoryListResponse response = restClient.get()
                    .uri("/openapi/x/v2/channel/list")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(CategoryListResponse.class);
            return formatCategoryList(response);
        } catch (Exception e) {
            log.error("Failed to query Bilibili category list", e);
            return "[B站数据获取失败] " + e.getMessage();
        }
    }

    /**
     * Get video statistics (views, likes, coins, etc.) for a specific video.
     *
     * @param accessToken OAuth access token
     * @param avid         the video AV number (without "av" prefix)
     * @return formatted video statistics
     */
    public String getVideoStats(String accessToken, String avid) {
        if (!isAvailable()) {
            return "[B站数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("access_token", accessToken);
            body.put("avid", avid);

            VideoStatsResponse response = restClient.post()
                    .uri("/openapi/x/v2/archive/stat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(VideoStatsResponse.class);

            return formatVideoStats(response, avid);
        } catch (Exception e) {
            log.error("Failed to get Bilibili video stats", e);
            return "[B站数据获取失败] " + e.getMessage();
        }
    }

    /**
     * Submit a video draft (稿件投递) to Bilibili.
     *
     * <p>This requires the video file to be uploaded first via the file upload
     * API, then the resulting filename is used in the submission.
     *
     * @param accessToken OAuth access token
     * @param title        video title
     * @param desc         video description
     * @param typeId       category (分区) ID
     * @param tag          tags (comma-separated, max 10)
     * @param coverUrl     cover image URL
     * @param videoFilename uploaded video filename
     * @return the bvid of the submitted video, or error message
     */
    public String submitVideo(String accessToken, String title, String desc,
                              int typeId, String tag, String coverUrl, String videoFilename) {
        if (!isAvailable()) {
            return "[B站发布不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            Map<String, Object> videos = new HashMap<>();
            videos.put("filename", videoFilename);
            videos.put("title", title);

            Map<String, Object> body = new HashMap<>();
            body.put("access_token", accessToken);
            body.put("videos", List.of(videos));
            body.put("title", title);
            body.put("copyright", 1); // 1=自制, 2=转载
            body.put("tid", typeId);
            body.put("tag", tag);
            body.put("desc", desc);
            if (coverUrl != null && !coverUrl.isBlank()) {
                body.put("cover", coverUrl);
            }

            SubmitVideoResponse response = restClient.post()
                    .uri("/openapi/x/v2/archive/add")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(SubmitVideoResponse.class);

            if (response != null && response.getData() != null && response.getData().getBvid() != null) {
                log.info("Bilibili video submitted: bvid={}", response.getData().getBvid());
                return response.getData().getBvid();
            }
            return "[B站发布失败] " + (response != null ? response.getMessage() : "未知错误");
        } catch (Exception e) {
            log.error("Failed to submit Bilibili video", e);
            return "[B站发布异常] " + e.getMessage();
        }
    }

    /**
     * Upload a cover image for a video.
     *
     * @param accessToken OAuth access token
     * @param coverBase64  base64-encoded image data
     * @return the cover URL, or error message
     */
    public String uploadCover(String accessToken, String coverBase64) {
        if (!isAvailable()) {
            return "[B站封面上传不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("access_token", accessToken);
            body.put("cover", coverBase64);

            CoverUploadResponse response = restClient.post()
                    .uri("/openapi/x/v2/cover/upload")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(CoverUploadResponse.class);

            if (response != null && response.getData() != null && response.getData().getUrl() != null) {
                log.info("Bilibili cover uploaded: url={}", response.getData().getUrl());
                return response.getData().getUrl();
            }
            return "[B站封面上传失败] " + (response != null ? response.getMessage() : "未知错误");
        } catch (Exception e) {
            log.error("Failed to upload Bilibili cover", e);
            return "[B站封面上传异常] " + e.getMessage();
        }
    }

    private String formatCategoryList(CategoryListResponse response) {
        if (response == null || response.getData() == null || response.getData().getList() == null) {
            return "[B站分区列表] 无数据返回。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[B站分区列表]\n");
        for (CategoryItem item : response.getData().getList()) {
            sb.append("- [").append(item.getTid()).append("] ").append(item.getName());
            if (item.getChildren() != null) {
                sb.append(" (子分区: ");
                for (CategoryItem child : item.getChildren()) {
                    sb.append(child.getName()).append("(").append(child.getTid()).append(") ");
                }
                sb.append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatVideoStats(VideoStatsResponse response, String avid) {
        if (response == null || response.getData() == null) {
            return "[B站视频统计] avid: " + avid + "\n无数据返回。";
        }
        StatsData data = response.getData();
        StringBuilder sb = new StringBuilder();
        sb.append("[B站视频统计] avid: ").append(avid).append("\n");
        sb.append("- 播放量: ").append(data.getView()).append("\n");
        sb.append("- 弹幕数: ").append(data.getDanmaku()).append("\n");
        sb.append("- 点赞数: ").append(data.getLike()).append("\n");
        sb.append("- 投币数: ").append(data.getCoin()).append("\n");
        sb.append("- 收藏数: ").append(data.getFavorite()).append("\n");
        sb.append("- 分享数: ").append(data.getShare()).append("\n");
        sb.append("- 回复数: ").append(data.getReply()).append("\n");
        return sb.toString();
    }

    // ──────────────────── Bilibili API Response DTOs ────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryListResponse {
        @JsonProperty("code")
        private int code;
        @JsonProperty("message")
        private String message;
        @JsonProperty("data")
        private CategoryData data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryData {
        @JsonProperty("list")
        private List<CategoryItem> list;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryItem {
        @JsonProperty("tid")
        private int tid;
        @JsonProperty("name")
        private String name;
        @JsonProperty("children")
        private List<CategoryItem> children;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoStatsResponse {
        @JsonProperty("code")
        private int code;
        @JsonProperty("message")
        private String message;
        @JsonProperty("data")
        private StatsData data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatsData {
        @JsonProperty("view")
        private long view;
        @JsonProperty("danmaku")
        private long danmaku;
        @JsonProperty("reply")
        private long reply;
        @JsonProperty("favorite")
        private long favorite;
        @JsonProperty("coin")
        private long coin;
        @JsonProperty("share")
        private long share;
        @JsonProperty("like")
        private long like;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubmitVideoResponse {
        @JsonProperty("code")
        private int code;
        @JsonProperty("message")
        private String message;
        @JsonProperty("data")
        private SubmitData data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubmitData {
        @JsonProperty("bvid")
        private String bvid;
        @JsonProperty("aid")
        private long aid;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CoverUploadResponse {
        @JsonProperty("code")
        private int code;
        @JsonProperty("message")
        private String message;
        @JsonProperty("data")
        private CoverData data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CoverData {
        @JsonProperty("url")
        private String url;
    }
}
