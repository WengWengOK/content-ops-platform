package com.contentops.common.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WeChat Official Account (微信公众号) platform service.
 *
 * <p>Provides real API integration for:
 * <ul>
 *   <li><b>Publishing</b>: Upload permanent image material, add article to draft box</li>
 *   <li><b>Analytics</b>: Article read data, user summary, article total detail</li>
 * </ul>
 *
 * <p>API reference: https://developers.weixin.qq.com/doc/offiaccount/Analytics/Data_Analysis.html
 *
 * <p>When the platform is disabled or credentials are missing, all methods return
 * graceful fallback messages instead of throwing.
 */
@Slf4j
@Component
public class WechatPlatformService {

    private final PlatformApiProperties.WechatConfig config;
    private final RestClient restClient;
    private volatile String cachedAccessToken;
    private volatile long tokenExpiresAt;

    public WechatPlatformService(PlatformApiProperties properties) {
        this.config = properties.getWechat();
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .build();
        if (config.isEnabled()) {
            log.info("WechatPlatformService initialized: appId={}", config.getAppId());
        } else {
            log.info("WechatPlatformService disabled — set contentops.platform.wechat.enabled=true to enable.");
        }
    }

    public boolean isAvailable() {
        return config.isEnabled()
                && config.getAppId() != null && !config.getAppId().isBlank()
                && config.getAppSecret() != null && !config.getAppSecret().isBlank();
    }

    // ════════════════ Authentication ════════════════

    /**
     * Get the OAuth access token for the WeChat API (cached for ~2 hours).
     */
    private synchronized String getAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < tokenExpiresAt - 60_000) {
            return cachedAccessToken;
        }
        try {
            String url = String.format("/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s",
                    config.getAppId(), config.getAppSecret());
            TokenResponse response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response != null && response.getAccessToken() != null) {
                cachedAccessToken = response.getAccessToken();
                tokenExpiresAt = now + (response.getExpiresIn() - 120) * 1000L;
                log.info("WeChat access token acquired, expires in {}s", response.getExpiresIn());
                return cachedAccessToken;
            }
            log.error("Failed to get WeChat access token: {}", response);
            return null;
        } catch (Exception e) {
            log.error("Failed to get WeChat access token", e);
            return null;
        }
    }

    // ════════════════ Publishing ════════════════

    // 安全说明：WeChat API 要求 access_token 作为 URL 查询参数传递（平台 API 限制），
    // 无法使用 Authorization Header。已通过以下措施缓解风险：
    // 1. 使用 UriComponentsBuilder 构建 URI（替代字符串拼接），减少 token 暴露面
    // 2. access_token 不出现在日志中（TokenLogSanitizer 自动脱敏 access_token=xxx）
    // 3. MDC 日志过滤器仅记录 path，不记录 query 参数
    // 4. 生产环境建议通过 HTTPS 代理层过滤访问日志中的 token 参数
    // 5. token 有效期约 2 小时，过期自动刷新

    /**
     * Upload an image as permanent material and return the media_id.
     *
     * <p>P0 ④: Real implementation — downloads the image from the given URL,
     * then uploads it as multipart/form-data to the WeChat material API.
     * The returned media_id can be used as {@code thumb_media_id} in {@link #addToDraft}.
     *
     * @param imageUrl URL of the image to upload (e.g. DALL-E generated image URL or any public image URL)
     * @return media_id of the uploaded material, or null on failure
     */
    public String uploadPermanentMaterial(String imageUrl) {
        if (!isAvailable()) return null;
        String token = getAccessToken();
        if (token == null) return null;
        if (imageUrl == null || imageUrl.isBlank()) {
            log.warn("uploadPermanentMaterial called with empty imageUrl");
            return null;
        }
        try {
            // Step 1: Download the image bytes from the source URL
            log.info("Downloading image for WeChat material upload: {}", imageUrl);
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> downloadResponse = httpClient.send(
                    downloadRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (downloadResponse.statusCode() != 200) {
                log.error("Failed to download image: HTTP {} for URL {}",
                        downloadResponse.statusCode(), imageUrl);
                return null;
            }
            byte[] imageBytes = downloadResponse.body();
            if (imageBytes == null || imageBytes.length == 0) {
                log.error("Downloaded image is empty: {}", imageUrl);
                return null;
            }
            String contentType = downloadResponse.headers()
                    .firstValue("Content-Type")
                    .orElse("image/jpeg");
            String filename = extractFilename(imageUrl, contentType);
            log.info("Image downloaded: {} bytes, type={}, filename={}",
                    imageBytes.length, contentType, filename);

            // Step 2: Wrap image bytes in a named ByteArrayResource
            ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            // Step 3: Build multipart request
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("media", imageResource, MediaType.parseMediaType(contentType));
            MultiValueMap<String, HttpEntity<?>> parts = builder.build();

            // Step 4: Upload to WeChat permanent material API
            URI uploadUri = UriComponentsBuilder.fromPath("/cgi-bin/material/add_material")
                    .queryParam("access_token", token)
                    .queryParam("type", "image")
                    .build().toUri();
            MediaResponse response = restClient.post()
                    .uri(uploadUri)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(MediaResponse.class);

            if (response != null && response.getMediaId() != null) {
                log.info("WeChat permanent material uploaded: media_id={}, url={}",
                        response.getMediaId(), response.getUrl());
                return response.getMediaId();
            }

            log.error("Failed to upload WeChat material: errcode={}, errmsg={}",
                    response != null ? response.getErrcode() : -1,
                    response != null ? response.getErrmsg() : "null response");
            return null;
        } catch (Exception e) {
            log.error("Failed to upload permanent material for image: {}", imageUrl, e);
            return null;
        }
    }

    /**
     * Convenience method: upload a cover image and add the article to the WeChat draft box
     * in a single call. This is the primary entry point for P0 ④ publishing flow.
     *
     * <p>If {@code coverImageUrl} is provided, it will be uploaded as a permanent material
     * first to obtain the {@code thumb_media_id}. If the upload fails or no URL is given,
     * the article is still added to the draft box without a cover image.
     *
     * @param title         article title (max 64 chars)
     * @param htmlContent   article content in HTML format (from MarkdownConverter)
     * @param coverImageUrl cover image URL (e.g. DALL-E generated URL), may be null
     * @param digest        article summary (max 120 chars)
     * @param author        author name
     * @return the draft media_id, or error message prefixed with [微信...]
     */
    public String publishArticleWithCover(String title, String htmlContent,
                                          String coverImageUrl, String digest, String author) {
        if (!isAvailable()) {
            return "[微信发布不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }

        // Upload cover image if provided
        String thumbMediaId = null;
        if (coverImageUrl != null && !coverImageUrl.isBlank()) {
            thumbMediaId = uploadPermanentMaterial(coverImageUrl);
            if (thumbMediaId == null) {
                log.warn("Cover image upload failed, proceeding without cover: {}", coverImageUrl);
            }
        }

        // Add article to draft box
        String result = addToDraft(title, htmlContent, thumbMediaId, digest, author);

        // Enhance success message with cover info
        if (result != null && !result.startsWith("[") && !result.startsWith("{")) {
            String coverInfo = thumbMediaId != null
                    ? "封面图已上传 (media_id: " + thumbMediaId + ")"
                    : "未上传封面图（无封面URL或上传失败）";
            return result + "\n" + coverInfo;
        }
        return result;
    }

    /**
     * Extract a sensible filename from a URL or generate one from the content type.
     */
    private String extractFilename(String imageUrl, String contentType) {
        String filename = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
        if (filename.contains("?")) {
            filename = filename.substring(0, filename.indexOf("?"));
        }
        if (filename.isEmpty() || !filename.contains(".")) {
            String ext = switch (contentType.toLowerCase()) {
                case "image/png" -> ".png";
                case "image/gif" -> ".gif";
                case "image/webp" -> ".webp";
                case "image/bmp" -> ".bmp";
                default -> ".jpg";
            };
            filename = "cover_image_" + System.currentTimeMillis() + ext;
        }
        return filename;
    }

    /**
     * Add an article to the WeChat draft box (草稿箱).
     *
     * @param title         article title
     * @param content       article content in HTML format
     * @param thumbMediaId  cover image media_id (from uploadPermanentMaterial)
     * @param digest        article summary (max 120 chars)
     * @param author        author name
     * @return media_id of the draft, or error message
     */
    public String addToDraft(String title, String content, String thumbMediaId, String digest, String author) {
        if (!isAvailable()) {
            return "[微信发布不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        String token = getAccessToken();
        if (token == null) {
            return "[微信发布失败] 无法获取 access_token，请检查 AppID/AppSecret 和 IP 白名单。";
        }
        try {
            Map<String, Object> article = new HashMap<>();
            article.put("title", title);
            article.put("thumb_media_id", thumbMediaId != null ? thumbMediaId : "");
            article.put("author", author != null ? author : "");
            article.put("digest", digest != null ? digest.substring(0, Math.min(digest.length(), 120)) : "");
            article.put("show_cover_pic", 1);
            article.put("content", content);
            article.put("content_source_url", "");
            article.put("need_open_comment", 1);
            article.put("only_fans_can_comment", 0);

            Map<String, Object> body = new HashMap<>();
            body.put("articles", List.of(article));

            DraftResponse response = restClient.post()
                    .uri(UriComponentsBuilder.fromPath("/cgi-bin/draft/add")
                            .queryParam("access_token", token)
                            .build().toUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(DraftResponse.class);

            if (response != null && response.getMediaId() != null) {
                log.info("WeChat draft created: media_id={}", response.getMediaId());
                return response.getMediaId();
            }
            return "[微信发布失败] " + (response != null ? response.getErrmsg() : "未知错误");
        } catch (Exception e) {
            log.error("Failed to add WeChat draft", e);
            return "[微信发布异常] " + e.getMessage();
        }
    }

    // ════════════════ Analytics ════════════════

    /**
     * Get article read data for a specific date.
     *
     * @param date the date to query (format: yyyy-MM-dd)
     * @return formatted article read statistics
     */
    public String getArticleReadData(String date) {
        if (!isAvailable()) {
            return "[微信数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        String token = getAccessToken();
        if (token == null) return "[微信数据失败] 无法获取 access_token。";

        String queryDate = (date != null && !date.isBlank()) ? date :
                LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("begin_date", queryDate);
            body.put("end_date", queryDate);

            ArticleReadResponse response = restClient.post()
                    .uri(UriComponentsBuilder.fromPath("/datacube/getarticleread")
                            .queryParam("access_token", token)
                            .build().toUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ArticleReadResponse.class);

            return formatArticleReadData(response, queryDate);
        } catch (Exception e) {
            log.error("Failed to get WeChat article read data", e);
            return "[微信数据获取失败] " + e.getMessage();
        }
    }

    /**
     * Get user summary data (new/cancel/cumulative users) for a date range.
     *
     * @param beginDate start date (yyyy-MM-dd)
     * @param endDate   end date (yyyy-MM-dd), max 7 days span
     * @return formatted user summary
     */
    public String getUserSummary(String beginDate, String endDate) {
        if (!isAvailable()) {
            return "[微信数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        String token = getAccessToken();
        if (token == null) return "[微信数据失败] 无法获取 access_token。";

        String begin = (beginDate != null && !beginDate.isBlank()) ? beginDate :
                LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String end = (endDate != null && !endDate.isBlank()) ? endDate :
                LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("begin_date", begin);
            body.put("end_date", end);

            UserSummaryResponse response = restClient.post()
                    .uri(UriComponentsBuilder.fromPath("/datacube/getusersummary")
                            .queryParam("access_token", token)
                            .build().toUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(UserSummaryResponse.class);

            return formatUserSummary(response, begin, end);
        } catch (Exception e) {
            log.error("Failed to get WeChat user summary", e);
            return "[微信数据获取失败] " + e.getMessage();
        }
    }

    /**
     * Get article total detail data for a date range.
     *
     * @param beginDate start date (yyyy-MM-dd)
     * @param endDate   end date (yyyy-MM-dd), max 1 day span for this API
     * @return formatted article total detail
     */
    public String getArticleTotalDetail(String beginDate, String endDate) {
        if (!isAvailable()) {
            return "[微信数据不可用] 未配置 AppID/AppSecret 或平台未启用。";
        }
        String token = getAccessToken();
        if (token == null) return "[微信数据失败] 无法获取 access_token。";

        String begin = (beginDate != null && !beginDate.isBlank()) ? beginDate :
                LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String end = (endDate != null && !endDate.isBlank()) ? endDate : begin;

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("begin_date", begin);
            body.put("end_date", end);

            ArticleTotalDetailResponse response = restClient.post()
                    .uri(UriComponentsBuilder.fromPath("/datacube/getarticletotaldetail")
                            .queryParam("access_token", token)
                            .build().toUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ArticleTotalDetailResponse.class);

            return formatArticleTotalDetail(response, begin, end);
        } catch (Exception e) {
            log.error("Failed to get WeChat article total detail", e);
            return "[微信数据获取失败] " + e.getMessage();
        }
    }

    // ════════════════ Formatting ════════════════

    private String formatArticleReadData(ArticleReadResponse response, String date) {
        if (response == null || response.getList() == null || response.getList().isEmpty()) {
            return "[微信图文阅读数据] 日期: " + date + "\n无数据返回（可能当天无阅读或数据尚未生成）。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[微信图文阅读数据] 日期: ").append(date).append("\n");
        int totalReadUser = 0, totalReadCount = 0, totalShareUser = 0, totalShareCount = 0;
        for (ArticleItem item : response.getList()) {
            sb.append("- 标题: ").append(item.getTitle()).append("\n");
            sb.append("  阅读人数: ").append(item.getIntPageReadUser());
            sb.append(", 阅读次数: ").append(item.getIntPageReadCount());
            sb.append(", 分享人数: ").append(item.getShareUser());
            sb.append(", 分享次数: ").append(item.getShareCount()).append("\n");
            totalReadUser += item.getIntPageReadUser();
            totalReadCount += item.getIntPageReadCount();
            totalShareUser += item.getShareUser();
            totalShareCount += item.getShareCount();
        }
        sb.append("\n汇总: 阅读人数 ").append(totalReadUser);
        sb.append(", 阅读次数 ").append(totalReadCount);
        sb.append(", 分享人数 ").append(totalShareUser);
        sb.append(", 分享次数 ").append(totalShareCount);
        return sb.toString();
    }

    private String formatUserSummary(UserSummaryResponse response, String begin, String end) {
        if (response == null || response.getList() == null || response.getList().isEmpty()) {
            return "[微信用户数据] 日期范围: " + begin + " ~ " + end + "\n无数据返回。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[微信用户数据] 日期范围: ").append(begin).append(" ~ ").append(end).append("\n");
        int totalNew = 0, totalCancel = 0;
        for (UserItem item : response.getList()) {
            sb.append("- ").append(item.getRefDate());
            sb.append(" [").append(item.getUserSource() != null ? item.getUserSource() : "全部").append("]");
            sb.append(" 新增: ").append(item.getNewUser());
            sb.append(", 取消: ").append(item.getCancelUser()).append("\n");
            totalNew += item.getNewUser();
            totalCancel += item.getCancelUser();
        }
        sb.append("\n汇总: 净增粉丝 ").append(totalNew - totalCancel);
        sb.append(" (新增 ").append(totalNew).append(" - 取消 ").append(totalCancel).append(")");
        return sb.toString();
    }

    private String formatArticleTotalDetail(ArticleTotalDetailResponse response, String begin, String end) {
        if (response == null || response.getList() == null || response.getList().isEmpty()) {
            return "[微信图文详细数据] 日期范围: " + begin + " ~ " + end + "\n无数据返回。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[微信图文详细数据] 日期范围: ").append(begin).append(" ~ ").append(end).append("\n");
        for (ArticleEntry entry : response.getList()) {
            sb.append("- 标题: ").append(entry.getTitle() != null ? entry.getTitle() : "未知").append("\n");
            if (entry.getDetailList() != null && !entry.getDetailList().isEmpty()) {
                for (DetailItem item : entry.getDetailList()) {
                    sb.append("  统计日期: ").append(item.getStatDate()).append("\n");
                    sb.append("  阅读人数: ").append(item.getReadUser());
                    sb.append(", 分享人数: ").append(item.getShareUser());
                    sb.append(", 评论数: ").append(item.getCommentCount());
                    sb.append(", 收藏人数: ").append(item.getCollectionUser()).append("\n");
                    if (item.getReadFinishRate() > 0) {
                        sb.append("  阅读完成率: ").append(String.format("%.1f%%", item.getReadFinishRate() * 100));
                    }
                    if (item.getReadAvgActivetime() > 0) {
                        sb.append(", 平均阅读时长: ").append(String.format("%.1f分钟", item.getReadAvgActivetime()));
                    }
                    if (item.getReadDeliveryRate() > 0) {
                        sb.append(", 阅读送达率: ").append(String.format("%.2f%%", item.getReadDeliveryRate() * 100));
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ──────────────────── WeChat API Response DTOs ────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenResponse {
        @JsonProperty("access_token")
        private String accessToken;
        @JsonProperty("expires_in")
        private long expiresIn;
        @JsonProperty("errcode")
        private int errcode;
        @JsonProperty("errmsg")
        private String errmsg;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DraftResponse {
        @JsonProperty("media_id")
        private String mediaId;
        @JsonProperty("errcode")
        private int errcode;
        @JsonProperty("errmsg")
        private String errmsg;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaResponse {
        @JsonProperty("media_id")
        private String mediaId;
        /** WeChat CDN URL for the uploaded image (only returned for image materials) */
        @JsonProperty("url")
        private String url;
        @JsonProperty("errcode")
        private int errcode;
        @JsonProperty("errmsg")
        private String errmsg;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArticleReadResponse {
        @JsonProperty("list")
        private List<ArticleItem> list;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArticleItem {
        @JsonProperty("title")
        private String title;
        @JsonProperty("int_page_read_user")
        private int intPageReadUser;
        @JsonProperty("int_page_read_count")
        private int intPageReadCount;
        @JsonProperty("share_user")
        private int shareUser;
        @JsonProperty("share_count")
        private int shareCount;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserSummaryResponse {
        @JsonProperty("list")
        private List<UserItem> list;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserItem {
        @JsonProperty("ref_date")
        private String refDate;
        @JsonProperty("user_source")
        private String userSource;
        @JsonProperty("new_user")
        private int newUser;
        @JsonProperty("cancel_user")
        private int cancelUser;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArticleTotalDetailResponse {
        @JsonProperty("list")
        private List<ArticleEntry> list;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArticleEntry {
        @JsonProperty("ref_date")
        private String refDate;
        @JsonProperty("msgid")
        private String msgid;
        @JsonProperty("title")
        private String title;
        @JsonProperty("content_url")
        private String contentUrl;
        @JsonProperty("detail_list")
        private List<DetailItem> detailList;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DetailItem {
        @JsonProperty("stat_date")
        private String statDate;
        @JsonProperty("read_user")
        private long readUser;
        @JsonProperty("share_user")
        private long shareUser;
        @JsonProperty("comment_count")
        private long commentCount;
        @JsonProperty("collection_user")
        private long collectionUser;
        @JsonProperty("read_finish_rate")
        private double readFinishRate;
        @JsonProperty("read_avg_activetime")
        private double readAvgActivetime;
        @JsonProperty("read_delivery_rate")
        private double readDeliveryRate;
    }
}
