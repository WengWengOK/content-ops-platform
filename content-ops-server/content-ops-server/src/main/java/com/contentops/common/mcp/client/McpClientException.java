package com.contentops.common.mcp.client;

/** MCP 调用异常（传输失败、协议错误、工具返回错误）。 */
public class McpClientException extends RuntimeException {

    public McpClientException(String message) {
        super(message);
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
