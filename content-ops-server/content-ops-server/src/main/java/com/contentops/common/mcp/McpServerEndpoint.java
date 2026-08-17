package com.contentops.common.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP Server HTTP 端点。
 *
 * <p>提供 MCP 协议的 HTTP 端点，允许 MCP 客户端发现和调用已注册的工具。
 * 所有端点以 {@code /mcp} 为前缀。
 *
 * <p>端点列表：
 * <ul>
 *   <li>{@code GET /mcp/tools} — 列出所有已注册工具</li>
 *   <li>{@code POST /mcp/tools/{toolName}/execute} — 执行指定工具</li>
 *   <li>{@code GET /mcp/health} — 健康检查</li>
 * </ul>
 *
 * <p>通过 {@code contentops.mcp.enabled=true} 启用。当 MCP 禁用时，
 * 此 Controller 不会被加载，端点不可访问。
 *
 * <p><b>工具执行请求体格式</b>：
 * <pre>{@code
 * {
 *   "param1": "value1",
 *   "param2": 42,
 *   "param3": ["a", "b"]
 * }
 * }</pre>
 * 其中 key 为参数名（与 @Tool 方法的参数名对应），value 为参数值。
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "contentops.mcp.enabled", havingValue = "true")
public class McpServerEndpoint {

    private final McpToolRegistry mcpToolRegistry;

    /**
     * 列出所有已注册的 MCP 工具。
     *
     * <p>返回的工具描述符中不包含 bean 和 method 字段（已通过 @JsonIgnoreProperties 排除）。
     *
     * @return 工具描述符列表
     */
    @GetMapping("/tools")
    public ResponseEntity<List<McpToolDescriptor>> listTools() {
        List<McpToolDescriptor> tools = mcpToolRegistry.listTools().stream()
                .collect(Collectors.toList());
        log.info("[MCP Endpoint] listTools: 返回 {} 个工具", tools.size());
        return ResponseEntity.ok(tools);
    }

    /**
     * 执行指定的 MCP 工具。
     *
     * @param toolName 工具名称（格式：BeanSimpleClassName.methodName）
     * @param args     参数 Map，key 为参数名，value 为参数值
     * @return 执行结果
     */
    @PostMapping("/tools/{toolName}/execute")
    public ResponseEntity<Map<String, Object>> executeTool(
            @PathVariable String toolName,
            @RequestBody(required = false) Map<String, Object> args) {

        log.info("[MCP Endpoint] executeTool: toolName={}, args={}", toolName, args);

        // 检查工具是否存在
        McpToolDescriptor descriptor = mcpToolRegistry.getTool(toolName);
        if (descriptor == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "工具不存在: " + toolName);
            error.put("availableTools", mcpToolRegistry.listTools().stream()
                    .map(McpToolDescriptor::getToolName)
                    .collect(Collectors.toList()));
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Object result = mcpToolRegistry.executeTool(toolName, args != null ? args : new HashMap<>());

            Map<String, Object> response = new HashMap<>();
            response.put("toolName", toolName);
            response.put("success", true);
            response.put("result", result);
            response.put("resultType", result != null ? result.getClass().getSimpleName() : "void");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[MCP Endpoint] executeTool 失败: toolName={}, error={}", toolName, e.getMessage(), e);

            Map<String, Object> error = new HashMap<>();
            error.put("toolName", toolName);
            error.put("success", false);
            error.put("error", e.getMessage());
            error.put("errorType", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * MCP 服务健康检查。
     *
     * @return 健康状态信息，包括已注册工具数量
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("registeredTools", mcpToolRegistry.listTools().size());
        health.put("toolNames", mcpToolRegistry.listTools().stream()
                .map(McpToolDescriptor::getToolName)
                .collect(Collectors.toList()));
        return ResponseEntity.ok(health);
    }
}
