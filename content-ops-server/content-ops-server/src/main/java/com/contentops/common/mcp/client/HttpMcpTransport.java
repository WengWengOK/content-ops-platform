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
            String body = spec.body(requestJson).retrieve().body(String.class);
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
