package com.contentops.common.llmops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型降级链四层容错增强器 — 面试高频考点。
 *
 * <p><b>面试回答模板：</b>"Failover = 基础设施瞬时故障换 Provider，
 * Fallback = 语义失败换模型/缓存/策略。降级链通过 idempotency key 贯穿，
 * held-out quality floor 防止降级后质量塌方，每个 fallback hop 都 emit OTel span。"
 *
 * <h3>四层容错架构</h3>
 * <pre>
 *   Layer 1: 接入层 — 协议适配 + 鉴权 + 限流 + 配额检查
 *   Layer 2: 跨 Provider 降级 (Failover) — 同模型不可用换 Provider
 *   Layer 3: 跨模型降级 (Fallback) — 换更小模型
 *   Layer 4: 兜底层 — 模板兜底 / 缓存兜底 / 人工接管
 * </pre>
 *
 * <h3>增强点（相比已有 ModelRouterWithFallback）</h3>
 * <ul>
 *   <li><b>Idempotency Key</b>：贯穿降级链，确保重试不会产生重复副作用</li>
 *   <li><b>Held-out Quality Floor</b>：降级后用 held-out 评估分数防塌方，
 *       低于阈值时跳过该 fallback hop 继续降级</li>
 *   <li><b>OTel Span Emission</b>：每个 fallback hop 发射 OpenTelemetry span，
 *       属性包括 fallback.reason / hop / route / score / mttr_ms</li>
 *   <li><b>Failover vs Fallback 区分</b>：5xx/网络/超时 = Failover（换 Provider），
 *       429/guardrail block/上下文溢出/质量不达标 = Fallback（换模型/策略）</li>
 * </ul>
 *
 * @see com.contentops.common.llmops.ModelRouterWithFallback
 */
@Slf4j
@Component
public class ModelFallbackChainEnhancer {

    /** 降级链路由记录 — 每个 hop 的决策轨迹 */
    private final Map<String, FallbackTrace> traceStore = new ConcurrentHashMap<>();

    /** Held-out 评估分数缓存（模型名 → 最近评估分数） */
    private final Map<String, Double> qualityFloorStore = new ConcurrentHashMap<>();

    /** 降级触发统计 */
    private final Map<FallbackReason, AtomicInteger> reasonStats = new ConcurrentHashMap<>();

    /** 默认质量底线（低于此分数的模型不会被选为降级目标） */
    private static final double DEFAULT_QUALITY_FLOOR = 0.6;

    /**
     * 降级链路由请求。
     *
     * @param idempotencyKey 幂等键（贯穿降级链，防重复副作用）
     * @param primaryModel   主模型名
     * @param fallbackChain  降级链（按优先级排列）
     * @param failureReason  失败原因
     * @param taskContext     任务上下文（用于 held-out 评估）
     */
    public record FallbackRouteRequest(
            String idempotencyKey,
            String primaryModel,
            List<String> fallbackChain,
            FallbackReason failureReason,
            Map<String, Object> taskContext
    ) {
        public FallbackRouteRequest {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                idempotencyKey = UUID.randomUUID().toString();
            }
            if (fallbackChain == null) {
                fallbackChain = List.of();
            }
            if (taskContext == null) {
                taskContext = new HashMap<>();
            }
        }
    }

    /**
     * 降级链路由结果。
     *
     * @param selectedModel  最终选中的模型
     * @param routeType      路由类型（FAILOVER / FALLBACK / PRIMARY / FALLBACK_TEMPLATE）
     * @param hopCount       经过的降级跳数
     * @param traceId       降级链追踪 ID
     * @qualityFloorPassed  是否通过 held-out 质量底线检查
     */
    public record FallbackRouteResult(
            String selectedModel,
            RouteType routeType,
            int hopCount,
            String traceId,
            boolean qualityFloorPassed,
            String explanation
    ) {
    }

    /**
     * 路由类型枚举。
     */
    public enum RouteType {
        /** 使用主模型，未降级 */
        PRIMARY,
        /** 跨 Provider 降级（基础设施故障） */
        FAILOVER,
        /** 跨模型降级（语义失败） */
        FALLBACK,
        /** 模板兜底 */
        FALLBACK_TEMPLATE
    }

    /**
     * 降级原因枚举 — 区分 Failover 和 Fallback。
     */
    public enum FallbackReason {
        // ── Failover（基础设施瞬时故障）──
        /** 5xx 服务端错误 */
        SERVER_ERROR,
        /** 网络超时 */
        NETWORK_TIMEOUT,
        /** 连接被拒绝 */
        CONNECTION_REFUSED,

        // ── Fallback（语义失败）──
        /** 429 限流 */
        RATE_LIMITED,
        /** Guardrail 阻断 */
        GUARDRAIL_BLOCK,
        /** 上下文窗口溢出 */
        CONTEXT_OVERFLOW,
        /** 质量评估不达标 */
        QUALITY_BELOW_STANDARD,
        /** 输出格式不符合预期 */
        FORMAT_VIOLATION
    }

    /**
     * 降级链追踪记录 — 记录每个 hop 的决策。
     */
    public record FallbackTrace(
            String traceId,
            String idempotencyKey,
            List<FallbackHop> hops,
            long startTime,
            long endTime
    ) {
    }

    /**
     * 单个降级跳。
     *
     * @param hopNumber    跳序号（从 1 开始）
     * @param fromModel    起始模型
     * @param toModel      目标模型
     * @param routeType    路由类型
     * @param reason       降级原因
     * @param qualityScore 质量评分（held-out 评估）
     * @param qualityFloorPassed 是否通过质量底线
     * @param mttrMs       该跳的恢复时间（毫秒）
     * @param otelSpanId   OTel span ID（用于追踪）
     */
    public record FallbackHop(
            int hopNumber,
            String fromModel,
            String toModel,
            RouteType routeType,
            FallbackReason reason,
            double qualityScore,
            boolean qualityFloorPassed,
            long mttrMs,
            String otelSpanId
    ) {
    }

    /**
     * 执行降级链路由决策。
     *
     * <p>按降级链依次检查每个候选模型：
     * <ol>
     *   <li>判断失败原因属于 Failover 还是 Fallback</li>
     *   <li>检查候选模型的 held-out 质量分数是否达到底线</li>
     *   <li>通过质量检查的模型被选中</li>
     *   <li>每个跳都生成 OTel span 并记录到追踪链</li>
     * </ol>
     *
     * @param request 降级路由请求
     * @return 降级路由结果
     */
    public FallbackRouteResult route(FallbackRouteRequest request) {
        String traceId = "trace-" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        // 记录降级原因统计
        reasonStats.computeIfAbsent(request.failureReason(), k -> new AtomicInteger(0))
                .incrementAndGet();

        log.info("[FallbackChain] 降级链启动: traceId={}, idempotencyKey={}, primary={}, reason={}, chainSize={}",
                traceId, request.idempotencyKey(), request.primaryModel(),
                request.failureReason(), request.fallbackChain().size());

        List<FallbackHop> hops = new java.util.ArrayList<>();
        RouteType routeType = determineRouteType(request.failureReason());

        // 遍历降级链，寻找第一个通过质量底线的候选
        for (int i = 0; i < request.fallbackChain().size(); i++) {
            String candidateModel = request.fallbackChain().get(i);
            int hopNumber = i + 1;
            long hopStart = System.currentTimeMillis();

            // 检查 held-out 质量底线
            double qualityScore = qualityFloorStore.getOrDefault(candidateModel, 1.0);
            boolean qualityPassed = qualityScore >= DEFAULT_QUALITY_FLOOR;

            String spanId = emitOtelSpan(traceId, hopNumber, request.primaryModel(),
                    candidateModel, routeType, request.failureReason(), qualityScore);

            FallbackHop hop = new FallbackHop(
                    hopNumber,
                    i == 0 ? request.primaryModel() : request.fallbackChain().get(i - 1),
                    candidateModel,
                    routeType,
                    request.failureReason(),
                    qualityScore,
                    qualityPassed,
                    System.currentTimeMillis() - hopStart,
                    spanId
            );
            hops.add(hop);

            if (qualityPassed) {
                long endTime = System.currentTimeMillis();
                traceStore.put(traceId, new FallbackTrace(
                        traceId, request.idempotencyKey(), hops, startTime, endTime));

                log.info("[FallbackChain] 降级成功: traceId={}, selected={}, hopCount={}, mttr={}ms, quality={}",
                        traceId, candidateModel, hopNumber, endTime - startTime,
                        String.format("%.2f", qualityScore));

                return new FallbackRouteResult(
                        candidateModel, routeType, hopNumber, traceId, true,
                        "降级到 " + candidateModel + "（质量评分 " +
                                String.format("%.2f", qualityScore) + " ≥ 底线 " + DEFAULT_QUALITY_FLOOR + "）"
                );
            } else {
                log.warn("[FallbackChain] 候选模型 {} 质量评分 {} 低于底线 {}，跳过",
                        candidateModel, String.format("%.2f", qualityScore), DEFAULT_QUALITY_FLOOR);
            }
        }

        // 所有候选都未通过 → 模板兜底
        long endTime = System.currentTimeMillis();
        traceStore.put(traceId, new FallbackTrace(
                traceId, request.idempotencyKey(), hops, startTime, endTime));

        log.error("[FallbackChain] 降级链耗尽，启用模板兜底: traceId={}, hops={}",
                traceId, hops.size());

        return new FallbackRouteResult(
                "template-fallback", RouteType.FALLBACK_TEMPLATE,
                hops.size() + 1, traceId, false,
                "降级链全部未通过质量底线，启用模板兜底"
        );
    }

    /**
     * 更新模型的 held-out 质量评分。
     *
     * @param modelName 模型名称
     * @param score     质量评分（0.0-1.0）
     */
    public void updateQualityScore(String modelName, double score) {
        double clamped = Math.max(0.0, Math.min(1.0, score));
        qualityFloorStore.put(modelName, clamped);
        log.info("[FallbackChain] 更新模型质量评分: model={}, score={}", modelName,
                String.format("%.2f", clamped));
    }

    /**
     * 获取降级链统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTraces", traceStore.size());
        stats.put("qualityFloorEntries", qualityFloorStore.size());

        Map<String, Integer> reasonCounts = new HashMap<>();
        reasonStats.forEach((reason, count) ->
                reasonCounts.put(reason.name(), count.get()));
        stats.put("fallbackReasons", reasonCounts);

        // 计算平均 MTTR
        double avgMttr = traceStore.values().stream()
                .mapToLong(t -> t.endTime() - t.startTime())
                .average()
                .orElse(0.0);
        stats.put("avgMttrMs", avgMttr);

        return stats;
    }

    /**
     * 获取指定追踪 ID的完整降级链记录。
     */
    public FallbackTrace getTrace(String traceId) {
        return traceStore.get(traceId);
    }

    // ──────────────────────── 内部方法 ────────────────────────

    /**
     * 根据失败原因判断路由类型。
     * <p>5xx/网络/超时 = Failover（换 Provider），429/guardrail/质量 = Fallback（换模型）。
     */
    private RouteType determineRouteType(FallbackReason reason) {
        return switch (reason) {
            case SERVER_ERROR, NETWORK_TIMEOUT, CONNECTION_REFUSED -> RouteType.FAILOVER;
            case RATE_LIMITED, GUARDRAIL_BLOCK, CONTEXT_OVERFLOW,
                 QUALITY_BELOW_STANDARD, FORMAT_VIOLATION -> RouteType.FALLBACK;
        };
    }

    /**
     * 模拟发射 OpenTelemetry span。
     *
     * <p>生产环境中应通过 OTel SDK 发射真实 span，包含属性：
     * fallback.reason / fallback.hop / fallback.route / fallback.score / fallback.mttr_ms
     *
     * @return 生成的 span ID
     */
    private String emitOtelSpan(String traceId, int hop, String fromModel, String toModel,
                                 RouteType routeType, FallbackReason reason, double qualityScore) {
        String spanId = "span-" + traceId + "-" + hop;
        log.debug("[OTel] emit span: spanId={}, traceId={}, hop={}, from={}, to={}, route={}, reason={}, score={}",
                spanId, traceId, hop, fromModel, toModel, routeType, reason,
                String.format("%.2f", qualityScore));
        return spanId;
    }
}
