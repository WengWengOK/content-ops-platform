package com.contentops.common.llmops;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.SubStage;
import com.contentops.common.routing.ModelConfig;
import com.contentops.common.routing.ModelRoutingService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带降级策略的模型路由器。
 *
 * <p>在现有 {@link ModelRoutingService} 的基础上增强以下能力，提供生产级的弹性模型路由：
 * <ul>
 *   <li><b>模型降级链</b>：主模型不可用时按链路自动降级（如 gpt-4o → gpt-4o-mini → gpt-3.5-turbo）</li>
 *   <li><b>熔断器</b>：单模型连续失败达阈值时自动熔断，并在冷却期后探测恢复</li>
 *   <li><b>限流</b>：按模型设置 QPS 上限，超限时降级到下一个可用模型</li>
 *   <li><b>成本优化</b>：低优先级任务自动选择低成本模型</li>
 * </ul>
 *
 * <h3>路由决策流程</h3>
 * <ol>
 *   <li>根据 {@link AgentStage}（及子阶段）通过 {@link ModelRoutingService} 获取主模型配置</li>
 *   <li>应用成本优化：低优先级任务替换为低成本模型</li>
 *   <li>按降级链依次检查：熔断器是否打开 → QPS 限流是否超限</li>
 *   <li>返回第一个可用的模型，标记是否触发了降级及原因</li>
 *   <li>调用方在调用结束后通过 {@link #recordResult} 反馈成功/失败，驱动熔断器状态机</li>
 * </ol>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>所有候选模型均被熔断或限流时，返回降级链末端模型（尽力而为），并标注 reason</li>
 *   <li>路由器未启用时，直接返回 {@link ModelRoutingService} 的原始配置（透传）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ModelRouteResult result = modelRouterWithFallback.route(
 *         AgentStage.CONTENT_CREATION, SubStage.OUTLINE, TaskPriority.LOW);
 * // 使用 result.getSelectedModel() 构建 OpenAiChatModel 并调用
 * try {
 *     String content = callLlm(result.getSelectedModel(), prompt);
 *     modelRouterWithFallback.recordResult(result.getSelectedModel(), true);
 * } catch (Exception e) {
 *     modelRouterWithFallback.recordResult(result.getSelectedModel(), false);
 * }
 * }</pre>
 *
 * @see ModelRoutingService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRouterWithFallback {

    private final ModelRoutingService modelRoutingService;
    private final RouterProperties properties;

    /** 各模型的熔断器状态（按模型名索引） */
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    /** 各模型的限流计数器（按模型名索引） */
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    /**
     * 路由模型（含子阶段与任务优先级）。
     *
     * @param stage     Agent 阶段
     * @param subStage  子阶段（可为 null）
     * @param priority  任务优先级（影响成本优化策略）
     * @return 模型路由结果
     */
    public ModelRouteResult route(AgentStage stage, SubStage subStage, TaskPriority priority) {
        // 路由器未启用，透传原始配置
        if (!properties.isEnabled()) {
            ModelConfig config = modelRoutingService.getModelConfig(stage, subStage);
            return ModelRouteResult.builder()
                    .selectedModel(config.getModelName())
                    .temperature(config.getTemperature())
                    .maxTokens(config.getMaxTokens())
                    .fallbackUsed(false)
                    .reason("路由器未启用，使用原始路由配置")
                    .estimatedCost(estimateCost(config.getModelName(), config.getMaxTokens()))
                    .priority(priority)
                    .build();
        }

        // 1. 获取主模型配置
        ModelConfig primary = modelRoutingService.getModelConfig(stage, subStage);

        // 2. 成本优化：低优先级任务替换为低成本模型
        String primaryModel = primary.getModelName();
        if (priority == TaskPriority.LOW && properties.isCostOptimizationEnabled()) {
            String lowCost = properties.getLowCostModel();
            if (lowCost != null && !lowCost.isBlank()) {
                log.debug("[ModelRouter] 低优先级任务成本优化: {} -> {}", primaryModel, lowCost);
                primaryModel = lowCost;
            }
        }

        // 3. 构建降级链（主模型 → 配置的降级链）
        List<String> chain = buildFallbackChain(primaryModel);

        // 4. 按降级链依次检查可用性
        for (int i = 0; i < chain.size(); i++) {
            String candidate = chain.get(i);
            String rejection = checkAvailability(candidate);
            if (rejection == null) {
                boolean fallbackUsed = i > 0;
                String reason = fallbackUsed
                        ? "主模型 " + primaryModel + " 不可用(" + lastRejection(chain, i) + ")，降级到 " + candidate
                        : "使用主模型 " + candidate;
                ModelConfig base = selectBaseConfig(candidate, primary);
                ModelRouteResult result = ModelRouteResult.builder()
                        .selectedModel(candidate)
                        .temperature(base.getTemperature())
                        .maxTokens(base.getMaxTokens())
                        .fallbackUsed(fallbackUsed)
                        .reason(reason)
                        .estimatedCost(estimateCost(candidate, base.getMaxTokens()))
                        .priority(priority)
                        .fallbackChain(chain)
                        .build();
                log.info("[ModelRouter] stage={}, subStage={}, priority={}, selected={}, fallback={}",
                        stage.getCode(), subStage == null ? null : subStage.getCode(),
                        priority, candidate, fallbackUsed);
                return result;
            }
        }

        // 5. 所有候选均不可用，尽力而为返回降级链末端
        String lastModel = chain.getLast();
        ModelConfig base = selectBaseConfig(lastModel, primary);
        log.warn("[ModelRouter] 所有候选模型均不可用，降级返回末端模型: {}", lastModel);
        return ModelRouteResult.builder()
                .selectedModel(lastModel)
                .temperature(base.getTemperature())
                .maxTokens(base.getMaxTokens())
                .fallbackUsed(true)
                .reason("所有候选模型均不可用（熔断/限流），尽力而为返回末端模型 " + lastModel)
                .estimatedCost(estimateCost(lastModel, base.getMaxTokens()))
                .priority(priority)
                .fallbackChain(chain)
                .build();
    }

    /**
     * 路由模型（不含子阶段）。
     *
     * @param stage    Agent 阶段
     * @param priority 任务优先级
     * @return 模型路由结果
     */
    public ModelRouteResult route(AgentStage stage, TaskPriority priority) {
        return route(stage, null, priority);
    }

    /**
     * 路由模型（默认中等优先级）。
     *
     * @param stage    Agent 阶段
     * @param subStage 子阶段
     * @return 模型路由结果
     */
    public ModelRouteResult route(AgentStage stage, SubStage subStage) {
        return route(stage, subStage, TaskPriority.NORMAL);
    }

    /**
     * 路由模型（默认中等优先级，不含子阶段）。
     *
     * @param stage Agent 阶段
     * @return 模型路由结果
     */
    public ModelRouteResult route(AgentStage stage) {
        return route(stage, null, TaskPriority.NORMAL);
    }

    /**
     * 反馈某次调用的成功/失败结果，驱动熔断器状态机。
     *
     * @param model   被调用的模型名
     * @param success 是否成功
     */
    public void recordResult(String model, boolean success) {
        if (!properties.isEnabled()) {
            return;
        }
        CircuitBreaker breaker = circuitBreakers.computeIfAbsent(model,
                k -> new CircuitBreaker(properties.getCircuitFailureThreshold(),
                        properties.getCircuitCooldownSeconds()));
        breaker.record(success);
        if (!success) {
            log.warn("[ModelRouter] 模型 {} 调用失败, 当前连续失败数={}", model, breaker.failureCount.get());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部路由逻辑
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建降级链：主模型 → 配置的降级链（去重）。
     *
     * @param primaryModel 主模型名
     * @return 降级链列表（不含重复）
     */
    private List<String> buildFallbackChain(String primaryModel) {
        List<String> chain = new ArrayList<>();
        chain.add(primaryModel);
        for (String fallback : properties.getFallbackChain()) {
            if (fallback != null && !fallback.isBlank() && !chain.contains(fallback)) {
                chain.add(fallback);
            }
        }
        return chain;
    }

    /**
     * 检查模型是否可用。
     *
     * @param model 模型名
     * @return null 表示可用；否则返回不可用原因
     */
    private String checkAvailability(String model) {
        // 1. 熔断器检查
        CircuitBreaker breaker = circuitBreakers.get(model);
        if (breaker != null && !breaker.allowRequest()) {
            return "熔断器打开(连续失败" + breaker.failureCount.get() + ")";
        }
        // 2. 限流检查
        if (properties.isRateLimitEnabled()) {
            RateLimiter limiter = rateLimiters.computeIfAbsent(model,
                    k -> new RateLimiter(properties.getDefaultQpsLimit()));
            if (!limiter.tryAcquire()) {
                return "QPS限流(超" + properties.getDefaultQpsLimit() + ")";
            }
        }
        return null;
    }

    /**
     * 获取降级链中第 i 个模型之前第一个被拒绝的原因（用于日志）。
     */
    private String lastRejection(List<String> chain, int acceptedIndex) {
        if (acceptedIndex <= 0) {
            return "";
        }
        return checkAvailability(chain.get(acceptedIndex - 1));
    }

    /**
     * 选择降级模型的基础配置参数（温度/maxTokens 沿用主模型配置）。
     *
     * @param model      目标模型名
     * @param primaryCfg 主模型配置
     * @return 模型配置
     */
    private ModelConfig selectBaseConfig(String model, ModelConfig primaryCfg) {
        return ModelConfig.builder()
                .modelName(model)
                .temperature(primaryCfg.getTemperature())
                .maxTokens(primaryCfg.getMaxTokens())
                .creative(primaryCfg.isCreative())
                .provider(primaryCfg.getProvider())
                .build();
    }

    /**
     * 估算单次调用的成本（美元），基于 maxTokens 与模型单价。
     *
     * @param model     模型名
     * @param maxTokens 最大输出 token
     * @return 估算成本
     */
    private double estimateCost(String model, int maxTokens) {
        RouterProperties.Pricing pricing = properties.getPricing().get(model);
        if (pricing == null) {
            pricing = properties.getPricing().getOrDefault("default",
                    new RouterProperties.Pricing(2.50, 10.00));
        }
        // 简化估算：输入按 maxTokens 的一半，输出按 maxTokens
        long input = maxTokens / 2L;
        long output = maxTokens;
        return (input / 1_000_000.0) * pricing.getInputPerMillion()
                + (output / 1_000_000.0) * pricing.getOutputPerMillion();
    }

    /**
     * 手动重置指定模型的熔断器（供运维或测试调用）。
     *
     * @param model 模型名
     */
    public void resetCircuitBreaker(String model) {
        CircuitBreaker breaker = circuitBreakers.remove(model);
        if (breaker != null) {
            log.info("[ModelRouter] 手动重置模型 {} 的熔断器", model);
        }
    }

    /**
     * 获取指定模型的熔断器状态描述。
     *
     * @param model 模型名
     * @return 状态描述
     */
    public String getCircuitBreakerStatus(String model) {
        CircuitBreaker breaker = circuitBreakers.get(model);
        if (breaker == null) {
            return "CLOSED(未触发)";
        }
        return breaker.state.name() + "(连续失败=" + breaker.failureCount.get() + ")";
    }

    // ════════════════════════════════════════════════════════════════
    //  任务优先级与结果 DTO
    // ════════════════════════════════════════════════════════════════

    /**
     * 任务优先级。
     *
     * <p>影响成本优化策略：{@link #LOW} 优先级任务会自动替换为低成本模型。
     */
    public enum TaskPriority {
        /** 高优先级：使用最强模型，不降级成本 */
        HIGH,
        /** 中等优先级：正常路由 */
        NORMAL,
        /** 低优先级：自动选择低成本模型 */
        LOW
    }

    /**
     * 模型路由结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelRouteResult {

        /** 最终选中的模型名称 */
        private String selectedModel;

        /** 采样温度 */
        private double temperature;

        /** 最大输出 token 数 */
        private int maxTokens;

        /** 是否触发了降级（true 表示未使用主模型） */
        private boolean fallbackUsed;

        /** 路由决策原因说明 */
        private String reason;

        /** 估算单次调用成本（美元） */
        private double estimatedCost;

        /** 任务优先级 */
        private TaskPriority priority;

        /** 完整降级链（含选中模型及其后续备选） */
        @lombok.Builder.Default
        private List<String> fallbackChain = new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════
    //  熔断器与限流器
    // ════════════════════════════════════════════════════════════════

    /**
     * 熔断器（简化版状态机：CLOSED → OPEN → HALF_OPEN → CLOSED）。
     *
     * <p>状态转换：
     * <ul>
     *   <li>CLOSED：正常放行，记录连续失败数</li>
     *   <li>OPEN：连续失败达阈值后打开，拒绝所有请求，等待冷却期</li>
     *   <li>HALF_OPEN：冷却期后放行单个探测请求，成功则恢复 CLOSED，失败则重回 OPEN</li>
     * </ul>
     */
    private static final class CircuitBreaker {

        private final int failureThreshold;
        private final int cooldownSeconds;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private volatile BreakerState state = BreakerState.CLOSED;
        private volatile Instant openedAt = null;

        CircuitBreaker(int failureThreshold, int cooldownSeconds) {
            this.failureThreshold = Math.max(1, failureThreshold);
            this.cooldownSeconds = Math.max(1, cooldownSeconds);
        }

        /**
         * 判断是否允许请求通过。
         *
         * @return true 表示放行
         */
        boolean allowRequest() {
            return switch (state) {
                case CLOSED -> true;
                case OPEN -> {
                    // 冷却期过后转为 HALF_OPEN，放行探测
                    if (openedAt != null
                            && Duration.between(openedAt, Instant.now()).getSeconds() >= cooldownSeconds) {
                        state = BreakerState.HALF_OPEN;
                        log.info("[CircuitBreaker] 熔断器进入半开状态, 探测请求放行");
                        yield true;
                    }
                    yield false;
                }
                case HALF_OPEN -> true;
            };
        }

        /**
         * 记录调用结果，驱动状态机。
         *
         * @param success 是否成功
         */
        void record(boolean success) {
            if (success) {
                if (state == BreakerState.HALF_OPEN) {
                    log.info("[CircuitBreaker] 半开探测成功, 恢复 CLOSED");
                }
                failureCount.set(0);
                state = BreakerState.CLOSED;
                openedAt = null;
            } else {
                int count = failureCount.incrementAndGet();
                if (state == BreakerState.HALF_OPEN) {
                    // 探测失败，重新打开
                    state = BreakerState.OPEN;
                    openedAt = Instant.now();
                    log.warn("[CircuitBreaker] 半开探测失败, 重新打开");
                } else if (count >= failureThreshold) {
                    state = BreakerState.OPEN;
                    openedAt = Instant.now();
                    log.warn("[CircuitBreaker] 连续失败 {} 次达到阈值, 打开熔断器", count);
                }
            }
        }

        /** 熔断器状态 */
        private enum BreakerState {
            /** 关闭（正常） */
            CLOSED,
            /** 打开（拒绝请求） */
            OPEN,
            /** 半开（放行探测） */
            HALF_OPEN
        }
    }

    /**
     * 简易令牌桶限流器（按 QPS 限制）。
     *
     * <p>基于滑动窗口实现：每秒重置可用令牌数为 QPS 上限，每次请求消耗一个令牌。
     */
    private static final class RateLimiter {

        private final int qpsLimit;
        private final AtomicLong tokens;
        private volatile long windowStart;

        RateLimiter(int qpsLimit) {
            this.qpsLimit = Math.max(1, qpsLimit);
            this.tokens = new AtomicLong(qpsLimit);
            this.windowStart = System.currentTimeMillis();
        }

        /**
         * 尝试获取一个令牌。
         *
         * @return true 表示获取成功（放行）
         */
        boolean tryAcquire() {
            long now = System.currentTimeMillis();
            synchronized (this) {
                // 超过 1 秒，重置窗口
                if (now - windowStart >= 1000) {
                    windowStart = now;
                    tokens.set(qpsLimit);
                }
                if (tokens.get() > 0) {
                    tokens.decrementAndGet();
                    return true;
                }
                return false;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  配置属性
    // ════════════════════════════════════════════════════════════════

    /**
     * 模型路由器配置属性。
     *
     * <p>通过 {@code contentops.llmops.router.*} 在 application.yml 中绑定。
     *
     * <h3>配置示例</h3>
     * <pre>{@code
     * contentops:
     *   llmops:
     *     router:
     *       enabled: true
     *       fallback-chain:
     *         - gpt-4o-mini
     *         - gpt-3.5-turbo
     *       low-cost-model: gpt-4o-mini
     *       cost-optimization-enabled: true
     *       circuit-failure-threshold: 5
     *       circuit-cooldown-seconds: 30
     *       rate-limit-enabled: true
     *       default-qps-limit: 10
     *       pricing:
     *         gpt-4o:
     *           input-per-million: 2.50
     *           output-per-million: 10.00
     *         gpt-4o-mini:
     *           input-per-million: 0.15
     *           output-per-million: 0.60
     *         gpt-3.5-turbo:
     *           input-per-million: 0.50
     *           output-per-million: 1.50
     *         default:
     *           input-per-million: 2.50
     *           output-per-million: 10.00
     * }</pre>
     */
    @Data
    @org.springframework.stereotype.Component
    @ConfigurationProperties(prefix = "contentops.llmops.router")
    public static class RouterProperties {

        /** 是否启用增强路由器（关闭时透传 ModelRoutingService 原始配置） */
        private boolean enabled = true;

        /** 模型降级链（主模型不可用时依次尝试，不含主模型本身） */
        private List<String> fallbackChain = List.of("gpt-4o-mini", "gpt-3.5-turbo");

        /** 低成本模型名称（低优先级任务自动切换到此模型） */
        private String lowCostModel = "gpt-4o-mini";

        /** 是否启用成本优化（低优先级任务自动选低成本模型） */
        private boolean costOptimizationEnabled = true;

        /** 熔断器连续失败阈值（达到后打开熔断器） */
        private int circuitFailureThreshold = 5;

        /** 熔断器冷却期（秒），过后转为半开状态探测 */
        private int circuitCooldownSeconds = 30;

        /** 是否启用 QPS 限流 */
        private boolean rateLimitEnabled = true;

        /** 默认每模型 QPS 上限 */
        private int defaultQpsLimit = 10;

        /** 按模型计费单价表（key 为模型名，含 "default" 兜底） */
        private Map<String, Pricing> pricing = new java.util.HashMap<>(Map.of(
                "gpt-4o", new Pricing(2.50, 10.00),
                "gpt-4o-mini", new Pricing(0.15, 0.60),
                "gpt-3.5-turbo", new Pricing(0.50, 1.50),
                "default", new Pricing(2.50, 10.00)));

        /**
         * 单模型计费单价。
         */
        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Pricing {
            /** 每百万输入 token 价格（美元） */
            private double inputPerMillion;
            /** 每百万输出 token 价格（美元） */
            private double outputPerMillion;
        }
    }
}
