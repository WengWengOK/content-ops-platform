package com.contentops.common.quality;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 竞争模式配置属性（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>通过 {@code contentops.competitive.*} 在 application.yml 中绑定，控制
 * 哪些 Agent 阶段启用竞争模式（并行调用两次 LLM 并选择最佳结果）。
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>{@code
 * contentops:
 *   competitive:
 *     enabled: true
 *     stages:
 *       - content-creation
 *       - topic-planning
 * }</pre>
 *
 * @see CompetitiveModeService
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.competitive")
public class CompetitiveModeProperties {

    /** 是否启用竞争模式（关闭时所有阶段均使用单次调用） */
    private boolean enabled = false;

    /** 启用竞争模式的阶段列表（使用 AgentStage 的 code，如 "content-creation"） */
    private List<String> stages = new ArrayList<>();
}
