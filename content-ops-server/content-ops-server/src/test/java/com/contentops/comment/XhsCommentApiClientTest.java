package com.contentops.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 小红书评论接口客户端测试：用 JDK 内置 HTTP server 起真实端点，
 * 校验「请求构造（鉴权/参数）」与「响应解析（多结构容错）」两条链路。
 */
class XhsCommentApiClientTest {

    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();
    private final List<URI> uris = new ArrayList<>();
    private final List<Headers> headers = new ArrayList<>();
    private volatile String responseBody = "{}";
    private volatile int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/comments", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            uris.add(exchange.getRequestURI());
            headers.add(exchange.getRequestHeaders());
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(responseStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ──────────────────────── 请求构造 ────────────────────────

    @Test
    @DisplayName("bearer 模式：POST 请求带 Authorization 头与 note_id/cursor/limit 参数，并解析 data.list")
    void fetchPage_bearerMode_sendsAuthHeaderAndParsesList() {
        responseBody = """
                {"code":0,"msg":"ok","data":{"list":[
                  {"comment_id":"c1","user_name":"小红","content":"求教程","like_count":12,"create_time":1735689600},
                  {"comment_id":"c2","nickname":"小蓝","content":"已收藏","like_count":3,"create_time":"2025-01-02 10:30:00"}
                ],"cursor":"next-1","has_more":true}}
                """;
        XhsCommentApiClient client = new XhsCommentApiClient(properties("bearer"), new ObjectMapper());

        XhsCommentApiClient.FetchPage page = client.fetchPage("note-123", "", 20);

        assertThat(page.comments()).hasSize(2);
        assertThat(page.comments().get(0).commentId()).isEqualTo("c1");
        assertThat(page.comments().get(0).author()).isEqualTo("小红");
        assertThat(page.comments().get(0).likes()).isEqualTo(12);
        assertThat(page.comments().get(0).commentTime()).isEqualTo(LocalDateTime.ofInstant(
                Instant.ofEpochSecond(1735689600L), ZoneId.systemDefault()));
        assertThat(page.comments().get(1).author()).isEqualTo("小蓝");
        assertThat(page.comments().get(1).commentTime()).isEqualTo(LocalDateTime.of(2025, 1, 2, 10, 30));
        assertThat(page.nextCursor()).isEqualTo("next-1");
        assertThat(page.hasMore()).isTrue();

        assertThat(headers.get(0).getFirst("Authorization")).isEqualTo("Bearer token-abc");
        assertThat(bodies.get(0)).contains("note-123").contains("limit");
    }

    @Test
    @DisplayName("query + GET 模式：令牌与参数放在 query string")
    void fetchPage_queryGetMode_putsTokenInQuery() {
        responseBody = "{\"data\":{\"comments\":[],\"has_more\":false}}";
        CommentProperties props = properties("query");
        props.getXiaohongshu().setMethod("GET");
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        XhsCommentApiClient.FetchPage page = client.fetchPage("note-9", "cursor-1", 5);

        assertThat(page.comments()).isEmpty();
        String query = uris.get(0).getQuery();
        assertThat(query).contains("note_id=note-9").contains("cursor=cursor-1")
                .contains("access_token=token-abc").contains("limit=5");
        assertThat(headers.get(0).getFirst("Authorization")).isNull();
    }

    @Test
    @DisplayName("ark-sign 模式：请求体包含 app_id/timestamp/method 与 32 位大写 MD5 签名")
    void fetchPage_arkSignMode_includesSignature() {
        responseBody = "{\"data\":{\"list\":[]}}";
        CommentProperties props = new CommentProperties();
        var c = props.getXiaohongshu();
        c.setEnabled(true);
        c.setEndpoint(url());
        c.setAuthMode("ark-sign");
        c.setAppId("app-1");
        c.setAppSecret("secret-1");
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        client.fetchPage("note-1", null, 20);

        String body = bodies.get(0);
        assertThat(body).contains("\"app_id\":\"app-1\"")
                .contains("\"method\":\"note.comment.list\"")
                .contains("\"timestamp\":");
        assertThat(body).containsPattern("\"sign\":\"[0-9A-F]{32}\"");
    }

    @Test
    @DisplayName("HTTP 403 映射为 COMMENT_SOURCE_FORBIDDEN 且带排查提示")
    void fetchPage_httpError_mapsToTypedCode() {
        responseStatus = 403;
        responseBody = "{\"msg\":\"no permission\"}";
        XhsCommentApiClient client = new XhsCommentApiClient(properties("bearer"), new ObjectMapper());

        CommentSourceException ex = catchThrowableOfType(
                () -> client.fetchPage("note-1", "", 20), CommentSourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("HTTP 403").contains("无权限");
        assertThat(ex.getCode()).isEqualTo("COMMENT_SOURCE_FORBIDDEN");
    }

    @Test
    @DisplayName("未配置时直接抛出可读异常，不发请求")
    void fetchPage_notConfigured_throws() {
        CommentProperties props = new CommentProperties();
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        CommentSourceException ex = catchThrowableOfType(
                () -> client.fetchPage("note-1", "", 20), CommentSourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("未启用");
        assertThat(ex.getCode()).isEqualTo("COMMENT_SOURCE_NOT_CONFIGURED");
        assertThat(bodies).isEmpty();
    }

    // ──────────────────────── 响应解析 ────────────────────────

    @Test
    @DisplayName("兼容 data.items + 毫秒时间戳 + parent_comment_id（数据服务商差异）")
    void parse_alternativeShape() {
        XhsCommentApiClient client = new XhsCommentApiClient(properties("bearer"), new ObjectMapper());

        XhsCommentApiClient.FetchPage page = client.parse("""
                {"success":true,"data":{"items":[
                  {"id":"d1","author":"小明","content":"沙发","likes":1,"create_time":1735689600000,"parent_comment_id":"p0"}
                ]}}
                """);

        assertThat(page.comments()).hasSize(1);
        assertThat(page.comments().get(0).commentId()).isEqualTo("d1");
        assertThat(page.comments().get(0).replyTo()).isEqualTo("p0");
        assertThat(page.comments().get(0).commentTime()).isNotNull();
    }

    @Test
    @DisplayName("业务错误码非 0 时抛出 COMMENT_SOURCE_BIZ_ERROR")
    void parse_businessError_throws() {
        XhsCommentApiClient client = new XhsCommentApiClient(properties("bearer"), new ObjectMapper());

        CommentSourceException ex = catchThrowableOfType(
                () -> client.parse("{\"code\":10001,\"msg\":\"invalid token\"}"), CommentSourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("10001").contains("invalid token");
        assertThat(ex.getCode()).isEqualTo("COMMENT_SOURCE_BIZ_ERROR");
    }

    @Test
    @DisplayName("无评论数组时返回空页而不是抛错")
    void parse_noList_returnsEmptyPage() {
        XhsCommentApiClient client = new XhsCommentApiClient(properties("bearer"), new ObjectMapper());

        XhsCommentApiClient.FetchPage page = client.parse("{\"code\":0,\"data\":{}}");

        assertThat(page.comments()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    // ──────────────────────── xiaohongshu-mcp 自建桥预设 ────────────────────────

    @Test
    @DisplayName("xhs-mcp 预设：请求体含 feed_id/xsec_token/load_all_comments，并解析 data.data.comments.list（含子评论）")
    void fetchPage_bridgePreset_parsesNestedComments() {
        responseBody = """
                {"success":true,"message":"获取Feed详情成功","data":{"feed_id":"note-9","data":{
                  "note":{"noteId":"note-9","title":"标题","interactInfo":{"commentCount":"2"}},
                  "comments":{"list":[
                    {"id":"c1","content":"求教程","likeCount":"12","createTime":1735689600000,
                     "userInfo":{"userId":"u1","nickname":"小红"},
                     "subComments":[{"id":"c1-1","content":"同求","likeCount":"1","createTime":1735689600000,
                        "userInfo":{"nickname":"小蓝"}}]},
                    {"id":"c2","content":"已收藏","likeCount":"3","createTime":1735689600000,
                     "userInfo":{"nickname":"小绿"}}
                  ],"cursor":"","hasMore":false}
                }}}
                """;
        CommentProperties props = properties("bearer");
        props.getXiaohongshu().setPreset("xhs-mcp");
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        XhsCommentApiClient.FetchPage page = client.fetchPage("note-9", "", 20, "xsec-token-1");

        assertThat(page.comments()).hasSize(3);
        XhsCommentApiClient.XhsComment first = page.comments().get(0);
        assertThat(first.commentId()).isEqualTo("c1");
        assertThat(first.author()).isEqualTo("小红");
        assertThat(first.content()).isEqualTo("求教程");
        assertThat(first.likes()).isEqualTo(12);
        assertThat(first.replyTo()).isNull();
        XhsCommentApiClient.XhsComment sub = page.comments().get(1);
        assertThat(sub.commentId()).isEqualTo("c1-1");
        assertThat(sub.author()).isEqualTo("小蓝");
        assertThat(sub.replyTo()).isEqualTo("c1");
        assertThat(page.comments().get(2).commentId()).isEqualTo("c2");

        String body = bodies.get(0);
        assertThat(body).contains("\"feed_id\":\"note-9\"")
                .contains("\"xsec_token\":\"xsec-token-1\"")
                .contains("\"load_all_comments\":true")
                .contains("comment_config");
        assertThat(headers.get(0).getFirst("Authorization")).isEqualTo("Bearer token-abc");
    }

    @Test
    @DisplayName("xhs-mcp 预设缺少 xsec_token 时给出可读错误，不发请求")
    void fetchPage_bridgePresetWithoutToken_throws() {
        CommentProperties props = properties("bearer");
        props.getXiaohongshu().setPreset("xhs-mcp");
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        CommentSourceException ex = catchThrowableOfType(
                () -> client.fetchPage("note-9", "", 20, null), CommentSourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("COMMENT_XSEC_TOKEN_REQUIRED");
        assertThat(ex.getMessage()).contains("xsec_token");
        assertThat(bodies).isEmpty();
    }

    @Test
    @DisplayName("未知响应结构：递归扫描仍能定位评论数组")
    void parse_recursiveFallback_findsComments() {
        XhsCommentApiClient client = new XhsCommentApiClient(properties("bearer"), new ObjectMapper());

        XhsCommentApiClient.FetchPage page = client.parse("""
                {"result":{"noteData":{"commentList":[
                  {"id":"r1","content":"结构不常见也能解析","likeCount":"2","userInfo":{"nickname":"路人"}}
                ]}}}
                """);

        assertThat(page.comments()).hasSize(1);
        assertThat(page.comments().get(0).commentId()).isEqualTo("r1");
        assertThat(page.comments().get(0).author()).isEqualTo("路人");
        assertThat(page.comments().get(0).likes()).isEqualTo(2);
    }
    // ──────────────────────── 真实发送回复 ────────────────────────

    @Test
    @DisplayName("xhs-mcp 预设回复：请求体含 feed_id/xsec_token/comment_id/content，并解析 success/message")
    void reply_bridgePreset_sendsAndParses() {
        responseBody = "{\"success\":true,\"message\":\"回复成功\",\"data\":{\"feed_id\":\"note-9\",\"success\":true}}";
        CommentProperties props = properties("bearer");
        props.getXiaohongshu().setPreset("xhs-mcp");
        props.getXiaohongshu().setReplyPath(url());
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        XhsCommentApiClient.ReplyResult result = client.reply("note-9", "xs-1", "c-1", "谢谢支持～");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("回复成功");
        String body = bodies.get(0);
        assertThat(body).contains("\"feed_id\":\"note-9\"")
                .contains("\"xsec_token\":\"xs-1\"")
                .contains("\"comment_id\":\"c-1\"")
                .contains("谢谢支持");
        assertThat(headers.get(0).getFirst("Authorization")).isEqualTo("Bearer token-abc");
    }

    @Test
    @DisplayName("回复接口返回 error 字段时判定为失败")
    void reply_errorPayload_marksFailure() {
        XhsCommentApiClient client = new XhsCommentApiClient(properties("bearer"), new ObjectMapper());

        XhsCommentApiClient.ReplyResult result =
                client.parseReply("{\"error\":\"回复过于频繁\",\"code\":\"RATE_LIMIT\"}");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("回复过于频繁");
    }

    @Test
    @DisplayName("reply-enabled=false 时不具备真实发送能力")
    void reply_disabled_hasNoCapability() {
        CommentProperties props = properties("bearer");
        props.getXiaohongshu().setPreset("xhs-mcp");
        props.getXiaohongshu().setReplyEnabled(false);
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        assertThat(client.isReplyConfigured()).isFalse();
        assertThat(client.replyStatusHint()).contains("真实发送已关闭");
    }

    @Test
    @DisplayName("缺少平台评论 ID 时拒绝真实回复")
    void reply_withoutPlatformCommentId_throws() {
        CommentProperties props = properties("bearer");
        props.getXiaohongshu().setPreset("xhs-mcp");
        props.getXiaohongshu().setReplyPath(url());
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        CommentSourceException ex = catchThrowableOfType(
                () -> client.reply("note-9", "xs-1", null, "内容"), CommentSourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("COMMENT_PLATFORM_ID_REQUIRED");
    }
    // ──────────────────────── 评论通知增量采集 ────────────────────────

    @Test
    @DisplayName("通知列表：解析 data.data.items（含 filtered），GET 请求带 tab/limit 与 Bearer")
    void fetchNotifications_parsesBridgePayload() {
        responseBody = """
                {"success":true,"message":"获取通知列表成功","data":{"data":{"tab":"mentions","filtered":2,"items":[
                  {"id":"n1","type":"评论","title":"评论了你","time":1767225600000,
                   "from":{"user_id":"u1","nickname":"桃桃","xsec_token":"user-token"},
                   "comment_id":"c-1","comment_text":"求同款链接！","liked":false,
                   "feed_id":"note-1","feed_xsec_token":"xs-note-1","feed_title":"测试笔记"},
                  {"id":"n2","type":"点赞","title":"赞了你","time":1767225600000,
                   "from":{"user_id":"u2","nickname":"路人"},"liked":true,"feed_id":"note-1"}
                ]}}}
                """;
        CommentProperties props = properties("bearer");
        props.getXiaohongshu().setPreset("xhs-mcp");
        props.getXiaohongshu().setNotificationPath(url());
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        XhsCommentApiClient.NotificationPage page = client.fetchNotifications(null, 20);

        assertThat(page.tab()).isEqualTo("mentions");
        assertThat(page.filtered()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        XhsCommentApiClient.XhsNotification first = page.items().get(0);
        assertThat(first.commentId()).isEqualTo("c-1");
        assertThat(first.commentText()).isEqualTo("求同款链接！");
        assertThat(first.nickname()).isEqualTo("桃桃");
        assertThat(first.feedId()).isEqualTo("note-1");
        assertThat(first.feedXsecToken()).isEqualTo("xs-note-1");
        assertThat(first.commentLike()).isTrue();
        assertThat(page.items().get(1).commentLike()).isFalse();

        assertThat(uris.get(0).getQuery()).contains("tab=mentions").contains("limit=20");
        assertThat(headers.get(0).getFirst("Authorization")).isEqualTo("Bearer token-abc");
    }

    @Test
    @DisplayName("未读数：解析 data.data 里的 mentions/likes/connections/unread")
    void fetchUnreadCounts_parsesCounts() {
        responseBody = "{\"success\":true,\"data\":{\"data\":{\"mentions\":3,\"likes\":1,\"connections\":0,\"unread\":4}}}";
        CommentProperties props = properties("bearer");
        props.getXiaohongshu().setPreset("xhs-mcp");
        props.getXiaohongshu().setNotificationUnreadPath(url());
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        Map<String, Object> counts = client.fetchUnreadCounts();

        assertThat(counts).containsEntry("mentions", 3)
                .containsEntry("likes", 1)
                .containsEntry("connections", 0)
                .containsEntry("unread", 4);
    }

    @Test
    @DisplayName("通知接口未配置时给出可读错误，不发请求")
    void fetchNotifications_notConfigured_throws() {
        XhsCommentApiClient client = new XhsCommentApiClient(new CommentProperties(), new ObjectMapper());

        CommentSourceException ex = catchThrowableOfType(
                () -> client.fetchNotifications(null, 20), CommentSourceException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("COMMENT_NOTIFICATION_NOT_CONFIGURED");
        assertThat(bodies).isEmpty();
    }
    // ──────────────────────── 通知直回复 ────────────────────────

    @Test
    @DisplayName("通知直回复：请求体只含 comment_id/content，解析 success/message")
    void replyToNotification_sendsAndParses() {
        responseBody = "{\"success\":true,\"message\":\"回复成功\"}";
        CommentProperties props = properties("bearer");
        props.getXiaohongshu().setPreset("xhs-mcp");
        props.getXiaohongshu().setNotificationReplyPath(url());
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        XhsCommentApiClient.ReplyResult result = client.replyToNotification("notif-c1", "谢谢支持～");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("回复成功");
        String body = bodies.get(0);
        assertThat(body).contains("\"comment_id\":\"notif-c1\"").contains("谢谢支持");
        assertThat(body).doesNotContain("xsec_token");
        assertThat(headers.get(0).getFirst("Authorization")).isEqualTo("Bearer token-abc");
    }

    @Test
    @DisplayName("通知回复开关关闭时不具备能力，给出诊断提示")
    void replyToNotification_disabled_hasNoCapability() {
        CommentProperties props = properties("bearer");
        props.getXiaohongshu().setPreset("xhs-mcp");
        props.getXiaohongshu().setNotificationReplyEnabled(false);
        XhsCommentApiClient client = new XhsCommentApiClient(props, new ObjectMapper());

        assertThat(client.isNotificationReplyConfigured()).isFalse();
        assertThat(client.notificationReplyStatusHint()).contains("通知直回复已关闭");
    }
    // ──────────────────────── 辅助 ────────────────────────

    private CommentProperties properties(String authMode) {
        CommentProperties props = new CommentProperties();
        var c = props.getXiaohongshu();
        c.setEnabled(true);
        c.setEndpoint(url());
        c.setAuthMode(authMode);
        c.setAccessToken("token-abc");
        return props;
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/comments";
    }
}