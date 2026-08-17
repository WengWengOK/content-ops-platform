package com.contentops.common.cost;

import com.contentops.common.cost.CostGuardBlockedException.Reason;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 工作流级成本预算 + 全局熔断器（P0 成本控制）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>预算预检</b>：每次 LLM 调用前检查当前工作流 token/成本是否超限，超限即阻断，
 *       不再发起真实调用（防止余额被打穿）；</li>
 *   <li><b>用量记账</b>：调用成功后从 {@link TokenUsage} 记账（按父工作流聚合，多平台分支
 *       共享同一预算）；</li>
 *   <li><b>熔断器</b>：连续失败（429/5xx）达阈值打开熔断；402 余额不足/401 鉴权失败视为
 *       致命错误立即长时熔断，避免对失效 Key 反复重试。</li>
 * </ul>
 *
 * <p>预算与熔断状态为进程内实现（重启复位）。工作流上下文通过 {@link ThreadLocal} 传递，
 * 由 {@code LocalAgentGateway} 在调用 Agent 前设置。
 */
@Slf4j
@Component
public class WorkflowCostGuard {

    private final CostBudgetProperties properties;

    /** 当前线程正在执行的工作流 ID（由 LocalAgentGateway 设置） */
    private static final ThreadLocal<String> CURRENT_WORKFLOW = new ThreadLocal<>();

    /** 工作流已用 token（key 为父工作流 ID） */
    private final Map<String, AtomicLong> workflowTokens = new ConcurrentHashMap<>();

    /** 工作流已用估算成本（美元，key 为父工作流 ID） */
    private final Map<String, AtomicLong> workflowCostMicroUsd = new ConcurrentHashMap<>();

    /** 已触发过预算提醒的工作流（避免重复刷日志） */
    private final Map<String, Boolean> budgetWarned = new ConcurrentHashMap<>();

    /** 全局连续失败次数（429/5xx） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** 熔断打开截止时间戳（毫秒）；0 表示未熔断 */
    private volatile long circuitOpenUntilMillis = 0;

    /** 是否处于致命错误熔断（402/401），需要更长冷却或人工复位 */
    private volatile boolean fatalCircuit = false;

    /** 全局累计 token（仅观测） */
    private final AtomicLong globalTokens = new AtomicLong(0);

    public WorkflowCostGuard(CostBudgetProperties properties) {
        this.properties = properties;
    }

    // ──────────────── 上下文传递 ────────────────

    public static String currentWorkflowId() {
        return CURRENT_WORKFLOW.get();
    }

    public <T> T withWorkflow(String workflowId, Supplier<T> action) {
        String previous = CURRENT_WORKFLOW.get();
        CURRENT_WORKFLOW.set(workflowId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT_WORKFLOW.remove();
            } else {
                CURRENT_WORKFLOW.set(previous);
            }
        }
    }

    public void runWithWorkflow(String workflowId, Runnable action) {
        withWorkflow(workflowId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 预算 key：多平台分支（{parent}:{platform}）统一归集到父工作流。
     */
    private String budgetKey(String workflowId) {
        if (workflowId == null) {
            return null;
        }
        int idx = workflowId.indexOf(':');
        return idx > 0 ? workflowId.substring(0, idx) : workflowId;
    }

    // ──────────────── 预算/熔断预检 ────────────────

    /**
     * 调用前预检：熔断打开或工作流预算用尽时抛出 {@link CostGuardBlockedException}。
     *
     * @param workflowId 当前工作流 ID（可为 null，仅做熔断检查）
     */
    public void checkBlocked(String workflowId) {
        if (!properties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < circuitOpenUntilMillis) {
            long remaining = (circuitOpenUntilMillis - now) / 1000;
            String detail = fatalCircuit ? "模型供应商余额不足或鉴权失败" : "连续失败触发熔断";
            log.warn("[CostGuard] 熔断打开，剩余 {}s（{}）", remaining, detail);
            throw new CostGuardBlockedException(Reason.CIRCUIT_OPEN,
                    CostGuardBlockedException.CIRCUIT_MARKER + " 模型调用被熔断（" + detail + "），剩余 "
                            + remaining + "s，请稍后重试");
        }

        String key = budgetKey(workflowId);
        if (key == null) {
            return;
        }
        warnIfNearBudget(key, workflowTokens.get(key), workflowCostMicroUsd.get(key));
        AtomicLong tokens = workflowTokens.get(key);
        if (tokens != null && tokens.get() >= properties.getWorkflowTokenBudget()) {
            throw new CostGuardBlockedException(Reason.BUDGET_EXCEEDED,
                    CostGuardBlockedException.BUDGET_MARKER
                            + " 工作流预算已用尽（已用 " + tokens.get() + "/"
                            + properties.getWorkflowTokenBudget() + " tokens），工作流已终止");
        }
        if (properties.getWorkflowCostBudgetUsd() > 0) {
            AtomicLong costMicro = workflowCostMicroUsd.get(key);
            if (costMicro != null && costMicro.get() >= properties.getWorkflowCostBudgetUsd() * 1_000_000) {
                throw new CostGuardBlockedException(Reason.BUDGET_EXCEEDED,
                        CostGuardBlockedException.BUDGET_MARKER
                                + " 工作流成本预算已用尽（$"
                                + String.format("%.4f", costMicro.get() / 1_000_000.0) + "/$"
                                + properties.getWorkflowCostBudgetUsd() + "），工作流已终止");
            }
        }
    }

    // ──────────────── 用量记账 ────────────────

    /**
     * 调用成功后记账 token 用量与估算成本。
     */
    public void recordUsage(String workflowId, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        long inputTokens = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        long outputTokens = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
        long total = inputTokens + outputTokens;
        globalTokens.addAndGet(total);

        String key = budgetKey(workflowId);
        if (key == null) {
            return;
        }
        workflowTokens.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(total);
        long costMicro = estimateCostMicro(inputTokens, outputTokens);
        if (costMicro > 0) {
            workflowCostMicroUsd.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(costMicro);
        }
        if (workflowTokens.size() > 5000) {
            // 防止长期运行内存膨胀；历史预算状态丢失可接受（预算仅影响后续调用）
            workflowTokens.clear();
            workflowCostMicroUsd.clear();
            log.warn("[CostGuard] 用量表超过上限已清空");
        }
        log.debug("[CostGuard] 记账 workflow={}, input={}, output={}, total={}, costUsd≈{:.6f}",
                key, inputTokens, outputTokens, total, costMicro / 1_000_000.0);
    }

    /**
     * 按模型价格估算成本（微美元）。TokenUsage 不携带模型名，取配置价格的保守上限。
     */
    private long estimateCostMicro(long inputTokens, long outputTokens) {
        double maxInput = 0;
        double maxOutput = 0;
        for (CostBudgetProperties.ModelPrice price : properties.getPricing().values()) {
            maxInput = Math.max(maxInput, price.getInput());
            maxOutput = Math.max(maxOutput, price.getOutput());
        }
        if (maxInput <= 0 && maxOutput <= 0) {
            return 0;
        }
        double usd = inputTokens / 1_000_000.0 * maxInput
                + outputTokens / 1_000_000.0 * maxOutput;
        return (long) (usd * 1_000_000);
    }

    // ──────────────── 熔断 ────────────────

    /**
     * 记录一次调用失败：402 余额不足/401 鉴权失败 → 长时熔断；429/5xx → 累计计数。
     */
    public void recordFailure(Throwable error) {
        if (!properties.isEnabled()) {
            return;
        }
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (isFatal(message)) {
            long until = System.currentTimeMillis() + properties.getFatalCircuitOpenSeconds() * 1000;
            circuitOpenUntilMillis = Math.max(circuitOpenUntilMillis, until);
            fatalCircuit = true;
            consecutiveFailures.set(0);
            log.error("[CostGuard] 致命错误触发长时熔断：{}", message);
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= properties.getCircuitOpenFailures()) {
            circuitOpenUntilMillis = System.currentTimeMillis()
                    + properties.getCircuitOpenSeconds() * 1000;
            fatalCircuit = false;
            consecutiveFailures.set(0);
            log.error("[CostGuard] 连续 {} 次失败，熔断打开 {}s：{}",
                    failures, properties.getCircuitOpenSeconds(), message);
        } else {
            log.warn("[CostGuard] 调用失败（{}/{}）：{}", failures,
                    properties.getCircuitOpenFailures(), message);
        }
    }

    private boolean isFatal(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("402") || m.contains("payment required")
                || m.contains("insufficient balance")
                || m.contains("401") || m.contains("unauthorized")
                || m.contains("authentication") || m.contains("invalid api key");
    }

    /** 人工复位熔断与工作流用量（重启同样会复位）。 */
    public void reset() {
        circuitOpenUntilMillis = 0;
        fatalCircuit = false;
        consecutiveFailures.set(0);
        workflowTokens.clear();
        workflowCostMicroUsd.clear();
        budgetWarned.clear();
        log.info("[CostGuard] 熔断与用量已复位");
    }

    /** 工作流进入终态后清理预算状态，避免内存膨胀。 */
    public void onWorkflowTerminal(String workflowId) {
        String key = budgetKey(workflowId);
        if (key != null) {
            workflowTokens.remove(key);
            workflowCostMicroUsd.remove(key);
            budgetWarned.remove(key);
        }
    }

    /**
     * 预算提醒：用量达到预算 warn-ratio 时记录一次 WARN（不阻断调用）。
     */
    private void warnIfNearBudget(String key, AtomicLong tokens, AtomicLong costMicro) {
        double ratio = properties.getWarnRatio();
        if (ratio <= 0 || ratio >= 1) {
            return;
        }
        StringBuilder detail = new StringBuilder();
        if (tokens != null && properties.getWorkflowTokenBudget() > 0
                && tokens.get() >= properties.getWorkflowTokenBudget() * ratio) {
            detail.append("token 已达预算 ").append((int) (ratio * 100)).append("%（")
                    .append(tokens.get()).append("/").append(properties.getWorkflowTokenBudget())
                    .append("）");
        }
        if (costMicro != null && properties.getWorkflowCostBudgetUsd() > 0
                && costMicro.get() >= properties.getWorkflowCostBudgetUsd() * 1_000_000 * ratio) {
            if (!detail.isEmpty()) {
                detail.append("；");
            }
            detail.append("成本已达预算 ").append((int) (ratio * 100)).append("%");
        }
        if (!detail.isEmpty() && budgetWarned.putIfAbsent(key, true) == null) {
            log.warn("[CostGuard] 预算提醒（工作流 {}）：{}，请关注 token 消耗", key, detail);
        }
    }

    // ──────────────── 观测 ────────────────

    public long workflowTokens(String workflowId) {
        AtomicLong v = workflowTokens.get(budgetKey(workflowId));
        return v == null ? 0 : v.get();
    }

    public long globalTokens() {
        return globalTokens.get();
    }

    public boolean isCircuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntilMillis;
    }
}
