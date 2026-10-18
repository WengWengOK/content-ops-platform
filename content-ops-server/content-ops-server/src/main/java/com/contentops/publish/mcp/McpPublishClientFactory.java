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

    /** 该平台是否配置并启用了 MCP 发布（原生 MCP 通道）。 */
    public boolean enabledFor(String platform) {
        return enabledFor(platform, "mcp");
    }

    /**
     * 该平台指定通道是否启用。
     *
     * @param channel {@code mcp}（平台原生 MCP server）或 {@code rpa}（影刀 RPA 通道）
     */
    public boolean enabledFor(String platform, String channel) {
        if (!properties.isEnabled() || platform == null) {
            return false;
        }
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(platform.toLowerCase());
        if (cfg == null) {
            return false;
        }
        if ("rpa".equalsIgnoreCase(channel)) {
            return cfg.getRpa() != null && cfg.getRpa().isEnabled();
        }
        return cfg.isEnabled();
    }

    /** 取（或构建）该平台的原生 MCP 客户端。 */
    public Optional<McpClient> clientFor(String platform) {
        return clientFor(platform, "mcp");
    }

    /** 取（或构建）该平台指定通道的客户端（一个通道一个常驻进程/连接）。 */
    public Optional<McpClient> clientFor(String platform, String channel) {
        String normalizedChannel = "rpa".equalsIgnoreCase(channel) ? "rpa" : "mcp";
        if (!enabledFor(platform, normalizedChannel)) {
            return Optional.empty();
        }
        String key = platform.toLowerCase();
        PublishMcpProperties.PlatformMcp cfg = properties.getPlatforms().get(key);
        String cacheKey = key + ":" + normalizedChannel;
        return Optional.of(cache.computeIfAbsent(cacheKey,
                ignored -> build(key, normalizedChannel, cfg)));
    }

    /** 丢弃缓存（改完配置或进程挂掉后重建）。 */
    public void reset(String platform) {
        String key = platform == null ? "" : platform.toLowerCase();
        for (String channel : List.of("mcp", "rpa")) {
            McpClient removed = cache.remove(key + ":" + channel);
            if (removed != null) {
                removed.close();
                log.info("[MCP] 已重建 {} 的 {} 客户端", platform, channel);
            }
        }
    }

    private McpClient build(String platform, String channel, PublishMcpProperties.PlatformMcp cfg) {
        Duration initTimeout = Duration.ofMillis(Math.max(1000, properties.getInitializeTimeoutMs()));
        List<String> command;
        Map<String, String> env;
        String workDir;
        String url;
        String token;
        if ("rpa".equals(channel)) {
            PublishMcpProperties.Rpa rpa = cfg.getRpa();
            command = rpa.getCommand();
            env = rpa.getEnv();
            workDir = rpa.getWorkDir();
            url = null;
            token = null;
        } else {
            command = cfg.getCommand();
            env = cfg.getEnv();
            workDir = cfg.getWorkDir();
            url = cfg.getUrl();
            token = cfg.getToken();
        }
        String transport = "mcp".equals(channel)
                ? (cfg.getTransport() == null ? "http" : cfg.getTransport().trim().toLowerCase())
                : "stdio";
        McpTransport mcpTransport;
        if ("stdio".equals(transport)) {
            if (command == null || command.isEmpty()) {
                throw new McpClientException("平台 " + platform + " 的 " + channel + " 通道配置为 stdio 但没有 command");
            }
            mcpTransport = new StdioMcpTransport(List.copyOf(command), env, workDir);
        } else if ("http".equals(transport)) {
            if (url == null || url.isBlank()) {
                throw new McpClientException("平台 " + platform + " 配置为 http 传输但没有 url");
            }
            mcpTransport = new HttpMcpTransport(url, token, initTimeout);
        } else {
            throw new McpClientException("平台 " + platform + " 传输类型不支持：" + transport);
        }
        log.info("[MCP] 平台 {} 的 {} 通道使用 {}", platform, channel, mcpTransport.describe());
        return new McpClient(platform + ":" + channel, mcpTransport, objectMapper, initTimeout);
    }

    @PreDestroy
    public void closeAll() {
        cache.values().forEach(McpClient::close);
        cache.clear();
    }
}
