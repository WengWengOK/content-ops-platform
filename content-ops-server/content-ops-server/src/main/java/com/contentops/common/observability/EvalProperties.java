package com.contentops.common.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM-as-Judge 评测配置（contentops.evals.*），
 * 扩展 P2 #9 「Agent 自我改进闭环」相关开关。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.evals")
public class EvalProperties {

    /** 是否启用评测（流水线阶段完成后自动判分并落库） */
    private boolean enabled = true;

    /** 及格分（0-100），低于视为不通过 */
    private int threshold = 70;

    /** 是否启用门禁：低于阈值时标记该阶段评测失败（默认只记录不阻断）。
     * 当 {@code gateEnabled=true} 且 {@code selfImprove.autoOptimizationOnFail=true} 时，
     * 工作流 COMPLETED 事件会自动拉起 OptimizationAgent 独立阶段优化。 */
    private boolean gateEnabled = false;

    /** 判官模型档位：formatting（默认） */
    private String judgeModelTier = "formatting";

    /** 自我改进闭环控制（P2 #9 大厂特色：评测失败自动触发优化迭代）。 */
    private SelfImprove selfImprove = new SelfImprove();

    @Data
    public static class SelfImprove {

        /** 是否启用"评测失败自动触发 Optimization 独立阶段"（默认关闭，渐进开启）。
         * 启用前置条件：{@code gateEnabled=true} 且 evals.enabled=true。 */
        private boolean autoOptimizationOnFail = false;

        /** 触发最低分：passed=false 时，若 judge_score ≤ 此值才触发（默认值与 threshold 一致=70）。
         * 可设成比 threshold 更低的值，例如 50，仅在"严重不及格"时触发，避免噪声优化开销。 */
        private int triggerMaxScore = 70;

        /** 同一工作流最多自动触发优化次数（防止失败→优化→再失败→无限循环）。默认 1 次。 */
        private int maxAutoOptimizations = 1;

        /** 幂等防重标记过期时间（秒）。同一 workflowId 在该 TTL 内不会重复触发自动优化。
         * 存储于 Redis key {@code contentops:eval-auto-triggered:{workflowId}}。
         * Redis 不可用时退化为 JVM 内存 ConcurrentHashMap。 */
        private int dedupTtlSeconds = 3600;
    }
}
