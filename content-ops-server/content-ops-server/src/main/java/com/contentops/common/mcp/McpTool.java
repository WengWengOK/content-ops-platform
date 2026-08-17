package com.contentops.common.mcp;

import java.util.Map;

/**
 * MCP 工具定义：名称 / 描述 / JSON Schema 入参 / 调用实现。
 * 由 {@link McpToolRegistry} 注册，经 {@link McpProtocolService} 暴露给外部 Agent。
 */
public interface McpTool {

    String name();

    String description();

    Map<String, Object> inputSchema();

    /**
     * 执行工具，返回文本结果（MCP text content）。
     */
    String call(Map<String, Object> args);
}
