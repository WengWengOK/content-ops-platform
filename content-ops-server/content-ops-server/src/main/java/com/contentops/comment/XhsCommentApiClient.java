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
 * <p>把「作品 ID（+ 可选 xsec_token）」发到配置好的评论接口，并把响应解析成 {@link XhsComment}。
 * 设计目标：<b>换数据源只改配置，不改代码</b>。
 *
 * <h3>内置预设</h3>
 * <ul>
 *   <li>{@code preset=xhs-mcp}：自建桥 <a href="https://github.com/xpzouying/xiaohongshu-mcp">xiaohongshu-mcp</a>，
 *       请求 {@code POST /api/v1/feeds/detail}，体为
 *       {@code {"feed_id":..,"xsec_token":..,"load_all_comments":true,"comment_config":{"max_comment_items":N}}}，
 *       响应 {@code {"success":true,"data":{"data":{"comments":{"list":[{"id","content","likeCount","createTime","userInfo":{"nickname"},"subComments":[...]}}]}}}}；</li>
 *   <li>{@code preset=custom}（默认）：自定义 endpoint，鉴权 {@code bearer}/{@code query}/{@code ark-sign}/{@code none}。</li>
 * </ul>
 *
 * <h3>容错能力</h3>
 * <ul>
 *   <li>列表路径：配置 {@code list-path} → 常见路径（{@code data.data.comments.list}、{@code data.comments.list} 等）
 *       → 递归扫描响应中第一段「像评论」的数组；</li>
 *   <li>字段别名：{@code id|comment_id|commentId}、{@code userInfo.nickname|nickname|user_name}、
 *       {@code likeCount|like_count|likes}（支持字符串数字）、时间支持秒/毫秒/字符串；</li>
 *   <li>子评论（{@code subComments}/{@code sub_comments}/{@code replies}）递归展开为独立评论，
 *       并写入 {@code replyTo} 保留父子关系。</li>
 * </ul>
 *
 * @see CommentProperties.XiaohongshuProperties
 */
@Slf4j
@Component
public class XhsCommentApiClient {

    private static final List<String> LIST_PATHS = List.of(
            "data.data.comments.list", "data.comments.list", "data.data.list",
            "data.list", "data.comments", "data.items", "data.records", "data.data",
            "result.list", "result.comments", "list", "comments", "items", "records", "data");

    private static final List<String> CURSOR_PATHS = List.of(
            "data.data.comments.cursor", "data.comments.cursor", "data.cursor", "data.next_cursor",
            "data.nextCursor", "cursor", "next_cursor", "data.page_token", "page_token");

    private static final List<String> HAS_MORE_PATHS = List.of(
            "data.data.comments.hasMore", "data.data.comments.has_more", "data.comments.hasMore",
            "data.comments.has_more", "data.has_more", "data.hasMore", "has_more",
            "data.hasMoreData", "hasMore", "data.next_page");

    private static final List<String> ID_FIELDS =
            List.of("id", "comment_id", "commentId", "note_comment_id", "cid");

    private static final List<String> AUTHOR_FIELDS = List.of(
            "userInfo.nickname", "userInfo.nickName", "userInfo.nick_name",
            "user.nickname", "user.nickName", "user_name", "nickname", "nick_name",
            "author", "userName", "user_nickname");

    private static final List<String> CONTENT_FIELDS =
            List.of("content", "text", "comment_content", "note_comment", "comment");

    private static final List<String> LIKE_FIELDS =
            List.of("likeCount", "like_count", "likes", "like_num", "praise_count");

    private static final List<String> TIME_FIELDS =
            List.of("createTime", "create_time", "created_at", "comment_time", "time", "gmt_create");

    private static final List<String> REPLY_TO_FIELDS =
            List.of("parent_comment_id", "reply_to", "target_comment_id", "parent_id",
                    "reply_comment_id", "parentCommentId");

    private static final List<String> SUB_COMMENT_FIELDS =
            List.of("subComments", "sub_comments", "subCommentsList", "replies", "children");

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

    /** 是否使用自建桥预设（xiaohongshu-mcp）。 */
    public boolean isBridgePreset() {
        String p = config().getPreset();
        return p != null && p.trim().equalsIgnoreCase("xhs-mcp");
    }

    /** 接口是否已配置可用（enabled + 端点/预设 + 对应鉴权凭据齐备）。 */
    public boolean isConfigured() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!c.isEnabled()) {
            return false;
        }
        if (!isBridgePreset() && isBlank(c.getEndpoint())) {
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
        if (!isBridgePreset() && isBlank(c.getEndpoint())) {
            return "未配置评论接口地址：contentops.comment.xiaohongshu.endpoint";
        }
        if (!isConfigured()) {
            return "缺少鉴权凭据：bearer/query 需 access-token；ark-sign 需 app-id + app-secret";
        }
        String target = isBridgePreset() ? "(preset=xhs-mcp) " + fullUrl() : c.getMethod().toUpperCase() + " " + fullUrl();
        return "真实接口已配置：" + target + "（鉴权 " + normalizeAuth(c.getAuthMode()) + "）";
    }

    /** 拉取一页评论（不带 xsec_token，用配置里的默认值）。 */
    public FetchPage fetchPage(String workId, String cursor, int limit) {
        return fetchPage(workId, cursor, limit, null);
    }

    /**
     * 拉取一页评论。
     *
     * @param workId    作品/笔记 ID（桥预设中即 feed_id）
     * @param cursor    分页游标；自建桥单次返回全部评论，可为空
     * @param limit     期望条数（会被 pageSize 上限裁剪）
     * @param xsecToken 单篇笔记票据（桥预设必填；为空时取配置默认值）
     */
    public FetchPage fetchPage(String workId, String cursor, int limit, String xsecToken) {
        CommentProperties.XiaohongshuProperties c = config();
        if (isBlank(workId)) {
            throw new CommentSourceException("COMMENT_WORK_ID_REQUIRED", "workId（笔记 ID）不能为空");
        }
        if (!isConfigured()) {
            throw new CommentSourceException("COMMENT_SOURCE_NOT_CONFIGURED", statusHint());
        }

        String token = isBlank(xsecToken) ? c.getXsecToken() : xsecToken;
        if (isBridgePreset() && isBlank(token)) {
            throw new CommentSourceException("COMMENT_XSEC_TOKEN_REQUIRED",
                    "自建桥需要 xsec_token：请在「监控作品」里填写该笔记的 xsec_token"
                            + "（可从 xiaohongshu-mcp 的 /api/v1/feeds/list 或搜索结果中获取）");
        }

        int pageSize = Math.max(1, Math.min(limit <= 0 ? c.getPageSize() : limit, c.getPageSize()));
        Map<String, Object> params = isBridgePreset()
                ? bridgeBody(workId, token, pageSize)
                : customBody(c, workId, cursor, token, pageSize);

        String auth = normalizeAuth(c.getAuthMode());
        if (!isBridgePreset() && "query".equals(auth)) {
            params.put("access_token", c.getAccessToken());
        }
        if (!isBridgePreset() && "ark-sign".equals(auth)) {
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
            String method = isBridgePreset() ? "POST" : c.getMethod();
            String raw;
            if ("GET".equalsIgnoreCase(method)) {
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
            log.debug("[Comment] 拉取评论页: preset={}, workId={}, 条数={}, hasMore={}",
                    isBridgePreset() ? "xhs-mcp" : "custom", workId, page.comments().size(), page.hasMore());
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

    private Map<String, Object> bridgeBody(String workId, String token, int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("feed_id", workId);
        body.put(config().getXsecTokenParam(), token);
        body.put("load_all_comments", true);
        Map<String, Object> commentConfig = new LinkedHashMap<>();
        commentConfig.put("max_comment_items", pageSize);
        commentConfig.put("click_more_replies", false);
        body.put("comment_config", commentConfig);
        return body;
    }

    private Map<String, Object> customBody(CommentProperties.XiaohongshuProperties c, String workId,
                                           String cursor, String token, int pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(c.getNoteIdParam(), workId);
        if (!isBlank(c.getCursorParam())) {
            params.put(c.getCursorParam(), cursor == null ? "" : cursor);
        }
        if (!isBlank(c.getPageSizeParam())) {
            params.put(c.getPageSizeParam(), pageSize);
        }
        if (!isBlank(token) && !isBlank(c.getXsecTokenParam())) {
            params.put(c.getXsecTokenParam(), token);
        }
        return params;
    }

    /** 解析接口响应为评论页（容错多路径/多字段别名/子评论）。 */
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
        if (array == null) {
            return new FetchPage(List.of(), cursorOf(root), false);
        }
        List<XhsComment> comments = new ArrayList<>();
        for (JsonNode item : array) {
            collectComment(item, null, comments);
        }
        String nextCursor = cursorOf(root);
        boolean hasMore = booleanOf(root, HAS_MORE_PATHS);
        if (!hasMore && !isBlank(nextCursor) && !"0".equals(nextCursor)) {
            hasMore = true;
        }
        return new FetchPage(comments, nextCursor, hasMore);
    }

    /** 递归展开一条评论及其子评论（子评论通过 replyTo 关联父评论）。 */
    private void collectComment(JsonNode node, String parentId, List<XhsComment> out) {
        if (node == null || !node.isObject()) {
            return;
        }
        XhsComment comment = mapComment(node, parentId);
        if (comment != null) {
            out.add(comment);
        }
        JsonNode subs = firstArrayField(node, SUB_COMMENT_FIELDS);
        if (subs != null) {
            String parent = comment != null ? comment.commentId() : parentId;
            for (JsonNode sub : subs) {
                collectComment(sub, parent, out);
            }
        }
    }

    private XhsComment mapComment(JsonNode node, String parentId) {
        String content = textOf(node, CONTENT_FIELDS);
        if (isBlank(content)) {
            return null;
        }
        String id = textOf(node, ID_FIELDS);
        String author = textOf(node, AUTHOR_FIELDS);
        Integer likes = intOf(node, LIKE_FIELDS);
        LocalDateTime time = timeOf(node, TIME_FIELDS);
        String replyTo = textOf(node, REPLY_TO_FIELDS);
        if (isBlank(replyTo)) {
            replyTo = parentId;
        }
        return new XhsComment(id, author, content, likes == null ? 0 : likes, time, replyTo);
    }

    private JsonNode firstArrayField(JsonNode node, List<String> fields) {
        for (String field : fields) {
            JsonNode v = descend(node, field);
            if (v != null && v.isArray()) {
                return v;
            }
        }
        return null;
    }

    private String businessError(JsonNode root) {
        if (root == null) {
            return null;
        }
        Integer code = intOf(root, List.of("code", "errCode", "err_code", "status_code", "status"));
        boolean success = !root.has("success") || root.path("success").asBoolean(true);
        String msg = textOf(root, List.of("msg", "message", "error_msg", "errMsg",
                "error_message", "error", "code"));
        if (!success) {
            return "评论接口返回失败" + (code == null ? "" : "（code=" + code + "）")
                    + (isBlank(msg) ? "" : "：" + msg);
        }
        if (code != null && code != 0 && code != 200) {
            return "评论接口返回业务错误（code=" + code + "）" + (isBlank(msg) ? "" : "：" + msg);
        }
        return null;
    }

    /** 定位评论数组：配置路径 → 常见路径 → 递归扫描。 */
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
        JsonNode found = findCommentArray(root);
        if (found != null) {
            log.debug("[Comment] 使用递归扫描定位评论数组，条数={}", found.size());
        }
        return found;
    }

    /** 递归扫描：返回第一个「元素像评论」的数组。 */
    private JsonNode findCommentArray(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            if (node.size() > 0 && looksLikeComment(node.get(0))) {
                return node;
            }
            for (JsonNode child : node) {
                JsonNode found = findCommentArray(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                JsonNode found = findCommentArray(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean looksLikeComment(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        boolean hasContent = node.has("content") || node.has("text") || node.has("commentContent");
        boolean hasIdentity = node.has("id") || node.has("commentId") || node.has("comment_id")
                || node.has("userInfo") || node.has("user");
        return hasContent && hasIdentity;
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
        return uriForGet(fullUrl(), params);
    }

    private URI uriForGet(String url, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(url);
        boolean first = !url.contains("?");
        for (Map.Entry<String, Object> e : params.entrySet()) {
            sb.append(first ? "?" : "&");
            first = false;
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(String.valueOf(e.getValue())));
        }
        return URI.create(sb.toString());
    }

    /** 组装真实请求地址：桥预设走 /api/v1/feeds/detail，自定义支持完整 URL 或相对路径。 */
    private String fullUrl() {
        CommentProperties.XiaohongshuProperties c = config();
        String endpoint = isBridgePreset()
                ? (isBlank(c.getEndpoint()) ? "/api/v1/feeds/detail" : c.getEndpoint().trim())
                : (c.getEndpoint() == null ? "" : c.getEndpoint().trim());
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
            case 401 -> "鉴权失败：检查 access-token（自建桥为启动时的 Bearer Token）";
            case 403 -> "无权限：该接口需平台开通权限（官方开放平台当前未开放笔记评论 API）";
            case 404 -> "接口不存在：检查 endpoint/预设（自建桥为 /api/v1/feeds/detail）";
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

    // ════════════════════════ 真实发送回复 ════════════════════════

    /** 是否具备真实发送回复的能力（接口启用 + reply-enabled + 端点/凭据齐备）。 */
    public boolean isReplyConfigured() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!c.isEnabled() || !c.isReplyEnabled()) {
            return false;
        }
        boolean hasEndpoint = isBridgePreset() || !isBlank(c.getReplyPath());
        boolean hasAuth = "none".equals(normalizeAuth(c.getAuthMode())) || !isBlank(c.getAccessToken());
        return hasEndpoint && hasAuth;
    }

    /** 真实发送的配置诊断提示。 */
    public String replyStatusHint() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!c.isEnabled()) {
            return "真实接口未启用：contentops.comment.xiaohongshu.enabled=false";
        }
        if (!c.isReplyEnabled()) {
            return "真实发送已关闭：contentops.comment.xiaohongshu.reply-enabled=false（发送仅记录状态）";
        }
        if (!isBridgePreset() && isBlank(c.getReplyPath())) {
            return "未配置回复接口：contentops.comment.xiaohongshu.reply-path";
        }
        if (!"none".equals(normalizeAuth(c.getAuthMode())) && isBlank(c.getAccessToken())) {
            return "缺少 access-token（自建桥为启动时的 AUTH_TOKEN）";
        }
        return "可真实发送：" + replyUrl();
    }

    /**
     * 真实回复一条评论（自建桥 {@code POST /api/v1/feeds/comment/reply}）。
     *
     * @param feedId            笔记 ID
     * @param xsecToken         笔记票据（可空，取配置默认值）
     * @param platformCommentId 平台原始评论 ID（必填）
     * @param content           回复内容
     */
    public ReplyResult reply(String feedId, String xsecToken, String platformCommentId, String content) {
        CommentProperties.XiaohongshuProperties c = config();
        if (isBlank(feedId)) {
            throw new CommentSourceException("COMMENT_WORK_ID_REQUIRED", "feedId 不能为空");
        }
        if (isBlank(platformCommentId)) {
            throw new CommentSourceException("COMMENT_PLATFORM_ID_REQUIRED",
                    "缺少平台原始评论 ID，无法真实回复（该评论可能是基于内容指纹生成的，请重新采集）");
        }
        if (isBlank(content)) {
            throw new CommentSourceException("COMMENT_REPLY_CONTENT_REQUIRED", "回复内容不能为空");
        }
        if (!isReplyConfigured()) {
            throw new CommentSourceException("COMMENT_REPLY_NOT_CONFIGURED", replyStatusHint());
        }
        String token = isBlank(xsecToken) ? c.getXsecToken() : xsecToken;
        if (isBlank(token)) {
            throw new CommentSourceException("COMMENT_XSEC_TOKEN_REQUIRED",
                    "真实回复需要 xsec_token：请在「监控作品」里补该笔记的 xsec_token");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(isBridgePreset() ? "feed_id" : c.getNoteIdParam(), feedId);
        body.put(c.getXsecTokenParam(), token);
        body.put(c.getCommentIdParam(), platformCommentId);
        body.put(c.getContentParam(), content);

        try {
            RestClient client = buildClient();
            var spec = client.post()
                    .uri(replyUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            String authorization = authorizationHeader(normalizeAuth(c.getAuthMode()));
            if (!isBlank(authorization)) {
                spec = spec.header("Authorization", authorization);
            }
            String raw = spec.body(body).retrieve().body(String.class);
            ReplyResult result = parseReply(raw);
            log.info("[Comment] 真实回复结果: feedId={}, commentId={}, success={}",
                    feedId, platformCommentId, result.success());
            return result;
        } catch (RestClientResponseException e) {
            throw new CommentSourceException(httpErrorCode(e.getStatusCode().value()),
                    "发送回复失败（HTTP " + e.getStatusCode().value() + "）："
                            + preview(e.getResponseBodyAsString()), e);
        } catch (CommentSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new CommentSourceException("COMMENT_REPLY_IO_ERROR",
                    "发送回复失败: " + e.getMessage(), e);
        }
    }

    /** 解析回复接口响应（兼容 {success,message} 与 {error,code} 两种风格）。 */
    public ReplyResult parseReply(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ReplyResult(true, "已发送");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            return new ReplyResult(true, preview(raw));
        }
        boolean success = !root.has("success") || root.path("success").asBoolean(true);
        JsonNode data = root.path("data");
        if (data.isObject() && data.has("success")) {
            success = success && data.path("success").asBoolean(true);
        }
        String error = textOf(root, List.of("error", "error_message"));
        if (!isBlank(error)) {
            success = false;
        }
        String message = textOf(root, List.of("message", "msg", "error", "error_message"));
        if (isBlank(message)) {
            message = success ? "已发送" : "发送失败";
        }
        return new ReplyResult(success, message);
    }

    private String replyUrl() {
        CommentProperties.XiaohongshuProperties c = config();
        String path = isBlank(c.getReplyPath())
                ? (isBridgePreset() ? "/api/v1/feeds/comment/reply" : "")
                : c.getReplyPath().trim();
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String base = c.getBaseUrl() == null ? "" : c.getBaseUrl().trim();
        if (base.endsWith("/") && path.startsWith("/")) {
            return base.substring(0, base.length() - 1) + path;
        }
        if (!base.endsWith("/") && !path.startsWith("/") && !path.isEmpty()) {
            return base + "/" + path;
        }
        return base + path;
    }

    /** 回复接口返回结果。 */
    public record ReplyResult(boolean success, String message) {
    }
    // ════════════════════════ 评论通知增量采集 ════════════════════════
    //
    // 相比按笔记轮询，通知中心（tab=mentions 即「评论和@」）是账号级增量流：
    // 一次请求就能拿到所有笔记的新评论，且 notification 里自带 feed_xsec_token，
    // 可以直接用于后续读取/回复该笔记。

    /** 是否具备通知采集能力（接口启用 + 端点/预设 + 凭据）。 */
    public boolean isNotificationConfigured() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!c.isEnabled() || !c.isNotificationEnabled()) {
            return false;
        }
        boolean hasEndpoint = isBridgePreset() || !isBlank(c.getNotificationPath());
        boolean hasAuth = "none".equals(normalizeAuth(c.getAuthMode())) || !isBlank(c.getAccessToken());
        return hasEndpoint && hasAuth;
    }

    /** 通知采集的配置诊断提示。 */
    public String notificationStatusHint() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!c.isEnabled()) {
            return "真实接口未启用：contentops.comment.xiaohongshu.enabled=false";
        }
        if (!c.isNotificationEnabled()) {
            return "通知采集已关闭：contentops.comment.xiaohongshu.notification-enabled=false";
        }
        if (!isBridgePreset() && isBlank(c.getNotificationPath())) {
            return "未配置通知接口：contentops.comment.xiaohongshu.notification-path";
        }
        if (!"none".equals(normalizeAuth(c.getAuthMode())) && isBlank(c.getAccessToken())) {
            return "缺少 access-token（自建桥为启动时的 AUTH_TOKEN）";
        }
        return "可拉取评论通知：" + notificationUrl() + "（tab="
                + (isBlank(c.getNotificationTab()) ? "mentions" : c.getNotificationTab()) + "）";
    }

    /** 拉取通知列表。 */
    public NotificationPage fetchNotifications(String tab, int limit) {
        CommentProperties.XiaohongshuProperties c = config();
        if (!isNotificationConfigured()) {
            throw new CommentSourceException("COMMENT_NOTIFICATION_NOT_CONFIGURED", notificationStatusHint());
        }
        String effectiveTab = isBlank(tab)
                ? (isBlank(c.getNotificationTab()) ? "mentions" : c.getNotificationTab().trim())
                : tab.trim();
        int effectiveLimit = limit <= 0
                ? Math.max(1, c.getNotificationLimit())
                : Math.min(limit, 100);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tab", effectiveTab);
        params.put("limit", effectiveLimit);

        try {
            RestClient client = buildClient();
            String authorization = authorizationHeader(normalizeAuth(c.getAuthMode()));
            String raw;
            if ("POST".equalsIgnoreCase(c.getNotificationMethod())) {
                var spec = client.post()
                        .uri(notificationUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON);
                if (!isBlank(authorization)) {
                    spec = spec.header("Authorization", authorization);
                }
                raw = spec.body(params).retrieve().body(String.class);
            } else {
                var spec = client.method(HttpMethod.GET)
                        .uri(uriForGet(notificationUrl(), params))
                        .accept(MediaType.APPLICATION_JSON);
                if (!isBlank(authorization)) {
                    spec = spec.header("Authorization", authorization);
                }
                raw = spec.retrieve().body(String.class);
            }
            NotificationPage page = parseNotifications(raw, effectiveTab);
            log.debug("[Comment] 拉取通知: tab={}, 条数={}, filtered={}",
                    page.tab(), page.items().size(), page.filtered());
            return page;
        } catch (RestClientResponseException e) {
            throw new CommentSourceException(httpErrorCode(e.getStatusCode().value()),
                    "拉取评论通知失败（HTTP " + e.getStatusCode().value() + "）："
                            + preview(e.getResponseBodyAsString()), e);
        } catch (CommentSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new CommentSourceException("COMMENT_NOTIFICATION_IO_ERROR",
                    "拉取评论通知失败: " + e.getMessage(), e);
        }
    }

    /** 解析通知列表响应（兼容 data.data.items 等嵌套）。 */
    public NotificationPage parseNotifications(String raw, String tab) {
        if (isBlank(raw)) {
            return new NotificationPage(tab, 0, List.of());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new CommentSourceException("COMMENT_SOURCE_BAD_JSON",
                    "通知接口返回内容无法解析为 JSON: " + preview(raw), e);
        }
        String bizError = businessError(root);
        if (bizError != null) {
            throw new CommentSourceException("COMMENT_SOURCE_BIZ_ERROR", bizError);
        }
        JsonNode array = locateNotificationList(root);
        List<XhsNotification> items = new ArrayList<>();
        if (array != null) {
            for (JsonNode node : array) {
                XhsNotification item = mapNotification(node);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        Integer filtered = intOf(root, List.of("data.data.filtered", "data.filtered", "filtered"));
        String actualTab = textOf(root, List.of("data.data.tab", "data.tab", "tab"));
        return new NotificationPage(isBlank(actualTab) ? tab : actualTab,
                filtered == null ? 0 : filtered, items);
    }

    /** 未读数（mentions / likes / connections / unread）；未配置或失败时返回空 Map。 */
    public Map<String, Object> fetchUnreadCounts() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!isConfigurable(c.getNotificationUnreadPath())) {
            return Map.of();
        }
        if (!isNotificationConfigured()) {
            return Map.of();
        }
        try {
            RestClient client = buildClient();
            var spec = client.method(HttpMethod.GET)
                    .uri(notificationUnreadUrl())
                    .accept(MediaType.APPLICATION_JSON);
            String authorization = authorizationHeader(normalizeAuth(c.getAuthMode()));
            if (!isBlank(authorization)) {
                spec = spec.header("Authorization", authorization);
            }
            String raw = spec.retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode counts = descend(root, "data.data");
            if (counts == null) {
                counts = descend(root, "data");
            }
            if (counts == null || !counts.isObject()) {
                return Map.of();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (String field : List.of("mentions", "likes", "connections", "unread", "unreadCount")) {
                JsonNode v = counts.path(field);
                if (v.isNumber()) {
                    result.put(field, v.asInt());
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("[Comment] 获取未读数失败（忽略）: {}", e.getMessage());
            return Map.of();
        }
    }

    private boolean isConfigurable(String path) {
        return isBridgePreset() || !isBlank(path);
    }

    private String notificationUrl() {
        CommentProperties.XiaohongshuProperties c = config();
        String path = isBlank(c.getNotificationPath())
                ? (isBridgePreset() ? "/api/v1/notifications/list" : "")
                : c.getNotificationPath().trim();
        return joinUrl(c.getBaseUrl(), path);
    }

    private String notificationUnreadUrl() {
        CommentProperties.XiaohongshuProperties c = config();
        String path = isBlank(c.getNotificationUnreadPath())
                ? (isBridgePreset() ? "/api/v1/notifications/unread" : "")
                : c.getNotificationUnreadPath().trim();
        return joinUrl(c.getBaseUrl(), path);
    }

    private String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        String p = path == null ? "" : path.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return p;
        }
        if (base.endsWith("/") && p.startsWith("/")) {
            return base.substring(0, base.length() - 1) + p;
        }
        if (!base.endsWith("/") && !p.startsWith("/") && !p.isEmpty()) {
            return base + "/" + p;
        }
        return base + p;
    }

    private JsonNode locateNotificationList(JsonNode root) {
        for (String path : List.of("data.data.items", "data.items", "items",
                "data.data.list", "data.list")) {
            JsonNode node = descend(root, path);
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return findArray(root, this::looksLikeNotification);
    }

    private boolean looksLikeNotification(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        boolean hasIdentity = node.has("id") || node.has("notification_id");
        boolean hasNotifyField = node.has("comment_text") || node.has("comment_id")
                || node.has("type") || node.has("from") || node.has("feed_id");
        return hasIdentity && hasNotifyField;
    }

    private JsonNode findArray(JsonNode node, java.util.function.Predicate<JsonNode> matcher) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            if (node.size() > 0 && matcher.test(node.get(0))) {
                return node;
            }
            for (JsonNode child : node) {
                JsonNode found = findArray(child, matcher);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                JsonNode found = findArray(child, matcher);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private XhsNotification mapNotification(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String id = textOf(node, List.of("id", "notification_id", "notify_id"));
        String type = textOf(node, List.of("type", "notification_type", "category"));
        String title = textOf(node, List.of("title", "desc"));
        LocalDateTime time = timeOf(node, List.of("time", "createTime", "create_time",
                "timestamp", "created_at"));
        String userId = textOf(node, List.of("from.user_id", "from.userId", "user_id", "userId"));
        String nickname = textOf(node, List.of("from.nickname", "from.nick_name", "nickname",
                "user_name"));
        String commentId = textOf(node, List.of("comment_id", "commentId", "cid"));
        String commentText = textOf(node, List.of("comment_text", "commentText",
                "comment_content", "content"));
        String feedId = textOf(node, List.of("feed_id", "feedId", "note_id", "noteId"));
        String feedToken = textOf(node, List.of("feed_xsec_token", "feedXsecToken",
                "xsec_token", "xsecToken"));
        String feedTitle = textOf(node, List.of("feed_title", "feedTitle", "note_title"));
        if (isBlank(id) && isBlank(commentId) && isBlank(commentText)) {
            return null;
        }
        return new XhsNotification(id, type, title, time, userId, nickname, commentId,
                commentText, feedId, feedToken, feedTitle);
    }

    /** 通知列表结果。 */
    public record NotificationPage(String tab, int filtered, List<XhsNotification> items) {
    }

    /** 一条通知（评论类通知带 comment_id / comment_text / feed_id / feed_xsec_token）。 */
    public record XhsNotification(String id, String type, String title, LocalDateTime time,
                                  String userId, String nickname, String commentId, String commentText,
                                  String feedId, String feedXsecToken, String feedTitle) {

        /** 是否为评论类通知（有评论 ID 或评论内容）。 */
        public boolean commentLike() {
            return (commentId != null && !commentId.isBlank())
                    || (commentText != null && !commentText.isBlank());
        }
    }
    // ════════════════════════ 通知直回复 ════════════════════════
    //
    // 通知里发现的评论往往没有笔记票据（feed_xsec_token），而通知回复接口只需要
    // comment_id + content，因此对「通知来源」的评论优先走这条路径。

    /** 是否具备通知直回复能力。 */
    public boolean isNotificationReplyConfigured() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!c.isEnabled() || !c.isNotificationReplyEnabled()) {
            return false;
        }
        boolean hasEndpoint = isBridgePreset() || !isBlank(c.getNotificationReplyPath());
        boolean hasAuth = "none".equals(normalizeAuth(c.getAuthMode())) || !isBlank(c.getAccessToken());
        return hasEndpoint && hasAuth;
    }

    /** 通知直回复的配置诊断提示。 */
    public String notificationReplyStatusHint() {
        CommentProperties.XiaohongshuProperties c = config();
        if (!c.isEnabled()) {
            return "真实接口未启用：contentops.comment.xiaohongshu.enabled=false";
        }
        if (!c.isNotificationReplyEnabled()) {
            return "通知直回复已关闭：contentops.comment.xiaohongshu.notification-reply-enabled=false";
        }
        if (!isBridgePreset() && isBlank(c.getNotificationReplyPath())) {
            return "未配置通知回复接口：contentops.comment.xiaohongshu.notification-reply-path";
        }
        if (!"none".equals(normalizeAuth(c.getAuthMode())) && isBlank(c.getAccessToken())) {
            return "缺少 access-token（自建桥为启动时的 AUTH_TOKEN）";
        }
        return "可通知直回复：" + notificationReplyUrl();
    }

    /**
     * 通过通知接口回复评论（自建桥 {@code POST /api/v1/notifications/reply}）。
     *
     * @param platformCommentId 平台原始评论 ID（必填）
     * @param content           回复内容
     */
    public ReplyResult replyToNotification(String platformCommentId, String content) {
        CommentProperties.XiaohongshuProperties c = config();
        if (isBlank(platformCommentId)) {
            throw new CommentSourceException("COMMENT_PLATFORM_ID_REQUIRED",
                    "缺少平台原始评论 ID，无法回复（该评论可能是基于内容指纹生成的，请重新采集）");
        }
        if (isBlank(content)) {
            throw new CommentSourceException("COMMENT_REPLY_CONTENT_REQUIRED", "回复内容不能为空");
        }
        if (!isNotificationReplyConfigured()) {
            throw new CommentSourceException("COMMENT_REPLY_NOT_CONFIGURED", notificationReplyStatusHint());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(c.getCommentIdParam(), platformCommentId);
        body.put(c.getContentParam(), content);

        try {
            RestClient client = buildClient();
            var spec = client.post()
                    .uri(notificationReplyUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            String authorization = authorizationHeader(normalizeAuth(c.getAuthMode()));
            if (!isBlank(authorization)) {
                spec = spec.header("Authorization", authorization);
            }
            String raw = spec.body(body).retrieve().body(String.class);
            ReplyResult result = parseReply(raw);
            log.info("[Comment] 通知直回复结果: commentId={}, success={}", platformCommentId, result.success());
            return result;
        } catch (RestClientResponseException e) {
            throw new CommentSourceException(httpErrorCode(e.getStatusCode().value()),
                    "通知回复失败（HTTP " + e.getStatusCode().value() + "）："
                            + preview(e.getResponseBodyAsString()), e);
        } catch (CommentSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new CommentSourceException("COMMENT_REPLY_IO_ERROR",
                    "通知回复失败: " + e.getMessage(), e);
        }
    }

    private String notificationReplyUrl() {
        CommentProperties.XiaohongshuProperties c = config();
        String path = isBlank(c.getNotificationReplyPath())
                ? (isBridgePreset() ? "/api/v1/notifications/reply" : "")
                : c.getNotificationReplyPath().trim();
        return joinUrl(c.getBaseUrl(), path);
    }
    /** 一页评论数据。 */
    public record FetchPage(List<XhsComment> comments, String nextCursor, boolean hasMore) {
    }

    /** 单条评论（数据源无关的中间结构）。 */
    public record XhsComment(String commentId, String author, String content, int likes,
                             LocalDateTime commentTime, String replyTo) {
    }
}