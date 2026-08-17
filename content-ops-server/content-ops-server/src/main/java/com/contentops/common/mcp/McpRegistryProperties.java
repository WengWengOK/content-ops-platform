package com.contentops.common.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 注册中心配置。
 *
 * <p>通过 application.yml 绑定，前缀为 {@code contentops.mcp}：
 * <pre>
 * contentops:
 *   mcp:
 *     enabled: true          # 是否启用 MCP 协议支持
 *     registry-port: 8090    # MCP 注册中心端口（用于服务发现标识）
 *     auto-scan: true        # 是否启动时自动扫描所有 @Tool 方法
 * </pre>
 *
 * <p>各子模块通过 {@code @ConditionalOnProperty(name = "contentops.mcp.enabled",
 * havingValue = "true")} 控制是否激活 MCP 相关组件。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.mcp")
public class McpRegistryProperties {

    /** 是否启用 MCP 协议支持（影响 Endpoint、拦截器、平台集成等组件的加载） */
    private boolean enabled = true;

    /** MCP 注册中心端口（用于服务发现标识，不实际监听） */
    private int registryPort = 8090;

    /** 是否在 Spring 启动后自动扫描所有 @Tool 注解方法并注册到 McpToolRegistry */
    private boolean autoScan = true;
}
