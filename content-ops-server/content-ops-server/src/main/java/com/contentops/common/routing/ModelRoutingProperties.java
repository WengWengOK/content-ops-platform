package com.contentops.common.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型路由配置属性（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>通过 {@code contentops.model-routing.*} 在 application.yml 中绑定，支持：
 * <ul>
 *   <li>全局默认模型与温度</li>
 *   <li>按 Agent 阶段（stage code）覆盖模型/温度/maxTokens</li>
 *   <li>按子阶段（如 {@code content-creation:outline}）细粒度覆盖</li>
 * </ul>
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>{@code
 * contentops:
 *   model-routing:
 *     enabled: true
 *     default-model: gpt-4o
 *     default-temperature: 0.8
 *     default-max-tokens: 4096
 *     stage-overrides:
 *       topic-planning:
 *         model: gpt-4o
 *         temperature: 0.8
 *       content-creation:
 *         model: gpt-4o
 *         temperature: 0.9
 *       image-design:
 *         model: gpt-4o
 *         temperature: 0.8
 *       publishing:
 *         model: gpt-4o-mini
 *         temperature: 0.3
 *       data-analysis:
 *         model: gpt-4o-mini
 *         temperature: 0.2
 *       optimization:
 *         model: gpt-4o-mini
 *         temperature: 0.3
 * }</pre>
 *
 * <p><b>子阶段覆盖示例：</b>在 {@code stage-overrides} 中使用
 * {@code {stageCode}:{subCode}} 作为 key，例如 {@code content-creation:outline}
 * 可以让大纲生成阶段使用不同的温度。
 *
 * @see ModelRoutingService
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.model-routing")
public class ModelRoutingProperties {

    /** 是否启用模型路由（关闭时所有 Agent 使用 default-model 与 default-temperature） */
    private boolean enabled = true;

    /** 默认模型名称 */
    private String defaultModel = "gpt-4o";

    /** 默认采样温度 */
    private double defaultTemperature = 0.8;

    /** 默认最大输出 token 数 */
    private int defaultMaxTokens = 4096;

    /** OpenAI API Key（接入 Agent 调用链后由本配置统一提供；必须通过环境变量注入） */
    private String apiKey = "sk-placeholder";

    /** OpenAI Base URL（LangChain4j 需要带 /v1 后缀） */
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * 按阶段/子阶段覆盖模型配置。
     *
     * <p>key 为 AgentStage 的 code（如 {@code "topic-planning"}），
     * 或子阶段的 fullCode（如 {@code "content-creation:outline"}）。
     * value 为该阶段的覆盖项，未指定的字段回退到默认值。
     */
    private Map<String, StageOverride> stageOverrides = new HashMap<>();

    /**
     * 单个阶段的模型覆盖配置项。
     *
     * <p>所有字段均为可选：未指定的字段将回退到全局默认值或阶段分类默认值。
     */
    @Data
    public static class StageOverride {

        /** 覆盖模型名称（如 gpt-4o-mini） */
        private String model;

        /** 覆盖采样温度 */
        private Double temperature;

        /** 覆盖最大输出 token 数 */
        private Integer maxTokens;
    }
}
