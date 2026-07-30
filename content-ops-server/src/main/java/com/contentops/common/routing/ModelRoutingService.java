package com.contentops.common.routing;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.SubStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 模型路由服务（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>核心职责：根据 Agent 阶段（及子阶段）返回最适合的 {@link ModelConfig}，
 * 实现「创意类任务用高温度大模型，格式化类任务用低温度模型」的路由策略。
 *
 * <h3>路由策略</h3>
 * <ul>
 *   <li><b>创意类阶段</b>（{@link AgentStage#TOPIC_PLANNING}、
 *       {@link AgentStage#CONTENT_CREATION}、{@link AgentStage#IMAGE_DESIGN}）
 *       → gpt-4o, temperature 0.8（高温度，激发创造力）</li>
 *   <li><b>格式化/分析类阶段</b>（{@link AgentStage#PUBLISHING}、
 *       {@link AgentStage#DATA_ANALYSIS}、{@link AgentStage#OPTIMIZATION}）
 *       → gpt-4o-mini, temperature 0.3（低温度，保证确定性）</li>
 * </ul>
 *
 * <h3>配置优先级</h3>
 * <ol>
 *   <li>子阶段覆盖（{@code stage-overrides} 中 key = "{stage}:{sub}"）</li>
 *   <li>阶段覆盖（{@code stage-overrides} 中 key = "{stage}"）</li>
 *   <li>阶段分类默认值（创意类 / 格式化类）</li>
 *   <li>全局默认值（{@code default-model} / {@code default-temperature}）</li>
 * </ol>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 在 Agent 配置类中读取路由配置
 * ModelConfig config = modelRoutingService.getModelConfig(AgentStage.CONTENT_CREATION);
 * // 使用 config.getModelName() / config.getTemperature() 构建 OpenAiChatModel
 * }</pre>
 *
 * @see ModelRoutingProperties
 * @see ModelConfig
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRoutingService {

    private final ModelRoutingProperties properties;

    // ──────────────── 创意类阶段默认参数 ────────────────
    private static final String CREATIVE_MODEL = "gpt-4o";
    private static final double CREATIVE_TEMPERATURE = 0.8;
    private static final int CREATIVE_MAX_TOKENS = 4096;

    // ──────────────── 格式化类阶段默认参数 ────────────────
    private static final String FORMATTING_MODEL = "gpt-4o-mini";
    private static final double FORMATTING_TEMPERATURE = 0.3;
    private static final int FORMATTING_MAX_TOKENS = 4096;

    /**
     * 判断指定阶段是否为创意类任务。
     *
     * <p>创意类阶段包括：选题策划、内容创作、配图设计。
     *
     * @param stage Agent 阶段
     * @return true 表示该阶段需要高温度大模型激发创造力
     */
    public boolean isCreativeStage(AgentStage stage) {
        return switch (stage) {
            case TOPIC_PLANNING, CONTENT_CREATION, IMAGE_DESIGN -> true;
            default -> false;
        };
    }

    /**
     * 获取指定 Agent 阶段的模型配置。
     *
     * <p>查找顺序：阶段覆盖 → 分类默认值 → 全局默认值。
     *
     * @param stage Agent 阶段
     * @return 模型配置，永不为 null
     */
    public ModelConfig getModelConfig(AgentStage stage) {
        // 路由未启用时，返回全局默认配置
        if (!properties.isEnabled()) {
            log.debug("[ModelRouting] 路由未启用，stage={} 使用全局默认: model={}, temp={}",
                    stage.getCode(), properties.getDefaultModel(), properties.getDefaultTemperature());
            return buildGlobalDefault();
        }

        // 1. 检查阶段级覆盖配置
        ModelRoutingProperties.StageOverride override = lookupOverride(stage.getCode());
        if (override != null) {
            ModelConfig config = buildFromOverride(override, stage);
            log.debug("[ModelRouting] stage={} 使用阶段覆盖: model={}, temp={}, creative={}",
                    stage.getCode(), config.getModelName(), config.getTemperature(), config.isCreative());
            return config;
        }

        // 2. 使用分类默认值
        ModelConfig config = buildClassificationDefault(stage);
        log.debug("[ModelRouting] stage={} 使用分类默认: model={}, temp={}, creative={}",
                stage.getCode(), config.getModelName(), config.getTemperature(), config.isCreative());
        return config;
    }

    /**
     * 获取指定 Agent 阶段及子阶段的模型配置。
     *
     * <p>查找顺序：子阶段覆盖（"{stage}:{sub}"）→ 阶段覆盖（"{stage}"）→ 分类默认值 → 全局默认值。
     *
     * @param stage    Agent 阶段
     * @param subStage 子阶段（为 null 时等价于 {@link #getModelConfig(AgentStage)}）
     * @return 模型配置，永不为 null
     */
    public ModelConfig getModelConfig(AgentStage stage, SubStage subStage) {
        // 路由未启用或无子阶段时，回退到阶段级路由
        if (!properties.isEnabled() || subStage == null) {
            return getModelConfig(stage);
        }

        // 1. 检查子阶段级覆盖配置（如 "content-creation:outline"）
        ModelRoutingProperties.StageOverride subOverride = lookupOverride(subStage.fullCode());
        if (subOverride != null) {
            ModelConfig config = buildFromOverride(subOverride, stage);
            log.debug("[ModelRouting] stage={}, subStage={} 使用子阶段覆盖: model={}, temp={}",
                    stage.getCode(), subStage.getCode(), config.getModelName(), config.getTemperature());
            return config;
        }

        // 2. 回退到阶段级路由
        return getModelConfig(stage);
    }

    /**
     * 判断模型路由是否已启用。
     *
     * @return true 时 Agent 应读取路由配置选择模型；false 时使用全局默认
     */
    public boolean isRoutingEnabled() {
        return properties.isEnabled();
    }

    // ──────────────── 内部工具方法 ────────────────

    /**
     * 从 stageOverrides 中查找指定 key 的覆盖配置。
     */
    private ModelRoutingProperties.StageOverride lookupOverride(String key) {
        if (properties.getStageOverrides() == null || key == null) {
            return null;
        }
        return properties.getStageOverrides().get(key);
    }

    /**
     * 构建全局默认配置（路由未启用时使用）。
     */
    private ModelConfig buildGlobalDefault() {
        return ModelConfig.builder()
                .modelName(properties.getDefaultModel())
                .temperature(properties.getDefaultTemperature())
                .maxTokens(properties.getDefaultMaxTokens())
                .creative(true) // 默认按创意类处理
                .provider("openai")
                .build();
    }

    /**
     * 根据阶段分类（创意类 / 格式化类）构建默认配置。
     */
    private ModelConfig buildClassificationDefault(AgentStage stage) {
        if (isCreativeStage(stage)) {
            return ModelConfig.builder()
                    .modelName(CREATIVE_MODEL)
                    .temperature(CREATIVE_TEMPERATURE)
                    .maxTokens(CREATIVE_MAX_TOKENS)
                    .creative(true)
                    .provider("openai")
                    .build();
        } else {
            return ModelConfig.builder()
                    .modelName(FORMATTING_MODEL)
                    .temperature(FORMATTING_TEMPERATURE)
                    .maxTokens(FORMATTING_MAX_TOKENS)
                    .creative(false)
                    .provider("openai")
                    .build();
        }
    }

    /**
     * 从覆盖配置项构建 ModelConfig，未指定的字段回退到分类默认值。
     */
    private ModelConfig buildFromOverride(ModelRoutingProperties.StageOverride override, AgentStage stage) {
        boolean creative = isCreativeStage(stage);

        // 模型名称：覆盖值 → 分类默认 → 全局默认
        String model = override.getModel();
        if (model == null || model.isBlank()) {
            model = creative ? CREATIVE_MODEL : FORMATTING_MODEL;
        }

        // 温度：覆盖值 → 分类默认 → 全局默认
        double temperature;
        if (override.getTemperature() != null) {
            temperature = override.getTemperature();
        } else {
            temperature = creative ? CREATIVE_TEMPERATURE : FORMATTING_TEMPERATURE;
        }

        // maxTokens：覆盖值 → 全局默认
        int maxTokens = override.getMaxTokens() != null
                ? override.getMaxTokens()
                : properties.getDefaultMaxTokens();

        return ModelConfig.builder()
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .creative(creative)
                .provider("openai")
                .build();
    }
}
