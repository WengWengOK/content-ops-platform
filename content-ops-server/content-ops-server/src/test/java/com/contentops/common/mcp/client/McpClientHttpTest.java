package com.contentops.common.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * MCP 客户端的 HTTP 传输测试：握手、tools/list、tools/call、错误映射。
 * 用 JDK 内置 HttpServer 做桩，不依赖任何外部 MCP server。
 */
class McpClientHttpTest {

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private String toolCallError;
    private boolean sseMode;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(request);
            String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            boolean initialize = request.contains("\"method\":\"initialize\"");
            if (!initialize && !"sess-123".equals(session)) {
                byte[] error = "{\"error\":\"missing Mcp-Session-Id\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, error.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(error);
                }
                return;
            }
            String response;
            if (initialize) {
                response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\","
                        + "\"serverInfo\":{\"name\":\"stub-xhs\",\"version\":\"1.0\"}}}";
            } else if (request.contains("\"method\":\"tools/list\"")) {
                response = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                        + "{\"name\":\"check_login\",\"description\":\"检查登录态\"},"
                        + "{\"name\":\"publish_note\",\"description\":\"发布图文笔记\"}]}}";
            } else if (request.contains("\"method\":\"tools/call\"")) {
                if (toolCallError != null) {
                    response = "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32000,\"message\":\""
                            + toolCallError + "\"}}";
                } else {
                    response = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"isError\":false,\"content\":"
                            + "[{\"type\":\"text\",\"text\":\"笔记已发布\"},{\"type\":\"text\",\"text\":\"id=note-123\"}]}}";
                }
            } else {
                // 通知（notifications/initialized）：MCP 规范允许 202 无响应体
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            byte[] bytes = (sseMode ? "event: message\ndata: " + response + "\n\n" : response)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", sseMode ? "text/event-stream" : "application/json");
            if (initialize) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-123");
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private McpClient client() {
        HttpMcpTransport transport = new HttpMcpTransport("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                null, Duration.ofSeconds(5));
        return new McpClient("xiaohongshu", transport, new ObjectMapper(), Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("握手 + tools/list：解析出工具名与描述")
    void handshakeAndListTools() {
        McpClient client = client();

        List<McpClient.McpToolInfo> tools = client.listTools();

        assertThat(tools).extracting(McpClient.McpToolInfo::name)
                .containsExactly("check_login", "publish_note");
        assertThat(received.get(0)).contains("\"method\":\"initialize\"").contains("protocolVersion");
        assertThat(received).anyMatch(body -> body.contains("\"method\":\"notifications/initialized\""));
    }

    @Test
    @DisplayName("tools/call：拼接多段 content 文本")
    void callTool() {
        McpClient client = client();

        McpClient.McpCallResult result = client.callTool("publish_note",
                Map.of("title", "标题", "content", "正文"));

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).contains("笔记已发布").contains("id=note-123");
        assertThat(received).anyMatch(body -> body.contains("\"name\":\"publish_note\"")
                && body.contains("\"title\":\"标题\""));
    }

    @Test
    @DisplayName("工具返回 JSON-RPC error：抛 McpClientException 并带错误信息")
    void mapsProtocolError() {
        toolCallError = "未登录小红书";
        McpClient client = client();

        McpClientException error = catchThrowableOfType(McpClientException.class,
                () -> client.callTool("publish_note", Map.of()));

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("未登录小红书");
    }

    @Test
    @DisplayName("SSE 形态的响应也能解析（部分 MCP server 走 text/event-stream）")
    void supportsSseResponse() {
        sseMode = true;
        McpClient client = client();

        assertThat(client.listTools()).hasSize(2);
    }

    @Test
    @DisplayName("服务不可达：抛带地址的 McpClientException")
    void unreachableEndpoint() {
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;
        HttpMcpTransport transport = new HttpMcpTransport("http://127.0.0.1:" + port + "/mcp", null,
                Duration.ofSeconds(2));
        McpClient client = new McpClient("xiaohongshu", transport, new ObjectMapper(), Duration.ofSeconds(2));

        McpClientException error = catchThrowableOfType(McpClientException.class, client::listTools);

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("MCP HTTP 调用失败");
    }
}
