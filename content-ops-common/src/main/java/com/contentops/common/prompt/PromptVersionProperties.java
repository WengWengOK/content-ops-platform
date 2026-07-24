package com.contentops.common.prompt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Prompt 版本管理与 A/B 测试配置（P1: Prompt 工程深度优化）。
 *
 * <p>通过 {@code contentops.prompt.*} 在 application.yml 中配置，支持：
 * <ul>
 *   <li>每个 Agent 独立的 Prompt 版本号（v1/v2/v3...）</li>
 *   <li>A/B 测试变体选择（A/B），可按 hash(memoryId) 自动分配或固定指定</li>
 *   <li>版本切换时不重启服务（设计预留 Nacos 配置中心热更新接口）</li>
 * </ul>
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>{@code
 * contentops:
 *   prompt:
 *     enabled: true
 *     default-version: v2
 *     default-variant: A
 *     ab-testing:
 *       enabled: true
 *       traffic-split: 50   # A/B 流量分配比例（%），50 表示各一半
 *     agents:
 *       topic:
 *         version: v2
 *         variant: A
 *       content:
 *         version: v2
 *         variant: B
 *       analysis:
 *         version: v2
 *         variant: A
 *       image:
 *         version: v2
 *         variant: A
 *       publish:
 *         version: v2
 *         variant: A
 *       optimize:
 *         version: v2
 *         variant: A
 * }</pre>
 *
 * <p><b>Nacos 集成预留：</b>当项目引入 {@code spring-cloud-starter-alibaba-nacos-config}
 * 后，只需将 {@code contentops.prompt} 前缀的配置放入 Nacos 配置中心，
 * 即可实现不重启服务的 Prompt 热更新和 A/B 测试流量切换。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.prompt")
public class PromptVersionProperties {

    /** 是否启用动态 Prompt 版本管理（关闭时使用 @SystemMessage 注解中的静态 Prompt） */
    private boolean enabled = true;

    /** 默认 Prompt 版本号 */
    private String defaultVersion = "v2";

    /** 默认 A/B 测试变体（A 或 B） */
    private String defaultVariant = "A";

    /** A/B 测试配置 */
    private AbTesting abTesting = new AbTesting();

    /** 各 Agent 的独立版本配置 */
    private Map<String, AgentVersionConfig> agents;

    /**
     * A/B 测试配置项。
     */
    @Data
    public static class AbTesting {
        /** 是否启用 A/B 测试 */
        private boolean enabled = false;

        /** A/B 流量分配比例（%），50 表示 A/B 各 50% 流量 */
        private int trafficSplit = 50;
    }

    /**
     * 单个 Agent 的版本配置。
     */
    @Data
    public static class AgentVersionConfig {
        /** Prompt 版本号 */
        private String version;

        /** A/B 测试变体（A 或 B），为 null 时根据流量比例自动分配 */
        private String variant;
    }

    /**
     * 获取指定 Agent 的版本号，未配置时返回默认版本。
     */
    public String getVersion(String agentKey) {
        if (agents != null && agents.containsKey(agentKey)) {
            AgentVersionConfig config = agents.get(agentKey);
            if (config.getVersion() != null && !config.getVersion().isBlank()) {
                return config.getVersion();
            }
        }
        return defaultVersion;
    }

    /**
     * 获取指定 Agent 的固定变体，未配置或为 auto 时返回 null（表示需自动分配）。
     */
    public String getFixedVariant(String agentKey) {
        if (agents != null && agents.containsKey(agentKey)) {
            AgentVersionConfig config = agents.get(agentKey);
            if (config.getVariant() != null
                    && !config.getVariant().isBlank()
                    && !"auto".equalsIgnoreCase(config.getVariant())) {
                return config.getVariant();
            }
        }
        if ("auto".equalsIgnoreCase(defaultVariant)) {
            return null;
        }
        return defaultVariant;
    }
}
