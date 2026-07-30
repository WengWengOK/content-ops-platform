package com.contentops.common.llmops;

import com.contentops.common.metrics.TokenMetricsService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * LLMOps 仪表盘服务。
 *
 * <p>汇总 LLM 调用的核心运营指标，为平台运营与成本治理提供数据支撑。
 * 数据源为 {@link MeterRegistry}（Micrometer）中由 {@link TokenMetricsService} 记录的指标，
 * 通过读取 Counter / Timer 的快照进行聚合计算。
 *
 * <h3>汇总指标</h3>
 * <ul>
 *   <li><b>Token 使用统计</b>：按模型、按阶段、按时段聚合输入/输出 token 消耗</li>
 *   <li><b>调用延迟分布</b>：基于 Timer 计算 P50 / P90 / P99 分位数</li>
 *   <li><b>成功率 / 失败率</b>：基于 {@code contentops.llm.calls.total} 的 success/failure 标签聚合</li>
 *   <li><b>成本统计</b>：基于 {@code contentops.llm.cost.total} 按阶段聚合，并按模型计费换算</li>
 *   <li><b>质量评分趋势</b>：从 {@link QualityScoreStore} 读取历史质量评分，输出趋势序列</li>
 * </ul>
 *
 * <h3>时间范围查询</h3>
 * <p>通过 {@code start} / {@code end} 参数指定查询时间窗口。由于 Micrometer 的累计型指标
 * 本身不内置时间分桶，本服务在进程内维护一份滑动采样缓存（{@link MetricsSampleStore}），
 * 用于支持时段聚合与趋势可视化；累计型指标则提供「截至当前」的总量视图。
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>MeterRegistry 中无对应指标时，对应维度返回零值而非抛出异常</li>
 *   <li>质量评分历史不可用时，趋势部分返回空列表，其余指标正常返回</li>
 *   <li>采样缓存读取失败时记录警告并回退到累计快照</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * DashboardData data = llmOpsDashboard.getDashboard(
 *         Instant.now().minus(Duration.ofHours(24)),
 *         Instant.now());
 * data.getTokenStats().forEach((model, stat) ->
 *         log.info("{} 输入={} 输出={}", model, stat.getInputTokens(), stat.getOutputTokens()));
 * }</pre>
 *
 * @see TokenMetricsService
 * @see MetricsSampleStore
 * @see QualityScoreStore
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmOpsDashboard {

    private final MeterRegistry meterRegistry;
    private final TokenMetricsService tokenMetricsService;
    private final MetricsSampleStore metricsSampleStore;
    private final QualityScoreStore qualityScoreStore;
    private final DashboardProperties properties;

    /** Token 总消耗指标名 */
    private static final String METRIC_TOKENS = "contentops.llm.tokens.total";
    /** 总费用指标名 */
    private static final String METRIC_COST = "contentops.llm.cost.total";
    /** 调用次数指标名 */
    private static final String METRIC_CALLS = "contentops.llm.calls.total";
    /** Agent 延迟指标名 */
    private static final String METRIC_DURATION = "contentops.agent.duration";

    /**
     * 获取仪表盘汇总数据。
     *
     * @param start 查询起始时间（包含）
     * @param end   查询结束时间（包含）
     * @return 仪表盘数据结构
     */
    public DashboardData getDashboard(Instant start, Instant end) {
        log.info("[Dashboard] 生成仪表盘数据, 时间范围: {} ~ {}", start, end);
        return DashboardData.builder()
                .timeRange(TimeRange.builder()
                        .start(toLocalDateTime(start))
                        .end(toLocalDateTime(end))
                        .durationSeconds(Duration.between(start, end).getSeconds())
                        .build())
                .tokenStats(buildTokenStats(start, end))
                .latencyDistribution(buildLatencyDistribution(start, end))
                .successRate(buildSuccessRate(start, end))
                .costStats(buildCostStats(start, end))
                .qualityTrend(buildQualityTrend(start, end))
                .build();
    }

    /**
     * 获取最近 N 小时的仪表盘数据。
     *
     * @param hours 小时数
     * @return 仪表盘数据
     */
    public DashboardData getRecentDashboard(long hours) {
        return getDashboard(Instant.now().minus(Duration.ofHours(hours)), Instant.now());
    }

    /**
     * 记录一次质量评分样本（供 {@link com.contentops.common.quality.QualityAssessmentService} 调用）。
     *
     * @param stage   Agent 阶段代码
     * @param score   质量总分
     * @param model   使用的模型名称
     */
    public void recordQualityScore(String stage, int score, String model) {
        try {
            qualityScoreStore.addSample(new QualityScoreStore.QualitySample(
                    Instant.now(), stage, model, score));
        } catch (Exception e) {
            log.warn("[Dashboard] 记录质量评分样本失败: {}", e.getMessage());
        }
    }

    /**
     * 主动采集一次指标快照（供定时任务调用，构建时段聚合基线）。
     */
    public void snapshotMetrics() {
        try {
            metricsSampleStore.snapshot(meterRegistry);
        } catch (Exception e) {
            log.warn("[Dashboard] 采集指标快照失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  指标聚合
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建 Token 使用统计（按模型 / 阶段聚合）。
     *
     * <p>从 {@link MetricsSampleStore} 读取时间窗口内的样本，按模型维度聚合输入/输出 token。
     */
    private Map<String, TokenStat> buildTokenStats(Instant start, Instant end) {
        Map<String, TokenStat> stats = new LinkedHashMap<>();
        try {
            List<TokenSample> samples = metricsSampleStore.getTokenSamples(start, end);
            Map<String, List<TokenSample>> byModel = samples.stream()
                    .collect(Collectors.groupingBy(TokenSample::model));
            byModel.forEach((model, list) -> {
                long input = list.stream().mapToLong(TokenSample::inputTokens).sum();
                long output = list.stream().mapToLong(TokenSample::outputTokens).sum();
                stats.put(model, TokenStat.builder()
                        .model(model)
                        .inputTokens(input)
                        .outputTokens(output)
                        .totalTokens(input + output)
                        .callCount(list.size())
                        .build());
            });

            // 若样本为空，回退到 MeterRegistry 累计快照
            if (stats.isEmpty()) {
                fillTokenStatsFromRegistry(stats);
            }
        } catch (Exception e) {
            log.warn("[Dashboard] 构建 Token 统计失败, 回退到累计快照: {}", e.getMessage());
            fillTokenStatsFromRegistry(stats);
        }
        return stats;
    }

    /**
     * 从 MeterRegistry 累计快照填充 Token 统计（降级路径）。
     */
    private void fillTokenStatsFromRegistry(Map<String, TokenStat> stats) {
        for (Meter meter : meterRegistry.getMeters()) {
            if (!METRIC_TOKENS.equals(meter.getId().getName())) {
                continue;
            }
            String tokenType = meter.getId().getTag("tokenType");
            if (!(meter instanceof io.micrometer.core.instrument.Counter counter)) {
                continue;
            }
            String model = meter.getId().getTag("model");
            if (model == null) {
                model = meter.getId().getTag("agentStage");
            }
            if (model == null) {
                model = "unknown";
            }
            TokenStat stat = stats.computeIfAbsent(model, k -> TokenStat.builder()
                    .model(k).inputTokens(0).outputTokens(0).totalTokens(0).callCount(0).build());
            double value = counter.count();
            if ("input".equals(tokenType)) {
                stat.setInputTokens((long) value);
            } else if ("output".equals(tokenType)) {
                stat.setOutputTokens((long) value);
            }
            stat.setTotalTokens(stat.getInputTokens() + stat.getOutputTokens());
        }
    }

    /**
     * 构建延迟分布（P50 / P90 / P99）。
     *
     * <p>基于 {@code contentops.agent.duration} Timer 的直方图快照计算分位数。
     * 未配置直方图时回退到 mean。
     */
    private Map<String, LatencyDistribution> buildLatencyDistribution(Instant start, Instant end) {
        Map<String, LatencyDistribution> distributions = new LinkedHashMap<>();
        try {
            Map<String, List<DurationSample>> byStage =
                    metricsSampleStore.getDurationSamples(start, end).stream()
                            .collect(Collectors.groupingBy(DurationSample::stage));
            byStage.forEach((stage, list) -> {
                DoubleSummaryStatistics summary = list.stream()
                        .mapToDouble(DurationSample::durationMs)
                        .summaryStatistics();
                List<Double> sorted = list.stream()
                        .map(DurationSample::durationMs)
                        .sorted()
                        .toList();
                distributions.put(stage, LatencyDistribution.builder()
                        .stage(stage)
                        .sampleCount(summary.getCount())
                        .meanMs(summary.getAverage())
                        .p50Ms(percentile(sorted, 0.50))
                        .p90Ms(percentile(sorted, 0.90))
                        .p99Ms(percentile(sorted, 0.99))
                        .maxMs(summary.getMax())
                        .minMs(summary.getMin())
                        .build());
            });

            // 回退到 Timer 快照
            if (distributions.isEmpty()) {
                fillLatencyFromRegistry(distributions);
            }
        } catch (Exception e) {
            log.warn("[Dashboard] 构建延迟分布失败, 回退到 Timer 快照: {}", e.getMessage());
            fillLatencyFromRegistry(distributions);
        }
        return distributions;
    }

    /**
     * 从 MeterRegistry 的 Timer 快照填充延迟分布（降级路径）。
     */
    private void fillLatencyFromRegistry(Map<String, LatencyDistribution> distributions) {
        for (Meter meter : meterRegistry.getMeters()) {
            if (!METRIC_DURATION.equals(meter.getId().getName())) {
                continue;
            }
            if (!(meter instanceof Timer timer)) {
                continue;
            }
            String stage = Objects.requireNonNullElse(meter.getId().getTag("agentStage"), "unknown");
            HistogramSnapshot snapshot = timer.takeSnapshot();
            distributions.put(stage, LatencyDistribution.builder()
                    .stage(stage)
                    .sampleCount((int) snapshot.count())
                    .meanMs(snapshot.mean() >= 0 ? snapshot.mean() : 0.0)
                    .p50Ms(snapshot.mean() >= 0 ? snapshot.mean() : 0.0)
                    .p90Ms(snapshot.max() >= 0 ? snapshot.max() : 0.0)
                    .p99Ms(snapshot.max() >= 0 ? snapshot.max() : 0.0)
                    .maxMs(snapshot.max() >= 0 ? snapshot.max() : 0.0)
                    .minMs(0.0)
                    .build());
        }
    }

    /**
     * 构建成功率 / 失败率统计。
     */
    private Map<String, SuccessRate> buildSuccessRate(Instant start, Instant end) {
        Map<String, SuccessRate> rates = new LinkedHashMap<>();
        try {
            Map<String, List<CallSample>> byStage =
                    metricsSampleStore.getCallSamples(start, end).stream()
                            .collect(Collectors.groupingBy(CallSample::stage));
            byStage.forEach((stage, list) -> {
                long total = list.size();
                long success = list.stream().filter(CallSample::success).count();
                long failure = total - success;
                rates.put(stage, SuccessRate.builder()
                        .stage(stage)
                        .totalCalls(total)
                        .successCalls(success)
                        .failureCalls(failure)
                        .successRate(total == 0 ? 0.0 : (double) success / total)
                        .failureRate(total == 0 ? 0.0 : (double) failure / total)
                        .build());
            });

            if (rates.isEmpty()) {
                fillSuccessRateFromRegistry(rates);
            }
        } catch (Exception e) {
            log.warn("[Dashboard] 构建成功率失败, 回退到累计快照: {}", e.getMessage());
            fillSuccessRateFromRegistry(rates);
        }
        return rates;
    }

    /**
     * 从 MeterRegistry 累计快照填充成功率（降级路径）。
     */
    private void fillSuccessRateFromRegistry(Map<String, SuccessRate> rates) {
        Map<String, long[]> tally = new TreeMap<>();
        for (Meter meter : meterRegistry.getMeters()) {
            if (!METRIC_CALLS.equals(meter.getId().getName())) {
                continue;
            }
            if (!(meter instanceof io.micrometer.core.instrument.Counter counter)) {
                continue;
            }
            String stage = Objects.requireNonNullElse(meter.getId().getTag("agentStage"), "unknown");
            String status = meter.getId().getTag("status");
            long[] pair = tally.computeIfAbsent(stage, k -> new long[2]);
            if ("success".equals(status)) {
                pair[0] = (long) counter.count();
            } else if ("failure".equals(status)) {
                pair[1] = (long) counter.count();
            }
        }
        tally.forEach((stage, pair) -> {
            long success = pair[0];
            long failure = pair[1];
            long total = success + failure;
            rates.put(stage, SuccessRate.builder()
                    .stage(stage)
                    .totalCalls(total)
                    .successCalls(success)
                    .failureCalls(failure)
                    .successRate(total == 0 ? 0.0 : (double) success / total)
                    .failureRate(total == 0 ? 0.0 : (double) failure / total)
                    .build());
        });
    }

    /**
     * 构建成本统计（按模型 / 阶段聚合）。
     *
     * <p>累计费用来自 {@code contentops.llm.cost.total}，并按模型计费单价换算估算成本。
     */
    private Map<String, CostStat> buildCostStats(Instant start, Instant end) {
        Map<String, CostStat> stats = new LinkedHashMap<>();
        try {
            // 从采样缓存聚合
            List<TokenSample> samples = metricsSampleStore.getTokenSamples(start, end);
            Map<String, List<TokenSample>> byModel = samples.stream()
                    .collect(Collectors.groupingBy(TokenSample::model));
            byModel.forEach((model, list) -> {
                long input = list.stream().mapToLong(TokenSample::inputTokens).sum();
                long output = list.stream().mapToLong(TokenSample::outputTokens).sum();
                double cost = estimateCost(model, input, output);
                stats.put(model, CostStat.builder()
                        .model(model)
                        .inputTokens(input)
                        .outputTokens(output)
                        .estimatedCostUsd(cost)
                        .build());
            });

            if (stats.isEmpty()) {
                fillCostStatsFromRegistry(stats);
            }
        } catch (Exception e) {
            log.warn("[Dashboard] 构建成本统计失败, 回退到累计快照: {}", e.getMessage());
            fillCostStatsFromRegistry(stats);
        }
        return stats;
    }

    /**
     * 从 MeterRegistry 累计快照填充成本统计（降级路径）。
     */
    private void fillCostStatsFromRegistry(Map<String, CostStat> stats) {
        Map<String, Double> stageCost = new TreeMap<>();
        for (Meter meter : meterRegistry.getMeters()) {
            if (!METRIC_COST.equals(meter.getId().getName())) {
                continue;
            }
            if (!(meter instanceof io.micrometer.core.instrument.Counter counter)) {
                continue;
            }
            String stage = Objects.requireNonNullElse(meter.getId().getTag("agentStage"), "unknown");
            stageCost.merge(stage, counter.count(), Double::sum);
        }
        stageCost.forEach((stage, cost) -> stats.put(stage, CostStat.builder()
                .model(stage)
                .inputTokens(0)
                .outputTokens(0)
                .estimatedCostUsd(cost)
                .build()));
    }

    /**
     * 构建质量评分趋势。
     */
    private List<QualityTrendPoint> buildQualityTrend(Instant start, Instant end) {
        try {
            List<QualityScoreStore.QualitySample> samples =
                    qualityScoreStore.getSamples(start, end);
            return samples.stream()
                    .map(s -> QualityTrendPoint.builder()
                            .timestamp(toLocalDateTime(s.timestamp()))
                            .stage(s.stage())
                            .model(s.model())
                            .score(s.score())
                            .build())
                    .toList();
        } catch (Exception e) {
            log.warn("[Dashboard] 构建质量趋势失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算有序列表的指定分位数。
     *
     * @param sorted 已排序（升序）的样本列表
     * @param p      分位数（0.0 - 1.0）
     * @return 分位值
     */
    private double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    /**
     * 按模型计费单价估算成本（美元）。
     *
     * @param model        模型名称
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     * @return 估算成本（美元）
     */
    private double estimateCost(String model, long inputTokens, long outputTokens) {
        DashboardProperties.Pricing pricing = properties.getPricing().get(model);
        if (pricing == null) {
            pricing = properties.getPricing().getOrDefault("default",
                    new DashboardProperties.Pricing(2.50, 10.00));
        }
        return (inputTokens / 1_000_000.0) * pricing.getInputPerMillion()
                + (outputTokens / 1_000_000.0) * pricing.getOutputPerMillion();
    }

    /**
     * Instant 转 LocalDateTime（系统默认时区）。
     */
    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    // ════════════════════════════════════════════════════════════════
    //  仪表盘数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 仪表盘汇总数据。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardData {

        /** 查询时间范围 */
        private TimeRange timeRange;

        /** Token 使用统计（按模型） */
        private Map<String, TokenStat> tokenStats;

        /** 调用延迟分布（按阶段，含 P50/P90/P99） */
        private Map<String, LatencyDistribution> latencyDistribution;

        /** 成功率 / 失败率（按阶段） */
        private Map<String, SuccessRate> successRate;

        /** 成本统计（按模型） */
        private Map<String, CostStat> costStats;

        /** 质量评分趋势 */
        private List<QualityTrendPoint> qualityTrend;

        /**
         * 获取总输入 token 数。
         *
         * @return 总输入 token
         */
        public long getTotalInputTokens() {
            if (tokenStats == null) return 0;
            return tokenStats.values().stream().mapToLong(TokenStat::getInputTokens).sum();
        }

        /**
         * 获取总输出 token 数。
         *
         * @return 总输出 token
         */
        public long getTotalOutputTokens() {
            if (tokenStats == null) return 0;
            return tokenStats.values().stream().mapToLong(TokenStat::getOutputTokens).sum();
        }

        /**
         * 获取总成本（美元）。
         *
         * @return 总成本
         */
        public double getTotalCost() {
            if (costStats == null) return 0.0;
            return costStats.values().stream().mapToDouble(CostStat::getEstimatedCostUsd).sum();
        }
    }

    /**
     * 时间范围。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeRange {
        /** 起始时间 */
        private LocalDateTime start;
        /** 结束时间 */
        private LocalDateTime end;
        /** 时长（秒） */
        private long durationSeconds;
    }

    /**
     * Token 使用统计。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenStat {
        /** 模型 / 阶段名称 */
        private String model;
        /** 输入 token 数 */
        private long inputTokens;
        /** 输出 token 数 */
        private long outputTokens;
        /** 总 token 数 */
        private long totalTokens;
        /** 调用次数 */
        private long callCount;
    }

    /**
     * 延迟分布。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatencyDistribution {
        /** 阶段名称 */
        private String stage;
        /** 样本数 */
        private long sampleCount;
        /** 平均延迟（毫秒） */
        private double meanMs;
        /** P50 延迟（毫秒） */
        private double p50Ms;
        /** P90 延迟（毫秒） */
        private double p90Ms;
        /** P99 延迟（毫秒） */
        private double p99Ms;
        /** 最大延迟（毫秒） */
        private double maxMs;
        /** 最小延迟（毫秒） */
        private double minMs;
    }

    /**
     * 成功率统计。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuccessRate {
        /** 阶段名称 */
        private String stage;
        /** 总调用次数 */
        private long totalCalls;
        /** 成功次数 */
        private long successCalls;
        /** 失败次数 */
        private long failureCalls;
        /** 成功率（0.0 - 1.0） */
        private double successRate;
        /** 失败率（0.0 - 1.0） */
        private double failureRate;
    }

    /**
     * 成本统计。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostStat {
        /** 模型 / 阶段名称 */
        private String model;
        /** 输入 token 数 */
        private long inputTokens;
        /** 输出 token 数 */
        private long outputTokens;
        /** 估算成本（美元） */
        private double estimatedCostUsd;
    }

    /**
     * 质量评分趋势点。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityTrendPoint {
        /** 时间戳 */
        private LocalDateTime timestamp;
        /** Agent 阶段 */
        private String stage;
        /** 模型名称 */
        private String model;
        /** 质量评分（0-100） */
        private int score;
    }

    // ════════════════════════════════════════════════════════════════
    //  指标采样缓存与质量评分存储
    // ════════════════════════════════════════════════════════════════

    /**
     * 指标采样缓存。
     *
     * <p>进程内维护的滑动窗口样本缓存，用于支持时间范围聚合查询。
     * 由 {@link LlmOpsDashboard#snapshotMetrics()} 定时采集，或由各服务调用 {@code recordXxx} 实时写入。
     *
     * <p>降级策略：当缓存为空时，仪表盘会回退到 {@link MeterRegistry} 的累计快照。
     */
    @Service
    @Slf4j
    public static class MetricsSampleStore {

        private final List<TokenSample> tokenSamples = new ArrayList<>();
        private final List<DurationSample> durationSamples = new ArrayList<>();
        private final List<CallSample> callSamples = new ArrayList<>();

        /** 最大保留样本数（防止内存溢出） */
        private static final int MAX_SAMPLES = 10_000;

        /**
         * 记录一次 token 消耗样本。
         *
         * @param timestamp    时间戳
         * @param model        模型名称
         * @param agentStage   Agent 阶段
         * @param inputTokens  输入 token
         * @param outputTokens 输出 token
         */
        public void recordToken(Instant timestamp, String model, String agentStage,
                                 long inputTokens, long outputTokens) {
            synchronized (tokenSamples) {
                tokenSamples.add(new TokenSample(timestamp, model, agentStage, inputTokens, outputTokens));
                trimIfNeeded(tokenSamples);
            }
        }

        /**
         * 记录一次延迟样本。
         *
         * @param timestamp 时间戳
         * @param stage     Agent 阶段
         * @param durationMs 延迟（毫秒）
         */
        public void recordDuration(Instant timestamp, String stage, double durationMs) {
            synchronized (durationSamples) {
                durationSamples.add(new DurationSample(timestamp, stage, durationMs));
                trimIfNeeded(durationSamples);
            }
        }

        /**
         * 记录一次调用样本。
         *
         * @param timestamp 时间戳
         * @param stage     Agent 阶段
         * @param success   是否成功
         */
        public void recordCall(Instant timestamp, String stage, boolean success) {
            synchronized (callSamples) {
                callSamples.add(new CallSample(timestamp, stage, success));
                trimIfNeeded(callSamples);
            }
        }

        /**
         * 从 MeterRegistry 采集一次快照（累计值差分，简化实现：直接追加当前累计值作为基线）。
         *
         * @param meterRegistry 指标注册表
         */
        public void snapshot(MeterRegistry meterRegistry) {
            Instant now = Instant.now();
            try {
                for (Meter meter : meterRegistry.getMeters()) {
                    if (meter instanceof io.micrometer.core.instrument.Counter counter) {
                        String name = meter.getId().getName();
                        if (METRIC_TOKENS.equals(name)) {
                            String model = Objects.requireNonNullElse(
                                    meter.getId().getTag("model"),
                                    Objects.requireNonNullElse(meter.getId().getTag("agentStage"), "unknown"));
                            String tokenType = meter.getId().getTag("tokenType");
                            long value = (long) counter.count();
                            if ("input".equals(tokenType)) {
                                recordToken(now, model, model, value, 0);
                            } else if ("output".equals(tokenType)) {
                                recordToken(now, model, model, 0, value);
                            }
                        } else if (METRIC_CALLS.equals(name)) {
                            String stage = Objects.requireNonNullElse(
                                    meter.getId().getTag("agentStage"), "unknown");
                            String status = meter.getId().getTag("status");
                            recordCall(now, stage, "success".equals(status));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[MetricsSampleStore] 采集快照失败: {}", e.getMessage());
            }
        }

        /**
         * 获取时间窗口内的 token 样本。
         */
        public List<TokenSample> getTokenSamples(Instant start, Instant end) {
            synchronized (tokenSamples) {
                return tokenSamples.stream()
                        .filter(s -> !s.timestamp().isBefore(start) && !s.timestamp().isAfter(end))
                        .toList();
            }
        }

        /**
         * 获取时间窗口内的延迟样本。
         */
        public List<DurationSample> getDurationSamples(Instant start, Instant end) {
            synchronized (durationSamples) {
                return durationSamples.stream()
                        .filter(s -> !s.timestamp().isBefore(start) && !s.timestamp().isAfter(end))
                        .toList();
            }
        }

        /**
         * 获取时间窗口内的调用样本。
         */
        public List<CallSample> getCallSamples(Instant start, Instant end) {
            synchronized (callSamples) {
                return callSamples.stream()
                        .filter(s -> !s.timestamp().isBefore(start) && !s.timestamp().isAfter(end))
                        .toList();
            }
        }

        /**
         * 样本数超限时丢弃最旧的样本。
         */
        private void trimIfNeeded(List<?> list) {
            while (list.size() > MAX_SAMPLES) {
                list.removeFirst();
            }
        }
    }

    /**
     * 质量评分历史存储。
     *
     * <p>进程内维护的质量评分样本缓存，供 {@link LlmOpsDashboard} 输出质量趋势。
     * 由 {@link LlmOpsDashboard#recordQualityScore} 写入。
     */
    @Service
    @Slf4j
    public static class QualityScoreStore {

        private final List<QualitySample> samples = new ArrayList<>();
        private static final int MAX_SAMPLES = 5_000;

        /**
         * 添加一个质量评分样本。
         *
         * @param sample 质量评分样本
         */
        public void addSample(QualitySample sample) {
            synchronized (samples) {
                samples.add(sample);
                while (samples.size() > MAX_SAMPLES) {
                    samples.removeFirst();
                }
            }
        }

        /**
         * 获取时间窗口内的质量评分样本。
         *
         * @param start 起始时间
         * @param end   结束时间
         * @return 样本列表
         */
        public List<QualitySample> getSamples(Instant start, Instant end) {
            synchronized (samples) {
                return samples.stream()
                        .filter(s -> !s.timestamp().isBefore(start) && !s.timestamp().isAfter(end))
                        .toList();
            }
        }

        /**
         * 质量评分样本。
         *
         * @param timestamp 时间戳
         * @param stage     Agent 阶段
         * @param model     模型名称
         * @param score     质量评分（0-100）
         */
        public record QualitySample(Instant timestamp, String stage, String model, int score) {
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  采样记录定义
    // ════════════════════════════════════════════════════════════════

    /**
     * Token 采样记录。
     *
     * @param timestamp    时间戳
     * @param model        模型名称
     * @param agentStage   Agent 阶段
     * @param inputTokens  输入 token
     * @param outputTokens 输出 token
     */
    public record TokenSample(Instant timestamp, String model, String agentStage,
                               long inputTokens, long outputTokens) {
    }

    /**
     * 延迟采样记录。
     *
     * @param timestamp  时间戳
     * @param stage      Agent 阶段
     * @param durationMs 延迟（毫秒）
     */
    public record DurationSample(Instant timestamp, String stage, double durationMs) {
    }

    /**
     * 调用采样记录。
     *
     * @param timestamp 时间戳
     * @param stage     Agent 阶段
     * @param success   是否成功
     */
    public record CallSample(Instant timestamp, String stage, boolean success) {
    }

    // ════════════════════════════════════════════════════════════════
    //  配置属性
    // ════════════════════════════════════════════════════════════════

    /**
     * 仪表盘配置属性。
     *
     * <p>通过 {@code contentops.llmops.dashboard.*} 在 application.yml 中绑定。
     *
     * <h3>配置示例</h3>
     * <pre>{@code
     * contentops:
     *   llmops:
     *     dashboard:
     *       snapshot-interval-seconds: 60
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
    @ConfigurationProperties(prefix = "contentops.llmops.dashboard")
    public static class DashboardProperties {

        /** 指标快照采集间隔（秒） */
        private int snapshotIntervalSeconds = 60;

        /** 按模型计费单价表（key 为模型名，含 "default" 兜底） */
        private Map<String, Pricing> pricing = new java.util.HashMap<>(Map.of(
                "gpt-4o", new Pricing(2.50, 10.00),
                "gpt-4o-mini", new Pricing(0.15, 0.60),
                "gpt-3.5-turbo", new Pricing(0.50, 1.50),
                "default", new Pricing(2.50, 10.00)));

        /**
         * 单模型计费单价。
         *
         * @param inputPerMillion  每百万输入 token 价格（美元）
         * @param outputPerMillion 每百万输出 token 价格（美元）
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
