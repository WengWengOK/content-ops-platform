package com.contentops.common.mcp.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 协议小工具测试：SSE 抠 JSON、stdio 跳过日志行。
 */
class McpProtocolTest {

    @Test
    @DisplayName("纯 JSON 响应直接返回")
    void plainJson() {
        assertThat(McpProtocol.extractJson("  {\"jsonrpc\":\"2.0\",\"id\":1}  "))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1}");
    }

    @Test
    @DisplayName("SSE 响应（event/data）里抠出 JSON")
    void sseBody() {
        String body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}\n\n";
        assertThat(McpProtocol.extractJson(body))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}");
    }

    @Test
    @DisplayName("SSE 里的非 JSON 事件（如 ping）跳过，取后面真正的报文")
    void sseWithNoise() {
        String body = "event: ping\ndata: keep-alive\n\nevent: message\ndata: {\"id\":3}\n\n";
        assertThat(McpProtocol.extractJson(body)).isEqualTo("{\"id\":3}");
    }

    @Test
    @DisplayName("无法解析时返回 null")
    void unparseable() {
        assertThat(McpProtocol.extractJson("500 Internal Server Error")).isNull();
        assertThat(McpProtocol.extractJson("")).isNull();
        assertThat(McpProtocol.extractJson(null)).isNull();
    }

    @Test
    @DisplayName("stdio 行识别：只有含 jsonrpc 的 JSON 行才算协议报文")
    void looksLikeJsonRpc() {
        assertThat(McpProtocol.looksLikeJsonRpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")).isTrue();
        assertThat(McpProtocol.looksLikeJsonRpc("INFO 抖音上传服务已启动")).isFalse();
        assertThat(McpProtocol.looksLikeJsonRpc("{\"message\":\"普通日志\"}")).isFalse();
        assertThat(McpProtocol.looksLikeJsonRpc("{\"error\":\"boom\"}")).isFalse();
    }
}
