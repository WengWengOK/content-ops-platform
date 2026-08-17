package com.contentops.common.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）JSON-RPC 服务端处理器。
 *
 * <p>实现 2025-06-18 协议的 subset：initialize / ping / tools/list / tools/call，
 * 通过 HTTP POST 暴露（streamable HTTP 传输的 JSON 响应形态）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpProtocolService {

    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /**
     * 处理一条 JSON-RPC 消息，返回响应 JSON；notification（无 id）返回 null。
     */
    public String process(String bodyJson) {
        try {
            JsonNode root = objectMapper.readTree(bodyJson == null ? "{}" : bodyJson);
            String method = root.path("method").asText("");
            JsonNode idNode = root.get("id");
            boolean hasId = idNode != null && !idNode.isNull();
            JsonNode params = root.path("params");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            if (hasId) {
                response.put("id", idNode);
            }

            switch (method) {
                case "initialize" -> {
                    response.put("result", Map.of(
                            "protocolVersion", PROTOCOL_VERSION,
                            "capabilities", Map.of("tools", Map.of("listChanged", false)),
                            "serverInfo", Map.of("name", "contentops-mcp", "version", "1.0.0")));
                }
                case "ping" -> response.put("result", Map.of());
                case "tools/list" -> {
                    List<Map<String, Object>> tools = toolRegistry.tools().stream()
                            .map(t -> (Map<String, Object>) Map.of(
                                    "name", t.name(),
                                    "description", t.description(),
                                    "inputSchema", t.inputSchema()))
                            .toList();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("tools", tools);
                    result.put("nextCursor", null);
                    response.put("result", result);
                }
                case "tools/call" -> {
                    String name = params.path("name").asText("");
                    JsonNode arguments = params.path("arguments");
                    Map<String, Object> args = arguments.isObject()
                            ? objectMapper.convertValue(arguments, Map.class)
                            : Map.of();
                    McpTool tool = toolRegistry.tools().stream()
                            .filter(t -> t.name().equals(name))
                            .findFirst()
                            .orElse(null);
                    if (tool == null) {
                        response.put("error", jsonRpcError(-32602, "Unknown tool: " + name));
                    } else {
                        try {
                            String text = tool.call(args);
                            response.put("result", Map.of(
                                    "content", List.of(Map.of("type", "text", "text", text)),
                                    "isError", false));
                        } catch (Exception e) {
                            log.warn("[MCP] tools/call 失败: tool={}, err={}", name, e.getMessage());
                            response.put("result", Map.of(
                                    "content", List.of(Map.of("type", "text", "text", "工具执行失败: " + e.getMessage())),
                                    "isError", true));
                        }
                    }
                }
                case "notifications/initialized", "" -> {
                    if (!hasId) {
                        return null;
                    }
                    response.put("result", Map.of());
                }
                default -> response.put("error", jsonRpcError(-32601, "Method not found: " + method));
            }
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("[MCP] 协议处理失败: {}", e.getMessage());
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "error", jsonRpcError(-32700, "Parse error: " + e.getMessage())));
            } catch (Exception ignored) {
                return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}";
            }
        }
    }

    private Map<String, Object> jsonRpcError(int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        return error;
    }
}
