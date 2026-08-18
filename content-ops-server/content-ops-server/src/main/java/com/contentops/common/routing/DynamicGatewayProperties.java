package com.contentops.common.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态模型路由网关配置（P2 #10 大厂特色：多模型路由自动化）。
 *
 * <p>绑定到 application.yml 中的 {@code contentops.model-routing.dynamic-gateway}：
 * <pre>
 * contentops:
 *   model-routing:
 *     dynamic-gateway:
 *       enabled: false            # 默认关闭，渐进开启；关闭时走原阶段静态路由
 *       tiered-chatmodels:        # 三档模型名（cheap/strong/vision 对应的模型）
 *         cheap: "deepseek-chat"      # 轻量任务（分析/摘要/格式化/翻译）低成本
 *         strong: "deepseek-chat"     # 重任务（内容创作/复杂推理/优化）高质量
 *         vision: "qwen-vl-plus"      # 多模态视觉任务
 *       stage-tier:               # 按阶段 → 默认档位映射
 *         topic-planning: "strong"
 *         content-creation: "strong"
 *         image-design: "strong"
 *         publishing: "cheap"
 *         data-analysis: "cheap"
 *         optimization: "cheap"
 *       difficulty-estimation:    # 难度估算（超出估算阈值时自动升档）
 *         enabled: true
 *         prompt-chars-upgrade: 8000   # prompt 超过 8K 字符时从 cheap→strong
 *         vision-stage-codes: ["image-design"]  # 这些阶段码直接走 vision 档
 *       cost-optimization:        # 成本优化
 *         cheap-stage-max-tokens: 4096   # cheap 档硬上限（不可超过，防止 cost 爆炸）
 *         upgrade-on-budget-low: true    # 当账号剩余预算不足时强制走 cheap
 * </pre>
 *
 * <h3>路由决策优先级（从高到低）</h3>
 * <ol>
 *   <li><b>Vision 类阶段</b>（配置 difficulty-estimation.vision-stage-codes 命中）→ vision 档</li>
 *   <li><b>gateway 关闭</b> → 回退到原阶段静态路由（ModelRoutingProperties.stageOverrides）</li>
 *   <li><b>成本策略</b>（预算不足）→ 强制 cheap 档</li>
 *   <li><b>难度估算</b>（promptChars ≥ prompt-chars-upgrade）→ 升档到 strong</li>
 *   <li><b>阶段默认档位</b>（stage-tier 配置）→ cheap/strong</li>
 * </ol>
 *
 * <p><b>为什么 P2 #10 只做三档？</b>：当前只有单一 OpenAI 兼容供应商，
 * "按难度/成本动态路由"的最低可行形态就是 cheap/strong/vision 三档；
 * 等多供应商接入后，可将 tiered-chatmodels 扩展为按 provider+model 映射，无需再改路由决策逻辑。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.model-routing.dynamic-gateway")
public class DynamicGatewayProperties {

    /** 是否启用动态路由网关（默认关闭，渐进开启）。关闭时所有流量回退到原阶段静态路由。 */
    private boolean enabled = false;

    /** 三档模型名配置（cheap / strong / vision）。对应真实模型名，供 builder 构造 raw ChatModel。 */
    private Tiers tieredChatmodels = new Tiers();

    /** 按阶段 → 默认档位映射（cheap / strong / vision）。未指定的阶段回退到强模型，避免降级质量。 */
    private Map<String, String> stageTier = new HashMap<>() {{
        put("topic-planning", "strong");
        put("content-creation", "strong");
        put("image-design", "strong");
        put("publishing", "cheap");
        put("data-analysis", "cheap");
        put("optimization", "cheap");
    }};

    /** 难度估算：自动升档/降档的启发式阈值。 */
    private DifficultyEstimation difficultyEstimation = new DifficultyEstimation();

    /** 成本优化：预算 / Token 上限等控制。 */
    private CostOptimization costOptimization = new CostOptimization();

    @Data
    public static class Tiers {
        /** 便宜模型（小模型/低成本，适合格式化/翻译/摘要/分析） */
        private String cheap = "deepseek-chat";
        /** 强模型（大模型/高质量，适合内容创作/复杂推理/优化） */
        private String strong = "deepseek-chat";
        /** 多模态视觉模型（图像/视频理解） */
        private String vision = "qwen-vl-plus";
    }

    @Data
    public static class DifficultyEstimation {
        /** 是否启用难度估算（关闭时严格按 stage-tier）。 */
        private boolean enabled = true;
        /** prompt 字符阈值：≥ 此值则从 cheap 升档到 strong（长 prompt 往往意味着更复杂）。 */
        private int promptCharsUpgrade = 8000;
        /** 走 vision 档的阶段码（多模态视觉任务）。 */
        private String[] visionStageCodes = new String[]{"image-design"};
    }

    @Data
    public static class CostOptimization {
        /** cheap 档的 maxTokens 硬上限（防止成本爆炸）。 */
        private int cheapStageMaxTokens = 4096;
        /** 预算不足时强制走 cheap。 */
        private boolean upgradeOnBudgetLow = true;
    }
}
