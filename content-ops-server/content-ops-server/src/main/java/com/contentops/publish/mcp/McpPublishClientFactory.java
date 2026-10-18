package com.contentops.publish.mcp;

import com.contentops.common.mcp.client.HttpMcpTransport;
import com.contentops.common.mcp.client.McpClient;
import com.contentops.common.mcp.client.McpClientException;
import com.contentops.common.mcp.client.McpTransport;
import com.contentops.common.mcp.client.StdioMcpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按平台构建/缓存 {@link McpClient}（一个平台一个进程/连接，避免每次上传都重启）。
 */
@Slf4j
@Component
public class McpPublishClientFactory {

    private final PublishMcpProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, McpClient> cache = new ConcurrentHashMap<>();

    public McpPublishClientFactory(PublishMcpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 该平台是否配置并启用了 MCP 发布。 */
    public boolean enabledFor(String platform) {
        if (!properties.isEnabled() || platform == null) {
            return false;
        }
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform.toLowerCase());
        return cfg != null && cfg.isEnabled();
    }

    /** 取（或构建）该平台的 MCP 客户端。 */
    public Optional<McpClient> clientFor(String platform) {
        if (!enabledFor(platform)) {
            return Optional.empty();
        }
        String key = platform.toLowerCase();
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(key);
        return Optional.of(cache.computeIfAbsent(key, ignored -> build(key, cfg)));
    }

    /** 丢弃缓存（改完配置或进程挂掉后重建）。 */
    public void reset(String platform) {
        McpClient removed = cache.remove(platform == null ? "" : platform.toLowerCase());
        if (removed != null) {
            removed.close();
            log.info("[MCP] 已重建 {} 的 MCP 客户端", platform);
        }
    }

    private McpClient build(String platform, PublishMcpProperties.PlatformMcp cfg) {
        Duration initTimeout = Duration.ofMillis(Math.max(1000, properties.getInitializeTimeoutMs()));
        String transport = cfg.getTransport() == null ? "http" : cfg.getTransport().trim().toLowerCase();
        McpTransport mcpTransport;
        if ("stdio".equals(transport)) {
            if (cfg.getCommand() == null || cfg.getCommand().isEmpty()) {
                throw new McpClientException("平台 " + platform + " 配置为 stdio 传输但没有 command");
            }
            mcpTransport = new StdioMcpTransport(List.copyOf(cfg.getCommand()), cfg.getEnv(), cfg.getWorkDir());
        } else if ("http".equals(transport)) {
            if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                throw new McpClientException("平台 " + platform + " 配置为 http 传输但没有 url");
            }
            mcpTransport = new HttpMcpTransport(cfg.getUrl(), cfg.getToken(), initTimeout);
        } else {
            throw new McpClientException("平台 " + platform + " 传输类型不支持：" + transport);
        }
        log.info("[MCP] 平台 {} 使用 {}", platform, mcpTransport.describe());
        return new McpClient(platform, mcpTransport, objectMapper, initTimeout);
    }

    @PreDestroy
    public void closeAll() {
        cache.values().forEach(McpClient::close);
        cache.clear();
    }
}
