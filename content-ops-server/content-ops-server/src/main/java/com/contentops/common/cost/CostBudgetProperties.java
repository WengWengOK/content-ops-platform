package com.contentops.common.cost;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 成本预算与熔断配置（P0 成本控制）。
 *
 * <p>通过 {@code contentops.cost-budget.*} 绑定：
 * <ul>
 *   <li>{@code workflow-token-budget}：单个工作流的 token 预算（含并行分支，按父工作流汇总）</li>
 *   <li>{@code workflow-cost-budget-usd}：单个工作流的估算成本预算（美元），0 表示不限制</li>
 *   <li>{@code circuit-open-failures}：连续失败（429/5xx 等）多少次后打开熔断器</li>
 *   <li>{@code circuit-open-seconds}：熔断打开持续时间（秒）</li>
 *   <li>{@code pricing}：按模型配置每百万 token 价格（美元），用于成本估算</li>
 * </ul>
 *
 * <p>熔断器为进程内实现，重启即复位；402 余额不足/401 鉴权失败会立即长时熔断，
 * 充值后重启服务或等待冷却期恢复。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.cost-budget")
public class CostBudgetProperties {

    /** 是否启用成本预算与熔断 */
    private boolean enabled = true;

    /** 单个工作流 token 预算（默认 50 万，覆盖一次完整多平台流程的合理上限） */
    private long workflowTokenBudget = 500_000;

    /** 单个工作流估算成本预算（美元），0 表示不限制 */
    private double workflowCostBudgetUsd = 3.0;

    /** 预算提醒比例（0~1）：用量达到预算的该比例时记录 WARN 提醒，不阻断调用 */
    private double warnRatio = 0.8;

    /** 连续失败多少次后打开熔断器 */
    private int circuitOpenFailures = 3;

    /** 熔断打开持续时间（秒） */
    private long circuitOpenSeconds = 60;

    /** 402/401 等致命错误的熔断时长（秒），通常比普通熔断更长 */
    private long fatalCircuitOpenSeconds = 600;

    /** 按模型名配置每百万 token 价格（美元）：input / output */
    private Map<String, ModelPrice> pricing = defaultPricing();

    @Data
    public static class ModelPrice {
        private double input = 0.0;
        private double output = 0.0;
    }

    private static Map<String, ModelPrice> defaultPricing() {
        Map<String, ModelPrice> map = new HashMap<>();
        map.put("deepseek-chat", price(0.27, 1.10));
        map.put("gpt-4o", price(2.50, 10.00));
        map.put("gpt-4o-mini", price(0.15, 0.60));
        return map;
    }

    private static ModelPrice price(double input, double output) {
        ModelPrice p = new ModelPrice();
        p.setInput(input);
        p.setOutput(output);
        return p;
    }
}
