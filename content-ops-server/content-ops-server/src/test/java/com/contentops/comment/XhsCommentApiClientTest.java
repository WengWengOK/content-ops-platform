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