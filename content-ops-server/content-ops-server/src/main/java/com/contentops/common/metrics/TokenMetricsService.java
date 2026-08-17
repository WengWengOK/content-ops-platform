package com.contentops.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer 自定义指标服务（P1: 弹性与可观测性）。
 *
 * <p>按多维度统计 LLM 调用的 token 消耗、费用、延迟和成功率：
 * <ul>
 *   <li>{@code contentops.llm.tokens.total} — Counter，按 workflowId/agentStage/tokenType 统计 token 消耗</li>
 *   <li>{@code contentops.llm.cost.total} — Counter，按 workflowId/agentStage 统计费用（美元）</li>
 *   <li>{@code contentops.llm.duration} — Timer，按 agentStage 统计 LLM 调用延迟</li>
 *   <li>{@code contentops.llm.calls.total} — Counter，按 agentStage/status 统计调用次数和成功率</li>
 * </ul>
 *
 * <p>指标通过 {@code /actuator/prometheus} 端点暴露，可被 Prometheus 抓取并在 Grafana 中可视化。
 */
@Slf4j
@Service
public class TokenMetricsService {

    private final MeterRegistry meterRegistry;

    /** 缓存 Counter 实例，避免重复创建 */
    private final ConcurrentHashMap<String, Counter> tokenCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> costCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> callCounters = new ConcurrentHashMap<>();

    /** GPT-4o 每百万 token 价格（美元），实际部署时从配置中心读取 */
    private static final double COST_PER_MILLION_INPUT_TOKENS = 2.50;
    private static final double COST_PER_MILLION_OUTPUT_TOKENS = 10.00;

    @Autowired
    public TokenMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        log.info("[TokenMetrics] Micrometer 指标服务已初始化，MeterRegistry: {}",
                meterRegistry.getClass().getSimpleName());
    }

    /**
     * 记录一次 LLM 调用的 token 消耗和费用。
     *
     * @param workflowId   工作流 ID
     * @param agentStage   Agent 阶段代码（如 "topic-planning"、"content-creation"）
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    public void recordTokenUsage(String workflowId, String agentStage,
                                 int inputTokens, int outputTokens) {
        // 输入 token 指标
        getOrCreateTokenCounter("input").increment(inputTokens);
        getOrCreateTokenCounter("output").increment(outputTokens);

        // 费用指标
        double cost = calculateCost(inputTokens, outputTokens);
        getOrCreateCostCounter(agentStage).increment(cost);

        log.info("[TokenMetrics] workflowId={}, stage={}, inputTokens={}, outputTokens={}, cost=${:.4f}",
                workflowId, agentStage, inputTokens, outputTokens, cost);
    }

    /**
     * 记录一次 Agent 调用的成功/失败状态。
     *
     * @param agentStage Agent 阶段代码
     * @param success    是否成功
     */
    public void recordAgentCall(String agentStage, boolean success) {
        String status = success ? "success" : "failure";
        getOrCreateCallCounter(agentStage, status).increment();
    }

    /**
     * 记录一次 Agent 调用的延迟。
     *
     * @param agentStage Agent 阶段代码
     * @param duration   耗时
     */
    public void recordAgentDuration(String agentStage, Duration duration) {
        Timer timer = Timer.builder("contentops.agent.duration")
                .description("Agent 调用延迟")
                .tag("agentStage", agentStage)
                .register(meterRegistry);
        timer.record(duration);

        log.debug("[TokenMetrics] stage={}, duration={}ms", agentStage, duration.toMillis());
    }

    /**
     * 创建一个计时器，供 try-with-resources 模式使用。
     *
     * <pre>{@code
     * try (Timer.Sample sample = TokenMetricsService.startTimer()) {
     *     // 调用 Agent...
     *     tokenMetricsService.recordAgentDuration("content-creation", sample.stop());
     * }
     * }</pre>
     */
    public static Timer.Sample startTimer() {
        return Timer.start();
    }

    /**
     * 计算一次 LLM 调用的费用（美元）。
     */
    private double calculateCost(int inputTokens, int outputTokens) {
        return (inputTokens / 1_000_000.0) * COST_PER_MILLION_INPUT_TOKENS
                + (outputTokens / 1_000_000.0) * COST_PER_MILLION_OUTPUT_TOKENS;
    }

    /**
     * 获取或创建 token 计数器。
     */
    private Counter getOrCreateTokenCounter(String tokenType) {
        return tokenCounters.computeIfAbsent(tokenType, type ->
                Counter.builder("contentops.llm.tokens.total")
                        .description("LLM 调用 token 总消耗")
                        .tag("tokenType", type)
                        .register(meterRegistry));
    }

    /**
     * 获取或创建费用计数器。
     */
    private Counter getOrCreateCostCounter(String agentStage) {
        return costCounters.computeIfAbsent(agentStage, stage ->
                Counter.builder("contentops.llm.cost.total")
                        .description("LLM 调用总费用（美元）")
                        .tag("agentStage", stage)
                        .register(meterRegistry));
    }

    /**
     * 获取或创建调用次数计数器。
     */
    private Counter getOrCreateCallCounter(String agentStage, String status) {
        String key = agentStage + ":" + status;
        return callCounters.computeIfAbsent(key, k ->
                Counter.builder("contentops.llm.calls.total")
                        .description("LLM 调用总次数")
                        .tag("agentStage", agentStage)
                        .tag("status", status)
                        .register(meterRegistry));
    }
}
