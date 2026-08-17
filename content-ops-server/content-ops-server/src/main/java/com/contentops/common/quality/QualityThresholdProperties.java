package com.contentops.common.quality;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 质量阈值配置属性（P2 v2.1.0: 多模型路由与质量评估，P2 优化: 权重配置化 + 指数退避）。
 *
 * <p>通过 {@code contentops.quality.*} 在 application.yml 中绑定，控制质量评估的
 * 最低阈值、三维评分权重和自动重试行为（含指数退避）。
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>{@code
 * contentops:
 *   quality:
 *     enabled: true
 *     min-score: 60
 *     auto-retry: true
 *     max-retries: 2
 *     retry-backoff-ms: 1000
 *     retry-backoff-multiplier: 2.0
 *     weights:
 *       logic: 0.4
 *       readability: 0.3
 *       originality: 0.3
 *     stage-weights:
 *       data-analysis:
 *         logic: 0.3
 *         readability: 0.2
 *         originality: 0.5
 * }</pre>
 *
 * @see QualityAssessmentService
 * @see AutoRetryService
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.quality")
public class QualityThresholdProperties {

    /** 是否启用质量评估（关闭时跳过质量评分检查） */
    private boolean enabled = true;

    /** 最低质量阈值（0-100），总分低于此值时触发自动重试 */
    private int minScore = 60;

    /** 是否启用低分自动重试 */
    private boolean autoRetry = true;

    /** 自动重试最大次数（不含首次调用，即总共最多调用 maxRetries+1 次） */
    private int maxRetries = 2;

    /** 重试间隔初始毫秒数（指数退避起始值） */
    private long retryBackoffMs = 1000;

    /** 指数退避乘数（每次重试间隔 = 上次间隔 × multiplier） */
    private double retryBackoffMultiplier = 2.0;

    /** 默认三维评分权重 */
    private Weights weights = new Weights();

    /** 阶段差异化权重（key 为 AgentStage 的 code，如 "data-analysis"） */
    private java.util.Map<String, Weights> stageWeights = new java.util.HashMap<>();

    /**
     * 三维评分权重配置。
     * <p>三者之和应为 1.0，若不为 1.0 则在运行时自动归一化。
     */
    @Data
    public static class Weights {
        /** 逻辑性权重（默认 0.4） */
        private double logic = 0.4;
        /** 可读性权重（默认 0.3） */
        private double readability = 0.3;
        /** 原创性权重（默认 0.3） */
        private double originality = 0.3;

        /**
         * 计算归一化后的逻辑性权重。
         */
        public double normalizedLogic() {
            double sum = logic + readability + originality;
            return sum > 0 ? logic / sum : 0;
        }

        /**
         * 计算归一化后的可读性权重。
         */
        public double normalizedReadability() {
            double sum = logic + readability + originality;
            return sum > 0 ? readability / sum : 0;
        }

        /**
         * 计算归一化后的原创性权重。
         */
        public double normalizedOriginality() {
            double sum = logic + readability + originality;
            return sum > 0 ? originality / sum : 0;
        }
    }

    /**
     * 获取指定阶段的评分权重。若阶段有差异化配置则返回该配置，否则返回默认权重。
     *
     * @param stageCode Agent 阶段 code（如 "data-analysis"）
     * @return 该阶段的评分权重配置
     */
    public Weights getWeightsForStage(String stageCode) {
        if (stageCode != null && stageWeights.containsKey(stageCode)) {
            return stageWeights.get(stageCode);
        }
        return weights;
    }
}
