package com.contentops.common.mcp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Streamable HTTP 传输：POST JSON-RPC 到 MCP server 地址。
 *
 * <p>社区里带 HTTP API 的 MCP server（如小红书 MCP）用这种形态；
 * 响应可能是纯 JSON，也可能是 SSE（{@code event: message / data: {...}}），两种都认。
 */
@Slf4j
public class HttpMcpTransport implements McpTransport {

    private final String url;
    private final String bearerToken;
    private final RestClient restClient;
    /**
     * MCP Streamable HTTP 会话 ID：initialize 响应头返回，后续请求必须带上。
     * 小红书 MCP 等服务端会按会话隔离浏览器上下文，丢了它 tools/call 会失败或新开会话。
     */
    private volatile String sessionId;

    public HttpMcpTransport(String url, String bearerToken, Duration timeout) {
        this.url = url;
        this.bearerToken = bearerToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void notify(String requestJson) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);
            if (bearerToken != null && !bearerToken.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + bearerToken);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                spec = spec.header("Mcp-Session-Id", sessionId);
            }
            spec.body(requestJson).retrieve().toBodilessEntity();
        } catch (Exception e) {
            // 通知失败不影响后续请求（部分实现直接返回 202 或不响应）
            log.debug("[MCP-http] 通知发送异常（可忽略）：{}", e.getMessage());
        }
    }

    @Override
    public String exchange(String requestJson, Duration timeout) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);
            if (bearerToken != null && !bearerToken.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + bearerToken);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                spec = spec.header("Mcp-Session-Id", sessionId);
            }
            String body = spec.body(requestJson).exchange((request, response) -> {
                String sid = response.getHeaders().getFirst("Mcp-Session-Id");
                if (sid != null && !sid.isBlank()) {
                    sessionId = sid;
                }
                String text = new String(response.getBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (response.getStatusCode().value() >= 400) {
                    throw new McpClientException("MCP HTTP " + response.getStatusCode().value() + "：" + text);
                }
                return text;
            });
            String json = McpProtocol.extractJson(body);
            if (json == null) {
                throw new McpClientException("MCP HTTP 响应无法解析为 JSON-RPC：" + summarise(body));
            }
            return json;
        } catch (McpClientException e) {
            throw e;
        } catch (Exception e) {
            throw new McpClientException("MCP HTTP 调用失败：" + url + " -> " + e.getMessage(), e);
        }
    }

    @Override
    public String describe() {
        return "http:" + url;
    }

    @Override
    public void close() {
        // 无状态，无需释放
    }

    private static String summarise(String body) {
        if (body == null) {
            return "(空)";
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}
