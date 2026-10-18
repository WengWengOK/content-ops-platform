package com.contentops.common.mcp.client;

import java.time.Duration;

/**
 * MCP 传输层：把一行 JSON-RPC 请求发出去，拿回一行 JSON-RPC 响应。
 *
 * <p>支持两种主流传输（对应社区各平台 MCP server 的实际形态）：
 * <ul>
 *   <li>{@link StdioMcpTransport} —— stdio：拉起本地进程（npx/node/python），走标准输入输出。
 *       典型：抖音创作者平台自动化上传（CDP）、文颜公众号排版发布（本地模式）。</li>
 *   <li>{@link HttpMcpTransport} —— Streamable HTTP：POST JSON-RPC 到服务地址。
 *       典型：小红书 MCP（HTTP API + MCP 双模式）、文颜远程模式。</li>
 * </ul>
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 发送一次 JSON-RPC 请求，返回响应原文（JSON）。
     *
     * @param requestJson 请求体
     * @param timeout     超时
     * @return 响应 JSON 文本
     */
    String exchange(String requestJson, Duration timeout);

    /**
     * 发送一条不需要响应的 JSON-RPC 通知（如 {@code notifications/initialized}）。
     *
     * <p>单独开一个方法而不是复用 {@link #exchange}：stdio 下等一个永远不来的响应会把超时白等满，
     * 这是实测踩到的坑。
     */
    void notify(String requestJson);

    /** 传输描述（用于状态展示：stdio:cmd / http:url）。 */
    String describe();

    @Override
    void close();
}
