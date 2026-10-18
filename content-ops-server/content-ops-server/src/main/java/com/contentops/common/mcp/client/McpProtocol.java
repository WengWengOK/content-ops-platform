package com.contentops.common.mcp.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP / JSON-RPC 协议小工具（纯函数，便于单测）。
 *
 * <p>负责两件容易出错的事：SSE 响应体里抠 JSON、从工具返回的 content 数组里抠文本。
 */
public final class McpProtocol {

    /** SSE 的 data: 行 */
    private static final Pattern SSE_DATA = Pattern.compile("(?m)^data:\\s*(.+)$");

    private McpProtocol() {
    }

    /**
     * HTTP 响应体可能是纯 JSON，也可能是 SSE（{@code event: message\ndata: {...}}）。
     * 这里统一取第一段可解析的 JSON 文本。
     */
    public static String extractJson(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        Matcher matcher = SSE_DATA.matcher(body);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (candidate.startsWith("{")) {
                return candidate;
            }
        }
        return null;
    }

    /** 判断一行文本是否像 JSON-RPC 报文（stdio 读流时用来跳过日志行）。 */
    public static boolean looksLikeJsonRpc(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("{") && trimmed.contains("\"jsonrpc\"");
    }

    /** 协议版本（MCP 2024-11-05 起稳定）。 */
    public static final String PROTOCOL_VERSION = "2024-11-05";
}
