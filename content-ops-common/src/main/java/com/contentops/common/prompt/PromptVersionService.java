package com.contentops.common.prompt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Prompt 版本管理与 A/B 测试决策服务（P1: Prompt 工程深度优化）。
 *
 * <p>核心职责：
 * <ol>
 *   <li><b>版本选择</b>：根据配置决定每个 Agent 使用哪个版本的 Prompt（v1/v2/v3）</li>
 *   <li><b>A/B 流量分配</b>：当 A/B 测试开启时，按 {@code hash(memoryId) % 100} 与
 *       流量分割比例比较，决定当前请求使用变体 A 还是 B</li>
 *   <li><b>固定变体</b>：当配置中指定了固定变体（A 或 B），则忽略流量分配直接使用</li>
 *   <li><b>Nacos 热更新预留</b>：{@link PromptVersionProperties} 通过 @ConfigurationProperties
 *       绑定，引入 Nacos Config 后可自动感知配置变更，无需重启服务</li>
 * </ol>
 *
 * <p><b>Nacos 集成方式：</b>
 * <ol>
 *   <li>引入依赖 {@code spring-cloud-starter-alibaba-nacos-config}</li>
 *   <li>在 bootstrap.yml 配置 {@code spring.cloud.nacos.config.*}</li>
 *   <li>在 Nacos 配置中心创建 Data ID，写入 {@code contentops.prompt.*} 配置</li>
 *   <li>添加 {@code @RefreshScope} 到本类即可实现热更新</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptVersionService {

    private final PromptVersionProperties properties;

    /**
     * 获取指定 Agent 当前应使用的 Prompt 版本号。
     *
     * @param agentKey Agent 标识（topic/content/analysis/image/publish/optimize）
     * @return 版本号（如 "v2"）
     */
    public String getVersion(String agentKey) {
        String version = properties.getVersion(agentKey);
        log.debug("[PromptVersion] Agent={}, version={}", agentKey, version);
        return version;
    }

    /**
     * 获取指定 Agent 当前请求应使用的 A/B 测试变体。
     *
     * <p>决策逻辑：
     * <ol>
     *   <li>若 A/B 测试未开启，返回配置的默认变体（通常为 A）</li>
     *   <li>若配置了固定变体（A 或 B），直接返回</li>
     *   <li>否则根据 {@code hash(memoryId) % 100 < trafficSplit} 判定使用 A 还是 B</li>
     * </ol>
     *
     * @param agentKey Agent 标识
     * @param memoryId 对话记忆 ID（用于 A/B 流量分配的哈希种子）
     * @return 变体标识（"A" 或 "B"）
     */
    public String getVariant(String agentKey, String memoryId) {
        // A/B 测试未开启，返回默认变体
        if (!properties.getAbTesting().isEnabled()) {
            String variant = properties.getFixedVariant(agentKey);
            return variant != null ? variant : "A";
        }

        // 配置了固定变体，直接返回
        String fixedVariant = properties.getFixedVariant(agentKey);
        if (fixedVariant != null) {
            log.debug("[PromptVersion] Agent={}, fixedVariant={}", agentKey, fixedVariant);
            return fixedVariant;
        }

        // 按流量比例自动分配
        int hash = Math.abs(memoryId.hashCode()) % 100;
        int split = properties.getAbTesting().getTrafficSplit();
        String variant = hash < split ? "A" : "B";
        log.debug("[PromptVersion] Agent={}, memoryId={}, hash={}, variant={}",
                agentKey, memoryId, hash, variant);
        return variant;
    }

    /**
     * 判断动态 Prompt 是否已启用。
     *
     * @return true 时应使用 PromptFragmentService 组装动态 Prompt；
     *         false 时回退到 @SystemMessage 注解中的静态 Prompt
     */
    public boolean isDynamicPromptEnabled() {
        return properties.isEnabled();
    }
}
