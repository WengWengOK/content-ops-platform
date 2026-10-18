package com.contentops.common.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简 MCP 客户端：握手 → tools/list → tools/call。
 *
 * <p>只实现发布场景需要的能力（不订阅资源、不用 prompts），因此不依赖任何 MCP SDK，
 * 对 stdio / HTTP 两种传输一视同仁 —— 社区里各平台 MCP server 这两种形态都有。
 */
@Slf4j
public class McpClient implements AutoCloseable {

    /** 工具描述 */
    public record McpToolInfo(String name, String description) {
    }

    /** 工具调用结果 */
    public record McpCallResult(boolean ok, String text, String raw) {
    }

    private final String name;
    private final McpTransport transport;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final AtomicInteger ids = new AtomicInteger(1);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public McpClient(String name, McpTransport transport, ObjectMapper objectMapper, Duration timeout) {
        this.name = name;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    /** 握手（幂等）。 */
    public synchronized void initialize() {
        if (initialized.get()) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", McpProtocol.PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "content-ops", "version", "1.0.0"));
        JsonNode result = request("initialize", params);
        notify("notifications/initialized", Map.of());
        initialized.set(true);
        log.info("[MCP] {} 握手完成，serverInfo={}", name,
                result == null ? "-" : result.path("serverInfo").toString());
    }

    /** 列出工具。 */
    public List<McpToolInfo> listTools() {
        initialize();
        JsonNode result = request("tools/list", Map.of());
        List<McpToolInfo> tools = new ArrayList<>();
        if (result != null && result.path("tools").isArray()) {
            for (JsonNode tool : result.path("tools")) {
                tools.add(new McpToolInfo(tool.path("name").asText(""),
                        tool.path("description").asText("")));
            }
        }
        return tools;
    }

    /**
     * 调用工具。
     *
     * @param tool 工具名
     * @param args 参数（与工具 inputSchema 对齐）
     */
    public McpCallResult callTool(String tool, Map<String, Object> args) {
        initialize();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", args == null ? Map.of() : args);
        JsonNode result = request("tools/call", params);
        if (result == null) {
            return new McpCallResult(false, "工具无返回", null);
        }
        boolean isError = result.path("isError").asBoolean(false);
        StringBuilder text = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String piece = item.path("text").asText("");
                if (!piece.isEmpty()) {
                    text.append(piece).append('\n');
                }
            }
        }
        String raw = result.toString();
        String body = text.isEmpty() ? raw : text.toString().trim();
        return new McpCallResult(!isError, body, raw);
    }

    public String describe() {
        return name + "(" + transport.describe() + ")";
    }

    // ─────────────── JSON-RPC 收发 ───────────────

    private JsonNode request(String method, Object params) {
        int id = ids.getAndIncrement();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);
        JsonNode response = send(payload);
        if (response.has("error") && !response.path("error").isNull()) {
            throw new McpClientException(name + " 调用 " + method + " 失败："
                    + response.path("error").path("message").asText(response.path("error").toString()));
        }
        return response.path("result");
    }

    private void notify(String method, Object params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        payload.put("params", params);
        try {
            transport.notify(toJson(payload));
        } catch (Exception e) {
            // 通知失败不影响后续请求
            log.debug("[MCP] {} 通知 {} 发送异常（可忽略）：{}", name, method, e.getMessage());
        }
    }

    private JsonNode send(Map<String, Object> payload) {
        String responseJson = transport.exchange(toJson(payload), timeout);
        try {
            return objectMapper.readTree(responseJson);
        } catch (Exception e) {
            throw new McpClientException(name + " 响应不是合法 JSON：" + responseJson, e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new McpClientException(name + " 请求序列化失败", e);
        }
    }

    @Override
    public void close() {
        transport.close();
    }
}
