package com.contentops.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小红书评论接口客户端（真实数据源）。
 *
 * <p>把「作品 ID + 分页游标」发到配置好的评论接口，并把响应解析成 {@link XhsComment} 列表。
 * 设计目标是<b>换数据服务商不改代码</b>：
 * <ul>
 *   <li>端点/方法可配置（{@code endpoint} 支持完整 URL 或相对 {@code base-url} 的路径）；</li>
 *   <li>鉴权可配置：{@code bearer}（Authorization 头）、{@code query}（access_token 参数）、
 *       {@code ark-sign}（开放平台网关：appId+timestamp+secret 的 MD5 签名）、{@code none}；</li>
 *   <li>响应容错：{@code data.list} / {@code data.comments} / {@code data.items} 等常见结构都能解析，
 *       字段名支持多别名（comment_id|id、user_name|nickname|author 等）。</li>
 * </ul>
 *
 * @see CommentProperties.XiaohongshuProperties
 */
@Slf4j
@Component
public class XhsCommentApiClient {

    private static final List<String> LIST_PATHS = List.of(
            "data.list", "data.comments", "data.items", "data.records", "data.data",
            "result.list", "result.comments", "list", "comments", "items", "records", "data");

    private static final List<String> CURSOR_PATHS = List.of(
            "data.cursor", "data.next_cursor", "data.nextCursor", "cursor",
            "next_cursor", "data.page_token", "page_token");

    private static final DateTimeFormatter SPACE_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CommentProperties properties;
    private final ObjectMapper objectMapper;
    private final ClientHttpRequestFactory requestFactory;

    @Autowired
    public XhsCommentApiClient(CommentProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    /** 供测试注入自定义请求工厂（MockClientHttpRequestFactory 等）。 */
    XhsCommentApiClient(CommentProperties properties, ObjectMapper objectMapper,
                        ClientHttpRequestFactory requestFactory) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.requestFactory = requestFactory;
    }

    public CommentProperties.XiaohongshuProperties config() {
        return properties.getXiaohongshu();
    }

    /** 接口是否已配置可用（enabled + endpoint + 对应鉴权凭据齐备）。 */
    public boolean isConfigured() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!c.isEnabled() || isBlank(c.getEndpoint())) {
            return false;
        }
        return switch (normalizeAuth(c.getAuthMode())) {
            case "none" -> true;
            case "ark-sign" -> !isBlank(c.getAppId()) && !isBlank(c.getAppSecret());
            case "query", "bearer" -> !isBlank(c.getAccessToken());
            default -> false;
        };
    }

    /** 配置诊断提示（接口返回/前端可直接展示）。 */
    public String statusHint() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!c.isEnabled()) {
            return "真实评论接口未启用：contentops.comment.xiaohongshu.enabled=false";
        }
        if (isBlank(c.getEndpoint())) {
            return "未配置评论接口地址：contentops.comment.xiaohongshu.endpoint";
        }
        if (!isConfigured()) {
            return "缺少鉴权凭据：bearer/query 需 access-token；ark-sign 需 app-id + app-secret";
        }
        return "真实接口已配置：" + c.getMethod().toUpperCase() + " " + c.getEndpoint()
                + "（鉴权 " + normalizeAuth(c.getAuthMode()) + "）";
    }

    /**
     * 拉取一页评论。
     *
     * @param workId 作品（笔记）ID
     * @param cursor 分页游标，首页传 null/空串
     * @param limit  期望条数（会被 pageSize 上限裁剪）
     */
    public FetchPage fetchPage(String workId, String cursor, int limit) {
        CommentProperties.XiaohongshuProperties c = config();
        if (isBlank(workId)) {
            throw new CommentSourceException("COMMENT_WORK_ID_REQUIRED", "workId（笔记 ID）不能为空");
        }
        if (!isConfigured()) {
            throw new CommentSourceException("COMMENT_SOURCE_NOT_CONFIGURED", statusHint());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put(c.getNoteIdParam(), workId);
        if (!isBlank(c.getCursorParam())) {
            params.put(c.getCursorParam(), cursor == null ? "" : cursor);
        }
        if (!isBlank(c.getPageSizeParam())) {
            int pageSize = Math.max(1, Math.min(limit <= 0 ? c.getPageSize() : limit, c.getPageSize()));
            params.put(c.getPageSizeParam(), pageSize);
        }

        String auth = normalizeAuth(c.getAuthMode());
        if ("query".equals(auth)) {
            params.put("access_token", c.getAccessToken());
        }
        if ("ark-sign".equals(auth)) {
            long timestamp = Instant.now().getEpochSecond();
            params.put("app_id", c.getAppId());
            params.put("timestamp", String.valueOf(timestamp));
            params.put("version", "3.0");
            if (!isBlank(c.getMethodName())) {
                params.put("method", c.getMethodName());
            }
            params.put("sign", sign(c.getAppId(), timestamp, c.getAppSecret()));
        }

        try {
            RestClient client = buildClient();
            String authorization = authorizationHeader(auth);
            String raw;
            if ("GET".equalsIgnoreCase(c.getMethod())) {
                var spec = client.method(HttpMethod.GET)
                        .uri(uriForGet(params))
                        .accept(MediaType.APPLICATION_JSON);
                if (!isBlank(authorization)) {
                    spec = spec.header("Authorization", authorization);
                }
                raw = spec.retrieve().body(String.class);
            } else {
                var spec = client.post()
                        .uri(fullUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON);
                if (!isBlank(authorization)) {
                    spec = spec.header("Authorization", authorization);
                }
                raw = spec.body(params).retrieve().body(String.class);
            }
            FetchPage page = parse(raw);
            log.debug("[Comment] 拉取评论页: workId={}, cursor={}, 条数={}, hasMore={}",
                    workId, cursor, page.comments().size(), page.hasMore());
            return page;
        } catch (RestClientResponseException e) {
            throw new CommentSourceException(httpErrorCode(e.getStatusCode().value()),
                    describeHttpError(e, workId), e);
        } catch (CommentSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new CommentSourceException("COMMENT_SOURCE_IO_ERROR",
                    "调用评论接口失败: " + e.getMessage(), e);
        }
    }

    /** 解析接口响应为评论页（容错多路径/多字段别名）。 */
    public FetchPage parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new FetchPage(List.of(), null, false);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new CommentSourceException("COMMENT_SOURCE_BAD_JSON",
                    "评论接口返回内容无法解析为 JSON: " + preview(raw), e);
        }
        String bizError = businessError(root);
        if (bizError != null) {
            throw new CommentSourceException("COMMENT_SOURCE_BIZ_ERROR", bizError);
        }
        JsonNode array = locateList(root);
        if (array == null || !array.isArray()) {
            return new FetchPage(List.of(), cursorOf(root), false);
        }
        List<XhsComment> comments = new ArrayList<>();
        for (JsonNode item : array) {
            XhsComment mapped = mapComment(item);
            if (mapped != null) {
                comments.add(mapped);
            }
        }
        String nextCursor = cursorOf(root);
        boolean hasMore = booleanOf(root, List.of("data.has_more", "data.hasMore", "has_more",
                "data.hasMoreData", "hasMore", "data.next_page"));
        if (!hasMore && !isBlank(nextCursor) && !"0".equals(nextCursor)) {
            hasMore = true;
        }
        return new FetchPage(comments, nextCursor, hasMore);
    }

    private XhsComment mapComment(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String content = textOf(node, List.of("content", "text", "comment_content",
                "note_comment", "comment"));
        if (isBlank(content)) {
            return null;
        }
        String id = textOf(node, List.of("comment_id", "id", "commentId", "note_comment_id", "cid"));
        String author = textOf(node, List.of("user_name", "nickname", "nick_name", "author",
                "userName", "user_nickname"));
        Integer likes = intOf(node, List.of("like_count", "likes", "like_num", "likeCount",
                "praise_count"));
        LocalDateTime time = timeOf(node, List.of("create_time", "created_at", "comment_time",
                "time", "createTime", "gmt_create"));
        String replyTo = textOf(node, List.of("parent_comment_id", "reply_to", "target_comment_id",
                "parent_id", "reply_comment_id"));
        return new XhsComment(id, author, content, likes == null ? 0 : likes, time, replyTo);
    }

    private String businessError(JsonNode root) {
        if (root == null) {
            return null;
        }
        Integer code = intOf(root, List.of("code", "errCode", "err_code", "status_code", "status"));
        boolean success = !root.has("success") || root.path("success").asBoolean(true);
        String msg = textOf(root, List.of("msg", "message", "error_msg", "errMsg",
                "error_message", "error"));
        if (!success) {
            return "评论接口返回失败" + (code == null ? "" : "（code=" + code + "）")
                    + (isBlank(msg) ? "" : "：" + msg);
        }
        if (code != null && code != 0 && code != 200) {
            return "评论接口返回业务错误（code=" + code + "）" + (isBlank(msg) ? "" : "：" + msg);
        }
        return null;
    }

    private JsonNode locateList(JsonNode root) {
        String configured = config().getListPath();
        if (!isBlank(configured)) {
            JsonNode node = descend(root, configured);
            if (node != null && node.isArray()) {
                return node;
            }
            log.warn("[Comment] 配置的 list-path 未命中数组: {}", configured);
        }
        if (root.isArray()) {
            return root;
        }
        for (String path : LIST_PATHS) {
            JsonNode node = descend(root, path);
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private String cursorOf(JsonNode root) {
        return textOf(root, CURSOR_PATHS);
    }

    private JsonNode descend(JsonNode root, String path) {
        if (root == null || isBlank(path)) {
            return null;
        }
        JsonNode node = root;
        for (String segment : path.split("\\.")) {
            if (node == null) {
                return null;
            }
            if (node.isArray() && segment.matches("\\d+")) {
                node = node.path(Integer.parseInt(segment));
            } else if (node.isObject()) {
                node = node.path(segment);
            } else {
                return null;
            }
        }
        return node == null || node.isMissingNode() || node.isNull() ? null : node;
    }

    private String textOf(JsonNode node, List<String> paths) {
        for (String path : paths) {
            JsonNode v = descend(node, path);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private Integer intOf(JsonNode node, List<String> paths) {
        for (String path : paths) {
            JsonNode v = descend(node, path);
            if (v == null) {
                continue;
            }
            if (v.isNumber()) {
                return v.asInt();
            }
            if (v.isTextual() && v.asText().trim().matches("-?\\d+")) {
                return Integer.parseInt(v.asText().trim());
            }
        }
        return null;
    }

    private boolean booleanOf(JsonNode node, List<String> paths) {
        for (String path : paths) {
            JsonNode v = descend(node, path);
            if (v == null) {
                continue;
            }
            if (v.isBoolean()) {
                return v.asBoolean();
            }
            if (v.isNumber()) {
                return v.asInt() != 0;
            }
            if (v.isTextual()) {
                String s = v.asText().trim().toLowerCase();
                if (List.of("true", "1", "yes").contains(s)) {
                    return true;
                }
                if (List.of("false", "0", "no").contains(s)) {
                    return false;
                }
            }
        }
        return false;
    }

    private LocalDateTime timeOf(JsonNode node, List<String> paths) {
        for (String path : paths) {
            JsonNode v = descend(node, path);
            if (v == null) {
                continue;
            }
            if (v.isNumber()) {
                return fromEpoch(v.asLong());
            }
            String s = v.asText("").trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.matches("\\d{10,13}")) {
                return fromEpoch(Long.parseLong(s));
            }
            String iso = s.replace(" ", "T").replace("Z", "");
            try {
                return LocalDateTime.parse(iso.substring(0, Math.min(19, iso.length())));
            } catch (Exception ignored) {
                try {
                    return LocalDateTime.parse(s, SPACE_DATE_TIME);
                } catch (Exception ignoredToo) {
                    log.debug("[Comment] 无法解析评论时间: {}", s);
                }
            }
        }
        return null;
    }

    private LocalDateTime fromEpoch(long value) {
        long millis = value < 100_000_000_000L ? value * 1000L : value;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private String sign(String appId, long timestamp, String appSecret) {
        String raw = appId + timestamp + appSecret;
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8)).toUpperCase();
    }

    private RestClient buildClient() {
        CommentProperties.XiaohongshuProperties c = config();
        ClientHttpRequestFactory factory = requestFactory != null
                ? requestFactory
                : defaultFactory(c.getTimeoutMs());
        // baseUrl 仅作占位，真实地址由 fullUrl() 提供，便于 endpoint 直接写完整 URL
        return RestClient.builder().baseUrl("http://localhost").requestFactory(factory).build();
    }

    private URI uriForGet(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(fullUrl());
        boolean first = !fullUrl().contains("?");
        for (Map.Entry<String, Object> e : params.entrySet()) {
            sb.append(first ? "?" : "&");
            first = false;
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(String.valueOf(e.getValue())));
        }
        return URI.create(sb.toString());
    }

    private String fullUrl() {
        CommentProperties.XiaohongshuProperties c = config();
        String endpoint = c.getEndpoint() == null ? "" : c.getEndpoint().trim();
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint;
        }
        String base = c.getBaseUrl() == null ? "" : c.getBaseUrl().trim();
        if (base.endsWith("/") && endpoint.startsWith("/")) {
            return base.substring(0, base.length() - 1) + endpoint;
        }
        if (!base.endsWith("/") && !endpoint.startsWith("/") && !endpoint.isEmpty()) {
            return base + "/" + endpoint;
        }
        return base + endpoint;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private SimpleClientHttpRequestFactory defaultFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int t = timeoutMs <= 0 ? 20_000 : timeoutMs;
        factory.setConnectTimeout(Duration.ofMillis(t));
        factory.setReadTimeout(Duration.ofMillis(t));
        return factory;
    }

    private String authorizationHeader(String auth) {
        return "bearer".equals(auth) ? "Bearer " + config().getAccessToken() : "";
    }

    private String normalizeAuth(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase();
        if (m.isEmpty()) {
            return "bearer";
        }
        return switch (m) {
            case "query", "query-token", "token-query" -> "query";
            case "ark-sign", "ark_sign", "sign", "signature" -> "ark-sign";
            case "none", "no-auth" -> "none";
            default -> "bearer";
        };
    }

    private String httpErrorCode(int status) {
        return switch (status) {
            case 401 -> "COMMENT_SOURCE_UNAUTHORIZED";
            case 403 -> "COMMENT_SOURCE_FORBIDDEN";
            case 404 -> "COMMENT_SOURCE_NOT_FOUND";
            case 429 -> "COMMENT_SOURCE_RATE_LIMITED";
            default -> "COMMENT_SOURCE_HTTP_" + status;
        };
    }

    private String describeHttpError(RestClientResponseException e, String workId) {
        int status = e.getStatusCode().value();
        String body = preview(e.getResponseBodyAsString());
        String hint = switch (status) {
            case 401 -> "鉴权失败：请检查 access-token / app-id+app-secret 是否正确或已过期";
            case 403 -> "无权限：该接口需平台开通权限（小红书官方开放平台当前未开放笔记评论 API）";
            case 404 -> "接口不存在：请确认 endpoint 路径";
            case 429 -> "触发平台限流：请调大 collect-ms 或降低 page-size";
            default -> "请检查 endpoint 与网络连通性";
        };
        return "调用评论接口失败（HTTP " + status + "，workId=" + workId + "）：" + hint
                + (isBlank(body) ? "" : "；响应=" + body);
    }

    private String preview(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 一页评论数据。 */
    public record FetchPage(List<XhsComment> comments, String nextCursor, boolean hasMore) {
    }

    /** 单条评论（数据源无关的中间结构）。 */
    public record XhsComment(String commentId, String author, String content, int likes,
                             LocalDateTime commentTime, String replyTo) {
    }
}