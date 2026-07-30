package com.contentops.common.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Nacos MCP 工具发现配置。
 *
 * <p>通过 application.yml 绑定，前缀为 {@code contentops.mcp.nacos}：
 * <pre>
 * contentops:
 *   mcp:
 *     nacos:
 *       enabled: false                    # 是否启用 Nacos MCP 工具发现（默认关闭）
 *       server-addr: 127.0.0.1:8848       # Nacos 服务地址（模拟，不实际连接）
 *       namespace: public                  # 命名空间
 *       service-name: content-ops-mcp     # 注册的服务名称
 * </pre>
 *
 * <p>注意：当前实现使用本地 Map 模拟 Nacos 服务注册与发现，
 * 不依赖实际的 Nacos 客户端。启用后可将本服务的 MCP 工具注册到模拟注册中心，
 * 供其他服务通过 {@link NacosMcpRegistry#discoverTools} 发现。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.mcp.nacos")
public class NacosMcpProperties {

    /** 是否启用 Nacos MCP 工具发现（默认关闭） */
    private boolean enabled = false;

    /** Nacos 服务地址（模拟，不实际连接） */
    private String serverAddr = "127.0.0.1:8848";

    /** 命名空间 */
    private String namespace = "public";

    /** 注册的服务名称 */
    private String serviceName = "content-ops-mcp";
}
