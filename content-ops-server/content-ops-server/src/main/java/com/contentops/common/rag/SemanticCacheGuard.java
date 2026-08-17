package com.contentops.common.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 语义缓存守卫（Semantic Cache Guard）—— 防御语义缓存的五大陷阱。
 *
 * <p>语义缓存通过 embedding 相似度复用历史问答，能显著降低延迟与成本，但「相似 ≠ 相同」：
 * 一旦阈值、隔离或失效策略失当，缓存反而会放大错误。本组件依据面试 P0 高频洞察，
 * 对语义缓存的五大陷阱进行系统性防御。
 *
 * <h3>五大陷阱与防御策略</h3>
 * <ol>
 *   <li><b>假阳性（False Positives）</b>：如「退款政策」与「取消政策」在 embedding 空间中
 *       距离很近，但答案截然不同；阈值过松会自信地返回错误答案。
 *       <br>防御：将相似度阈值设为 {@value #SIMILARITY_THRESHOLD}（高于常见的 0.7）；
 *       命中 {@link #highStakesKeywords} 的高风险查询整体绕过缓存。</li>
 *   <li><b>延迟亏损（Latency Overhead）</b>：远程 Redis 向量检索每次新增 20-50ms，
 *       p99 可达 100ms；命中率低于 15-20% 时为净延迟亏损。
 *       <br>防御：跟踪命中率与平均检索延迟，低于 {@value #MIN_HIT_RATE} 触发
 *       {@link PitfallReason#LOW_HIT_RATE}，平均延迟超过 {@value #LATENCY_LOSS_THRESHOLD_MS}ms
 *       触发 {@link PitfallReason#LATENCY_LOSS}，建议停用缓存。</li>
 *   <li><b>陈旧缓存（Stale Cache）</b>：内容已变更但缓存未失效，返回过时答案。
 *       <br>防御：缓存条目年龄超过 {@value #MAX_CACHE_AGE_MS}ms 判定陈旧；
 *       并对答案做内容级陈旧标记启发式检查。</li>
 *   <li><b>缓存投毒（Cache Poisoning）</b>：低质量/错误结果被缓存并持续服务于后续查询。
 *       <br>防御：空/过短/含错误标记的答案拒绝服务；通过 {@link #recordCacheHit} 收集用户反馈，
 *       连续被拒的查询标记为投毒并从缓存中剔除，接受反馈占优时可自愈。</li>
 *   <li><b>多租户泄漏（Multi-tenant Leakage）</b>：租户 A 的缓存答案被服务于租户 B。
 *       <br>防御：强制要求 tenantId，缺失则判定 {@link PitfallReason#TENANT_LEAKAGE} 拒绝服务，
 *       确保租户隔离。</li>
 * </ol>
 *
 * <h3>核心 API</h3>
 * <ul>
 *   <li>{@link #shouldServeCache(CacheCheckRequest)}：缓存服务前的总闸决策（含策略性绕过）。</li>
 *   <li>{@link #validateCacheEntry(String, String, double, String)}：对单个缓存条目执行五项陷阱检查。</li>
 *   <li>{@link #recordCacheHit(String, boolean)}：基于用户反馈的缓存质量监控（投毒检测/自愈）。</li>
 *   <li>{@link #recordLookupLatency(long)}：记录一次远程向量检索延迟，用于平均延迟统计。</li>
 *   <li>{@link #getCacheHealthStats()}：返回命中率、假阳性率、平均延迟、陈旧数等健康指标。</li>
 * </ul>
 *
 * <p>统计基于 {@link AtomicInteger}/{@link AtomicLong} 与 {@link ConcurrentHashMap}，线程安全。
 * 判决类型采用 Java 21 密封接口 {@link CacheVerdict} 与模式匹配（pattern matching for switch），
 * 便于扩展与可读性。
 *
 * @see PitfallReason
 * @see CacheCheckResult
 */
@Slf4j
@Component
public class SemanticCacheGuard {

    // ──────────────────── 配置常量 ────────────────────

    /** 相似度阈值（默认 0.85，高于常见的 0.7，用以防止假阳性）。对应陷阱 #1。 */
    static final double SIMILARITY_THRESHOLD = 0.85;

    /** 缓存值得启用的最低命中率（默认 15%）。命中率低于此值时缓存为净延迟亏损。对应陷阱 #2。 */
    static final double MIN_HIT_RATE = 0.15;

    /** 触发命中率/延迟判定所需的最小请求数（样本不足时不判定，避免冷启动误判）。 */
    static final int MIN_SAMPLE_FOR_HIT_RATE = 20;

    /** 缓存最大年龄（默认 1 小时 = 3600000ms），超过则判定陈旧。对应陷阱 #3。 */
    static final long MAX_CACHE_AGE_MS = 3_600_000L;

    /** 平均向量检索延迟亏损阈值（ms），超过则判定延迟亏损。对应陷阱 #2。 */
    static final double LATENCY_LOSS_THRESHOLD_MS = 50.0;

    /** 缓存答案最小长度（字符），低于则疑似投毒。对应陷阱 #4。 */
    static final int MIN_ANSWER_LENGTH = 10;

    /** 标记为投毒的连续被拒次数阈值（反馈驱动）。对应陷阱 #4。 */
    static final int POISON_REJECTION_THRESHOLD = 3;

    /**
     * 高风险查询关键词集合（命中则整体绕过缓存，防止假阳性）。
     * <p>包含英文关键词（refund/cancel/delete/payment/legal）以及对齐面试示例的中文关键词
     * （退款/取消/删除/支付/付款/法律/合同/违约），用于识别「退款政策」「取消政策」这类
     * 一旦返回错误答案代价极高的查询。对应陷阱 #1。
     */
    static final Set<String> highStakesKeywords = Set.of(
            "refund", "cancel", "delete", "payment", "legal",
            "退款", "取消", "删除", "支付", "付款", "法律", "合同", "违约");

    /** 疑似陈旧/占位内容标记（内容级陈旧启发式）。对应陷阱 #3。 */
    private static final Set<String> STALE_CONTENT_MARKERS = Set.of(
            "[deprecated]", "[stale]", "[placeholder]", "[todo]", "[outdated]", "已过期", "已下线");

    /** 疑似投毒/错误内容标记（命中则拒绝服务）。对应陷阱 #4。 */
    private static final Set<String> ERROR_MARKERS = Set.of(
            "[error]", "exception", "traceback", "nullpointer", "undefined",
            "i don't know", "我不知道", "无法回答");

    // ──────────────────── 统计计数器 ────────────────────

    private final AtomicInteger totalRequests = new AtomicInteger();
    private final AtomicInteger servedHits = new AtomicInteger();
    private final AtomicInteger rejectedFalsePositives = new AtomicInteger();
    private final AtomicInteger staleRejections = new AtomicInteger();
    private final AtomicInteger feedbackAccepted = new AtomicInteger();
    private final AtomicInteger feedbackRejected = new AtomicInteger();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final AtomicInteger latencySamples = new AtomicInteger();

    /** 投毒查询集合（反馈驱动，连续被拒达到阈值即加入；接受反馈占优时可自愈移除）。 */
    private final Set<String> poisonedQueries = ConcurrentHashMap.newKeySet();

    /** 每查询被用户拒绝次数（反馈驱动投毒检测）。 */
    private final ConcurrentHashMap<String, AtomicInteger> queryRejections = new ConcurrentHashMap<>();

    /** 每查询被用户接受次数（反馈驱动投毒自愈）。 */
    private final ConcurrentHashMap<String, AtomicInteger> queryAcceptances = new ConcurrentHashMap<>();

    // ──────────────────── 公共 API ────────────────────

    /**
     * 缓存服务前的总闸决策：综合判定是否可将缓存答案服务于当前请求。
     *
     * <p>依次执行：高风险查询绕过 → 五项陷阱检查（假阳性/延迟亏损/低命中率/陈旧/投毒/租户隔离）。
     * 高风险查询返回策略性绕过（{@link PolicyBypass}），投影为 {@code shouldServe=false}。
     *
     * @param request 缓存检查请求
     * @return 缓存检查结果（verdict + reason + 解释 + 置信度）
     */
    public CacheCheckResult shouldServeCache(CacheCheckRequest request) {
        CacheVerdict verdict = evaluate(request);
        // Java 21 模式匹配 switch：将密封判决层级投影为统一的 CacheCheckResult
        return switch (verdict) {
            case CacheCheckResult r -> r;
            case PolicyBypass b -> new CacheCheckResult(false, b.prevented(), b.reason(), 0.0);
        };
    }

    /**
     * 对单个缓存条目执行五项陷阱检查（不增量请求计数，纯校验）。
     *
     * <p>依次检查：假阳性（相似度/高风险）、延迟亏损与低命中率（全局信号）、陈旧缓存
     * （内容级启发式，年龄相关陈旧请使用 {@link #shouldServeCache}）、缓存投毒、多租户隔离。
     *
     * @param query           当前查询
     * @param cachedAnswer    待校验的缓存答案
     * @param similarityScore 当前查询与缓存查询的相似度
     * @param tenantId        租户标识（缺失将判定租户泄漏）
     * @return 缓存检查结果（命中任一陷阱则 shouldServe=false，reason 为最严重者，解释列出全部命中项）
     */
    public CacheCheckResult validateCacheEntry(String query, String cachedAnswer,
                                               double similarityScore, String tenantId) {
        boolean highStakes = isHighStakesQuery(query);
        EnumMap<PitfallReason, String> triggered = evaluateAllPitfalls(
                query, cachedAnswer, similarityScore, tenantId, null, highStakes);

        if (triggered.isEmpty()) {
            double confidence = computeConfidence(PitfallReason.NONE, similarityScore);
            return new CacheCheckResult(true, PitfallReason.NONE,
                    String.format("通过全部五项陷阱检查，相似度 %.3f", similarityScore), confidence);
        }

        PitfallReason worst = mostSevere(triggered);
        String explanation = summarize(triggered);
        double confidence = computeConfidence(worst, similarityScore);
        log.warn("[SemanticCache] 缓存条目校验未通过 worst={}, query='{}', issues=[{}]",
                worst, query, explanation);
        return new CacheCheckResult(false, worst, explanation, confidence);
    }

    /**
     * 基于用户反馈的缓存质量监控：记录一次缓存命中是否被用户接受。
     *
     * <p>反馈驱动投毒检测与自愈：
     * <ul>
     *   <li>{@code userAccepted=false}：累计该查询被拒次数，达到
     *       {@value #POISON_REJECTION_THRESHOLD} 次则标记为投毒（加入 {@link #poisonedQueries}）。</li>
     *   <li>{@code userAccepted=true}：累计接受次数；当接受次数足够且明显多于拒绝时，解除投毒标记（自愈）。</li>
     * </ul>
     *
     * @param query         被命中的查询
     * @param userAccepted  用户是否接受了该缓存答案
     */
    public void recordCacheHit(String query, boolean userAccepted) {
        if (query == null || query.isBlank()) {
            return;
        }
        String key = normalizeQuery(query);
        if (userAccepted) {
            queryAcceptances.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            feedbackAccepted.incrementAndGet();
            maybeUnpoison(key);
        } else {
            int rejections = queryRejections.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            feedbackRejected.incrementAndGet();
            if (rejections >= POISON_REJECTION_THRESHOLD && poisonedQueries.add(key)) {
                log.warn("[SemanticCache] 查询被标记为投毒（累计被拒 {} 次）: query='{}'", rejections, query);
            }
        }
    }

    /**
     * 记录一次远程向量检索延迟，用于平均延迟统计与延迟亏损判定。
     *
     * <p>语义缓存的向量检索本身会引入 20-50ms 延迟，是陷阱 #2 的核心来源。调用方在执行
     * 实际向量检索后应调用本方法上报延迟，{@link #getCacheHealthStats()} 据此计算平均延迟。
     *
     * @param latencyMs 检索延迟（毫秒，负值忽略）
     */
    public void recordLookupLatency(long latencyMs) {
        if (latencyMs < 0) {
            return;
        }
        totalLatencyMs.addAndGet(latencyMs);
        latencySamples.incrementAndGet();
    }

    /**
     * 返回缓存健康统计：命中率、假阳性率、平均延迟、陈旧数等。
     *
     * <p>指标定义：
     * <ul>
     *   <li>{@code hitRate} = 已服务命中 / 总请求数</li>
     *   <li>{@code falsePositiveRate} = (假阳性拒绝 + 用户反馈拒绝) / 总请求数</li>
     *   <li>{@code avgLatencyMs} = 累计检索延迟 / 延迟样本数</li>
     *   <li>{@code staleCount} = 累计陈旧拒绝次数</li>
     *   <li>{@code poisonedEntries} = 当前被标记为投毒的查询数</li>
     * </ul>
     *
     * @return 缓存健康统计快照
     */
    public CacheHealthStats getCacheHealthStats() {
        int total = totalRequests.get();
        double hitRate = total == 0 ? 0.0 : (double) servedHits.get() / total;
        int falsePositiveSignals = rejectedFalsePositives.get() + feedbackRejected.get();
        double falsePositiveRate = total == 0 ? 0.0 : (double) falsePositiveSignals / total;
        double avgLatency = avgLatencyMs();
        return new CacheHealthStats(hitRate, falsePositiveRate, avgLatency,
                staleRejections.get(), total, poisonedQueries.size());
    }

    /**
     * 将密封判决层级描述为可读字符串（演示 Java 21 模式匹配 switch + 守卫）。
     *
     * @param verdict 判决
     * @return 可读描述
     */
    public String describe(CacheVerdict verdict) {
        return switch (verdict) {
            case CacheCheckResult r when r.shouldServe() ->
                    "SERVE(confidence=%.2f): %s".formatted(r.confidenceScore(), r.explanation());
            case CacheCheckResult r ->
                    "REJECT[%s](confidence=%.2f): %s".formatted(r.reason(), r.confidenceScore(), r.explanation());
            case PolicyBypass b ->
                    "BYPASS[%s]: %s".formatted(b.prevented(), b.reason());
        };
    }

    // ──────────────────── 内部评估 ────────────────────

    /**
     * 总闸评估：返回密封判决 {@link CacheVerdict}。
     * 高风险查询返回 {@link PolicyBypass}，其余返回 {@link CacheCheckResult}。
     */
    private CacheVerdict evaluate(CacheCheckRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return new CacheCheckResult(false, PitfallReason.NONE, "无效请求：查询为空", 0.0);
        }
        totalRequests.incrementAndGet();

        // 陷阱 #1 防御：高风险查询策略性绕过缓存（调用方预标记或关键词命中任一即触发）
        boolean highStakes = request.isHighStakesQuery() || isHighStakesQuery(request.query());
        if (highStakes) {
            log.debug("[SemanticCache] 高风险查询绕过缓存: query='{}'", request.query());
            return new PolicyBypass(request.query(), PitfallReason.FALSE_POSITIVE,
                    "高风险查询（退款/取消/删除/支付/法律等），策略性绕过缓存以防假阳性");
        }

        EnumMap<PitfallReason, String> triggered = evaluateAllPitfalls(
                request.query(), request.cachedAnswer(), request.similarityScore(),
                request.tenantId(), request.cacheEntryAgeMs(), false);

        if (triggered.isEmpty()) {
            servedHits.incrementAndGet();
            double confidence = computeConfidence(PitfallReason.NONE, request.similarityScore());
            return new CacheCheckResult(true, PitfallReason.NONE,
                    String.format("相似度 %.3f 通过阈值 %.2f，缓存可安全服务",
                            request.similarityScore(), SIMILARITY_THRESHOLD),
                    confidence);
        }

        PitfallReason worst = mostSevere(triggered);
        recordRejectionStats(worst);
        String explanation = summarize(triggered);
        double confidence = computeConfidence(worst, request.similarityScore());
        return new CacheCheckResult(false, worst, explanation, confidence);
    }

    /**
     * 五项陷阱统一评估，返回「命中的陷阱 → 说明」映射（每项至多一条）。
     *
     * @param cacheEntryAgeMs 缓存条目年龄（ms），null 表示不评估年龄相关陈旧
     * @param highStakes      是否高风险查询（命中则记为假阳性风险）
     */
    private EnumMap<PitfallReason, String> evaluateAllPitfalls(
            String query, String cachedAnswer, double similarityScore, String tenantId,
            Long cacheEntryAgeMs, boolean highStakes) {
        EnumMap<PitfallReason, String> triggered = new EnumMap<>(PitfallReason.class);

        // 陷阱 #1：假阳性
        if (highStakes) {
            triggered.put(PitfallReason.FALSE_POSITIVE,
                    "高风险查询命中关键词，按策略绕过缓存以避免假阳性");
        } else if (similarityScore < SIMILARITY_THRESHOLD) {
            triggered.put(PitfallReason.FALSE_POSITIVE,
                    String.format("相似度 %.3f 低于阈值 %.2f，存在假阳性风险（相似≠相同）",
                            similarityScore, SIMILARITY_THRESHOLD));
        }

        // 陷阱 #2：低命中率 / 延迟亏损（全局信号）
        int total = totalRequests.get();
        if (total >= MIN_SAMPLE_FOR_HIT_RATE && currentHitRate() < MIN_HIT_RATE) {
            triggered.put(PitfallReason.LOW_HIT_RATE,
                    String.format("当前命中率 %.1f%% 低于启用阈值 %.0f%%，缓存净延迟亏损",
                            currentHitRate() * 100, MIN_HIT_RATE * 100));
        }
        if (latencySamples.get() >= MIN_SAMPLE_FOR_HIT_RATE && avgLatencyMs() > LATENCY_LOSS_THRESHOLD_MS) {
            triggered.put(PitfallReason.LATENCY_LOSS,
                    String.format("平均向量检索延迟 %.1fms 超过亏损阈值 %.0fms，缓存可能造成净延迟",
                            avgLatencyMs(), LATENCY_LOSS_THRESHOLD_MS));
        }

        // 陷阱 #3：陈旧缓存
        if (cacheEntryAgeMs != null && cacheEntryAgeMs > MAX_CACHE_AGE_MS) {
            triggered.put(PitfallReason.STALE_CACHE,
                    String.format("缓存条目年龄 %dms 超过最大 %dms，疑似陈旧",
                            cacheEntryAgeMs, MAX_CACHE_AGE_MS));
        } else if (looksStale(cachedAnswer)) {
            triggered.put(PitfallReason.STALE_CACHE, "缓存答案包含陈旧/占位标记");
        }

        // 陷阱 #4：缓存投毒
        if (looksPoisoned(query, cachedAnswer)) {
            triggered.put(PitfallReason.CACHE_POISONING,
                    "缓存答案为空/过短/含错误标记，或查询已被反馈标记为投毒");
        }

        // 陷阱 #5：多租户泄漏
        if (tenantId == null || tenantId.isBlank()) {
            triggered.put(PitfallReason.TENANT_LEAKAGE,
                    "缺少 tenantId，无法保证租户隔离，存在跨租户泄漏风险");
        }

        return triggered;
    }

    /** 记录拒绝统计（仅假阳性与陈旧有独立计数器，用于健康指标）。 */
    private void recordRejectionStats(PitfallReason reason) {
        switch (reason) {
            case FALSE_POSITIVE -> rejectedFalsePositives.incrementAndGet();
            case STALE_CACHE -> staleRejections.incrementAndGet();
            default -> { /* 其余陷阱不单独计数 */
            }
        }
    }

    /** 投毒自愈：当接受反馈足够且明显多于拒绝时，解除投毒标记。 */
    private void maybeUnpoison(String key) {
        if (!poisonedQueries.contains(key)) {
            return;
        }
        AtomicInteger acc = queryAcceptances.get(key);
        AtomicInteger rej = queryRejections.get(key);
        int accepted = acc == null ? 0 : acc.get();
        int rejected = rej == null ? 0 : rej.get();
        if (accepted >= POISON_REJECTION_THRESHOLD && accepted >= rejected * 2) {
            poisonedQueries.remove(key);
            log.info("[SemanticCache] 查询投毒标记已自愈解除: key='{}'", key);
        }
    }

    /** 高风险查询检测：查询（小写）包含任一高风险关键词即判定。 */
    private boolean isHighStakesQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        for (String keyword : highStakesKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 内容级陈旧启发式：答案包含陈旧/占位标记。 */
    private boolean looksStale(String cachedAnswer) {
        if (cachedAnswer == null || cachedAnswer.isBlank()) {
            return false;
        }
        String lower = cachedAnswer.toLowerCase(Locale.ROOT);
        for (String marker : STALE_CONTENT_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 缓存投毒启发式：答案为空/过短/含错误标记，或查询已被反馈标记为投毒。 */
    private boolean looksPoisoned(String query, String cachedAnswer) {
        if (cachedAnswer == null || cachedAnswer.isBlank() || cachedAnswer.length() < MIN_ANSWER_LENGTH) {
            return true;
        }
        String lower = cachedAnswer.toLowerCase(Locale.ROOT);
        for (String marker : ERROR_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return query != null && poisonedQueries.contains(normalizeQuery(query));
    }

    /** 当前命中率（已服务命中 / 总请求数）。 */
    private double currentHitRate() {
        int total = totalRequests.get();
        return total == 0 ? 0.0 : (double) servedHits.get() / total;
    }

    /** 平均向量检索延迟（ms）。 */
    private double avgLatencyMs() {
        int samples = latencySamples.get();
        return samples == 0 ? 0.0 : (double) totalLatencyMs.get() / samples;
    }

    /** 取命中陷阱中严重度最高者。 */
    private PitfallReason mostSevere(EnumMap<PitfallReason, String> triggered) {
        PitfallReason worst = PitfallReason.NONE;
        for (PitfallReason reason : triggered.keySet()) {
            if (reason.severity() > worst.severity()) {
                worst = reason;
            }
        }
        return worst;
    }

    /** 计算判决置信度：服务时为相似度；拒绝时按陷阱类型递增。 */
    private double computeConfidence(PitfallReason reason, double similarityScore) {
        if (reason == PitfallReason.NONE) {
            return clamp01(similarityScore);
        }
        return switch (reason) {
            case TENANT_LEAKAGE, CACHE_POISONING -> 1.0;          // 安全问题，高置信拒绝
            case FALSE_POSITIVE -> Math.max(0.5, 1.0 - similarityScore); // 相似度越低越确信
            case STALE_CACHE -> 0.9;
            case LATENCY_LOSS, LOW_HIT_RATE -> 0.7;
            case NONE -> clamp01(similarityScore);
        };
    }

    /** 将命中陷阱映射汇总为单行说明。 */
    private String summarize(EnumMap<PitfallReason, String> triggered) {
        return triggered.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + " | " + b)
                .orElse("");
    }

    /** 查询归一化（去空格 + 小写），用作投毒/反馈映射的键。 */
    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    /** 将数值钳制到 [0.0, 1.0]。 */
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ──────────────────── 类型定义 ────────────────────

    /**
     * 缓存判决密封接口（Java 21 sealed）：允许的子类型为 {@link CacheCheckResult} 与
     * {@link PolicyBypass}，配合模式匹配 switch 实现穷尽处理。
     */
    public sealed interface CacheVerdict permits CacheCheckResult, PolicyBypass {
    }

    /**
     * 策略性绕过判决：高风险查询等场景下主动绕过缓存（不视为命中某一具体陷阱实例，
     * 而是预防性策略）。由 {@link #shouldServeCache} 投影为 {@code shouldServe=false} 的
     * {@link CacheCheckResult}。
     *
     * @param query     触发绕过的查询
     * @param prevented 所预防的陷阱类型
     * @param reason    绕过原因说明
     */
    public record PolicyBypass(String query, PitfallReason prevented, String reason) implements CacheVerdict {
        public PolicyBypass {
            prevented = prevented == null ? PitfallReason.NONE : prevented;
            reason = reason == null ? "" : reason;
        }
    }

    /**
     * 缓存检查请求。
     *
     * @param query            当前查询
     * @param tenantId         租户标识（缺失将判定租户泄漏）
     * @param similarityScore  当前查询与缓存查询的 embedding 相似度
     * @param cachedAnswer     候选缓存答案
     * @param cacheEntryAgeMs  缓存条目年龄（毫秒）
     * @param isHighStakesQuery 调用方是否预标记为高风险查询
     */
    public record CacheCheckRequest(String query, String tenantId, double similarityScore,
                                    String cachedAnswer, long cacheEntryAgeMs, boolean isHighStakesQuery) {
    }

    /**
     * 缓存检查结果。
     *
     * @param shouldServe       是否应将缓存答案服务于当前请求
     * @param reason            陷阱原因（{@link PitfallReason#NONE} 表示可服务）
     * @param explanation       人类可读说明（拒绝时列出全部命中陷阱）
     * @param confidenceScore   置信度 [0.0, 1.0]（服务时为相似度；拒绝时按陷阱类型递增）
     */
    public record CacheCheckResult(boolean shouldServe, PitfallReason reason,
                                   String explanation, double confidenceScore) implements CacheVerdict {
        public CacheCheckResult {
            reason = reason == null ? PitfallReason.NONE : reason;
            explanation = explanation == null ? "" : explanation;
            confidenceScore = clamp01(confidenceScore);
        }
    }

    /**
     * 缓存健康统计快照。
     *
     * @param hitRate            命中率（已服务命中 / 总请求数）
     * @param falsePositiveRate  假阳性率（假阳性拒绝 + 用户反馈拒绝 / 总请求数）
     * @param avgLatencyMs       平均向量检索延迟（ms）
     * @param staleCount         累计陈旧拒绝次数
     * @param totalRequests      总请求数
     * @param poisonedEntries    当前被标记为投毒的查询数
     */
    public record CacheHealthStats(double hitRate, double falsePositiveRate, double avgLatencyMs,
                                   int staleCount, int totalRequests, int poisonedEntries) {
    }

    /**
     * 陷阱原因枚举（严重度由低到高）。
     * <ul>
     *   <li>{@link #NONE} —— 无陷阱，可服务缓存</li>
     *   <li>{@link #LOW_HIT_RATE} —— 命中率过低，缓存净亏损（陷阱 #2）</li>
     *   <li>{@link #LATENCY_LOSS} —— 向量检索延迟过高，净延迟亏损（陷阱 #2）</li>
     *   <li>{@link #STALE_CACHE} —— 缓存陈旧（陷阱 #3）</li>
     *   <li>{@link #FALSE_POSITIVE} —— 假阳性风险（陷阱 #1）</li>
     *   <li>{@link #CACHE_POISONING} —— 缓存投毒（陷阱 #4）</li>
     *   <li>{@link #TENANT_LEAKAGE} —— 多租户泄漏（陷阱 #5）</li>
     * </ul>
     */
    public enum PitfallReason {
        /** 无陷阱 */
        NONE(0),
        /** 低命中率：命中率低于启用阈值，缓存净亏损 */
        LOW_HIT_RATE(1),
        /** 延迟亏损：向量检索延迟过高 */
        LATENCY_LOSS(2),
        /** 陈旧缓存：缓存条目过期或内容含陈旧标记 */
        STALE_CACHE(3),
        /** 假阳性：相似度过低或高风险查询，相似≠相同 */
        FALSE_POSITIVE(4),
        /** 缓存投毒：答案质量低或被反馈标记为投毒 */
        CACHE_POISONING(5),
        /** 多租户泄漏：缺失 tenantId，无法保证租户隔离 */
        TENANT_LEAKAGE(6);

        private final int severity;

        PitfallReason(int severity) {
            this.severity = severity;
        }

        /** 严重度（数值越大越严重，用于在多个命中陷阱间取最严重者）。 */
        public int severity() {
            return severity;
        }
    }
}
