package com.contentops.common.finetune;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型 A/B 测试框架（编排层）。
 *
 * <p>支持为两个模型版本配置流量比例，自动收集表现指标，进行统计显著性检验（T 检验），
 * 并生成可视化数据，辅助模型选型决策。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>流量分配</b>：按配置比例（如 modelA:50%, modelB:50%）将请求路由到不同模型版本</li>
 *   <li><b>指标收集</b>：自动记录每次推理的评估分数，构建指标样本序列</li>
 *   <li><b>统计检验</b>：基于 Welch's T 检验判断两组指标差异是否统计显著</li>
 *   <li><b>可视化数据</b>：生成折线图、柱状图所需的聚合数据（均值、标准差、置信区间）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 创建 A/B 测试
 * AbTestConfig config = new AbTestConfig(
 *     "qwen2.5-7b-finetuned-v1",  // 模型 A
 *     "qwen2.5-7b-finetuned-v2",  // 模型 B
 *     50, 50,                      // 流量比例 50:50
 *     100,                         // 最小样本量
 *     0.05                         // 显著性水平
 * );
 * AbTest test = abTestManager.createTest("lora-rank-comparison", config);
 *
 * // 记录评估结果
 * abTestManager.recordResult(test.testId(), "A", 85.5);
 * abTestManager.recordResult(test.testId(), "B", 88.2);
 *
 * // 执行统计检验
 * TTestResult result = abTestManager.runTTest(test.testId());
 * if (result.significant()) {
 *     log.info("模型 {} 显著优于模型 {}", result.betterModel(), result.worseModel());
 * }
 *
 * // 生成可视化数据
 * VisualizationData viz = abTestManager.generateVisualization(test.testId());
 * }</pre>
 *
 * @see ModelEvaluationService
 * @see ModelDeploymentManager
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelAbTest {

    private final ModelEvaluationService evaluationService;

    /** A/B 测试注册表（按测试 ID 索引） */
    private final Map<String, AbTest> testRegistry = new ConcurrentHashMap<>();

    /** Jackson ObjectMapper，用于序列化 */
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    // ════════════════════════════════════════════════════════════════
    // A/B 测试配置与记录
    // ════════════════════════════════════════════════════════════════

    /**
     * A/B 测试配置。
     *
     * @param modelAId        模型 A 的 ID
     * @param modelBId        模型 B 的 ID
     * @param trafficA        模型 A 流量百分比（0-100）
     * @param trafficB        模型 B 流量百分比（0-100）
     * @param minSampleSize   最小样本量（达到后才执行统计检验）
     * @param significanceLevel 显著性水平 α（通常 0.05）
     * @param metricName      测试指标名称（如 evaluationScore、latency）
     */
    public record AbTestConfig(
            String modelAId,
            String modelBId,
            int trafficA,
            int trafficB,
            int minSampleSize,
            double significanceLevel,
            String metricName
    ) {
        public AbTestConfig {
            if (modelAId == null || modelAId.isBlank()) {
                throw new IllegalArgumentException("模型 A ID 不能为空");
            }
            if (modelBId == null || modelBId.isBlank()) {
                throw new IllegalArgumentException("模型 B ID 不能为空");
            }
            if (modelAId.equals(modelBId)) {
                throw new IllegalArgumentException("模型 A 和模型 B 不能相同");
            }
            // 归一化流量比例
            int total = trafficA + trafficB;
            if (total <= 0) {
                trafficA = 50;
                trafficB = 50;
            } else if (total != 100) {
                trafficA = (int) Math.round((double) trafficA / total * 100);
                trafficB = 100 - trafficA;
            }
            minSampleSize = Math.max(2, minSampleSize);
            significanceLevel = significanceLevel <= 0 || significanceLevel >= 1
                    ? 0.05 : significanceLevel;
            metricName = metricName == null || metricName.isBlank()
                    ? "evaluationScore" : metricName;
        }

        /** 默认配置：50:50 流量，最小样本 100，α=0.05 */
        public static AbTestConfig defaultConfig(String modelAId, String modelBId) {
            return new AbTestConfig(modelAId, modelBId, 50, 50, 100, 0.05, "evaluationScore");
        }
    }

    /**
     * A/B 测试运行实例。
     *
     * @param testId      测试唯一 ID
     * @param testName    测试名称
     * @param config      测试配置
     * @param status      测试状态
     * @param samplesA    模型 A 的指标样本列表
     * @param samplesB    模型 B 的指标样本列表
     * @param startedAt   测试开始时间
     * @param endedAt     测试结束时间
     */
    public record AbTest(
            String testId,
            String testName,
            AbTestConfig config,
            AbTestStatus status,
            List<Double> samplesA,
            List<Double> samplesB,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        public AbTest {
            testId = testId == null ? UUID.randomUUID().toString() : testId;
            samplesA = samplesA == null ? new ArrayList<>() : new ArrayList<>(samplesA);
            samplesB = samplesB == null ? new ArrayList<>() : new ArrayList<>(samplesB);
            status = status == null ? AbTestStatus.RUNNING : status;
            startedAt = startedAt == null ? LocalDateTime.now() : startedAt;
        }

        /** 模型 A 样本数 */
        public int sampleCountA() {
            return samplesA.size();
        }

        /** 模型 B 样本数 */
        public int sampleCountB() {
            return samplesB.size();
        }

        /** 总样本数 */
        public int totalSamples() {
            return samplesA.size() + samplesB.size();
        }

        /** 是否达到最小样本量 */
        public boolean hasEnoughSamples() {
            return samplesA.size() >= config.minSampleSize()
                    && samplesB.size() >= config.minSampleSize();
        }
    }

    /**
     * A/B 测试状态。
     */
    public enum AbTestStatus {
        /** 运行中 */
        RUNNING,
        /** 已完成（统计显著或手动停止） */
        COMPLETED,
        /** 已停止（未达显著但手动停止） */
        STOPPED
    }

    /**
     * 单次评估结果记录。
     *
     * @param modelVariant 模型变体（"A" 或 "B"）
     * @param score        评估分数
     * @param recordedAt   记录时间
     */
    public record AbTestSample(
            String modelVariant,
            double score,
            LocalDateTime recordedAt
    ) {
    }

    // ════════════════════════════════════════════════════════════════
    // T 检验结果
    // ════════════════════════════════════════════════════════════════

    /**
     * T 检验结果。
     *
     * @param testId          关联的测试 ID
     * @param meanA           模型 A 指标均值
     * @param meanB           模型 B 指标均值
     * @param stdA            模型 A 指标标准差
     * @param stdB            模型 B 指标标准差
     * @param tStatistic      T 统计量
     * @param pValue          P 值
     * @param degreesOfFreedom 自由度（Welch 近似）
     * @param significant     是否统计显著（P 值 < 显著性水平）
     * @param betterModel     表现更好的模型 ID（不显著时为 null）
     * @param worseModel      表现更差的模型 ID（不显著时为 null）
     * @param confidenceIntervalA 模型 A 均值的 95% 置信区间 [lower, upper]
     * @param confidenceIntervalB 模型 B 均值的 95% 置信区间 [lower, upper]
     * @param computedAt      计算时间
     */
    public record TTestResult(
            String testId,
            double meanA,
            double meanB,
            double stdA,
            double stdB,
            double tStatistic,
            double pValue,
            double degreesOfFreedom,
            boolean significant,
            String betterModel,
            String worseModel,
            double[] confidenceIntervalA,
            double[] confidenceIntervalB,
            LocalDateTime computedAt
    ) {
        public TTestResult {
            confidenceIntervalA = confidenceIntervalA == null
                    ? new double[]{0, 0} : confidenceIntervalA.clone();
            confidenceIntervalB = confidenceIntervalB == null
                    ? new double[]{0, 0} : confidenceIntervalB.clone();
            computedAt = computedAt == null ? LocalDateTime.now() : computedAt;
        }

        /**
         * 将检验结果序列化为 JSON 字符串。
         *
         * @return JSON 字符串
         */
        public String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("testId", testId);
            map.put("meanA", Math.round(meanA * 10000.0) / 10000.0);
            map.put("meanB", Math.round(meanB * 10000.0) / 10000.0);
            map.put("stdA", Math.round(stdA * 10000.0) / 10000.0);
            map.put("stdB", Math.round(stdB * 10000.0) / 10000.0);
            map.put("tStatistic", Math.round(tStatistic * 10000.0) / 10000.0);
            map.put("pValue", Math.round(pValue * 100000.0) / 100000.0);
            map.put("degreesOfFreedom", Math.round(degreesOfFreedom * 100.0) / 100.0);
            map.put("significant", significant);
            map.put("betterModel", betterModel != null ? betterModel : "N/A");
            map.put("worseModel", worseModel != null ? worseModel : "N/A");
            map.put("confidenceIntervalA", confidenceIntervalA);
            map.put("confidenceIntervalB", confidenceIntervalB);
            map.put("computedAt", computedAt.toString());
            return writeJson(map);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 可视化数据
    // ════════════════════════════════════════════════════════════════

    /**
     * 可视化数据（供前端图表渲染）。
     *
     * @param testId        测试 ID
     * @param modelAId      模型 A ID
     * @param modelBId      模型 B ID
     * @param summaryA      模型 A 统计摘要
     * @param summaryB      模型 B 统计摘要
     * @param timeSeriesA   模型 A 时间序列数据（滚动均值）
     * @param timeSeriesB   模型 B 时间序列数据（滚动均值）
     * @param distributionA 模型 A 分数分布直方图
     * @param distributionB 模型 B 分数分布直方图
     * @param generatedAt   生成时间
     */
    public record VisualizationData(
            String testId,
            String modelAId,
            String modelBId,
            MetricSummary summaryA,
            MetricSummary summaryB,
            List<double[]> timeSeriesA,
            List<double[]> timeSeriesB,
            int[] distributionA,
            int[] distributionB,
            LocalDateTime generatedAt
    ) {
        public VisualizationData {
            timeSeriesA = timeSeriesA == null ? List.of() : List.copyOf(timeSeriesA);
            timeSeriesB = timeSeriesB == null ? List.of() : List.copyOf(timeSeriesB);
            distributionA = distributionA == null ? new int[10] : distributionA.clone();
            distributionB = distributionB == null ? new int[10] : distributionB.clone();
            generatedAt = generatedAt == null ? LocalDateTime.now() : generatedAt;
        }

        /**
         * 将可视化数据序列化为 JSON 字符串。
         *
         * @return JSON 字符串
         */
        public String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("testId", testId);
            map.put("modelAId", modelAId);
            map.put("modelBId", modelBId);
            map.put("summaryA", summaryToMap(summaryA));
            map.put("summaryB", summaryToMap(summaryB));
            map.put("timeSeriesA", timeSeriesA);
            map.put("timeSeriesB", timeSeriesB);
            map.put("distributionA", distributionA);
            map.put("distributionB", distributionB);
            map.put("generatedAt", generatedAt.toString());
            return writeJson(map);
        }

        private Map<String, Object> summaryToMap(MetricSummary s) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mean", Math.round(s.mean() * 10000.0) / 10000.0);
            m.put("std", Math.round(s.std() * 10000.0) / 10000.0);
            m.put("min", Math.round(s.min() * 100.0) / 100.0);
            m.put("max", Math.round(s.max() * 100.0) / 100.0);
            m.put("sampleCount", s.sampleCount());
            return m;
        }
    }

    /**
     * 指标统计摘要。
     *
     * @param mean        均值
     * @param std         标准差
     * @param min         最小值
     * @param max         最大值
     * @param sampleCount 样本数
     */
    public record MetricSummary(
            double mean,
            double std,
            double min,
            double max,
            int sampleCount
    ) {
    }

    // ════════════════════════════════════════════════════════════════
    // 测试管理方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建 A/B 测试。
     *
     * @param testName 测试名称
     * @param config   测试配置
     * @return 创建的 A/B 测试实例
     */
    public AbTest createTest(String testName, AbTestConfig config) {
        AbTest test = new AbTest(
                UUID.randomUUID().toString(),
                testName,
                config,
                AbTestStatus.RUNNING,
                new ArrayList<>(),
                new ArrayList<>(),
                LocalDateTime.now(),
                null
        );
        testRegistry.put(test.testId(), test);

        log.info("[ModelAbTest] A/B 测试已创建: testId={}, name={}, modelA={}, modelB={}, traffic={}:{}",
                test.testId(), testName, config.modelAId(), config.modelBId(),
                config.trafficA(), config.trafficB());

        return test;
    }

    /**
     * 便捷方法：使用默认配置创建 A/B 测试。
     *
     * @param testName  测试名称
     * @param modelAId  模型 A ID
     * @param modelBId  模型 B ID
     * @return 创建的 A/B 测试实例
     */
    public AbTest createTest(String testName, String modelAId, String modelBId) {
        return createTest(testName, AbTestConfig.defaultConfig(modelAId, modelBId));
    }

    /**
     * 根据流量比例路由请求到模型 A 或 B。
     *
     * <p>基于配置的流量比例随机选择模型变体。返回 "A" 或 "B"。
     *
     * @param testId 测试 ID
     * @return 模型变体标识（"A" 或 "B"）
     */
    public String routeRequest(String testId) {
        AbTest test = getTestOrThrow(testId);
        double random = ThreadLocalRandom.current().nextDouble(100);
        return random < test.config().trafficA() ? "A" : "B";
    }

    /**
     * 记录一次评估结果。
     *
     * @param testId       测试 ID
     * @param modelVariant 模型变体（"A" 或 "B"）
     * @param score        评估分数
     */
    public void recordResult(String testId, String modelVariant, double score) {
        AbTest test = getTestOrThrow(testId);
        if (test.status() != AbTestStatus.RUNNING) {
            log.warn("[ModelAbTest] 测试已结束，无法记录结果: testId={}, status={}", testId, test.status());
            return;
        }

        AbTest updated;
        List<Double> samplesA = new ArrayList<>(test.samplesA());
        List<Double> samplesB = new ArrayList<>(test.samplesB());

        switch (modelVariant.toUpperCase()) {
            case "A" -> samplesA.add(score);
            case "B" -> samplesB.add(score);
            default -> throw new IllegalArgumentException("无效的模型变体: " + modelVariant + "（应为 A 或 B）");
        }

        updated = new AbTest(
                test.testId(), test.testName(), test.config(), test.status(),
                samplesA, samplesB, test.startedAt(), test.endedAt()
        );
        testRegistry.put(testId, updated);

        log.debug("[ModelAbTest] 结果已记录: testId={}, variant={}, score={}, totalA={}, totalB={}",
                testId, modelVariant, score, samplesA.size(), samplesB.size());
    }

    /**
     * 记录评估结果（基于 EvaluationResult 自动提取分数）。
     *
     * @param testId  测试 ID
     * @param variant 模型变体
     * @param result  评估结果
     */
    public void recordEvaluationResult(String testId, String variant,
                                       ModelEvaluationService.EvaluationResult result) {
        recordResult(testId, variant, result.totalScore());
    }

    /**
     * 停止 A/B 测试。
     *
     * @param testId 测试 ID
     * @param completed true 表示已完成（统计显著），false 表示手动停止
     * @return 更新后的测试
     */
    public AbTest stopTest(String testId, boolean completed) {
        AbTest test = getTestOrThrow(testId);
        AbTest stopped = new AbTest(
                test.testId(), test.testName(), test.config(),
                completed ? AbTestStatus.COMPLETED : AbTestStatus.STOPPED,
                test.samplesA(), test.samplesB(), test.startedAt(), LocalDateTime.now()
        );
        testRegistry.put(testId, stopped);

        log.info("[ModelAbTest] 测试已{}: testId={}, totalA={}, totalB={}",
                completed ? "完成" : "停止", testId,
                test.sampleCountA(), test.sampleCountB());

        return stopped;
    }

    // ════════════════════════════════════════════════════════════════
    // 统计检验方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 执行 Welch's T 检验。
     *
     * <p>Welch's T 检验适用于两组样本方差不等的场景，计算步骤：
     * <ol>
     *   <li>计算两组样本的均值和方差</li>
     *   <li>计算 T 统计量：t = (meanA - meanB) / sqrt(varA/nA + varB/nB)</li>
     *   <li>使用 Welch-Satterthwaite 方程近似自由度</li>
     *   <li>通过 t 分布近似计算 P 值（双尾检验）</li>
     * </ol>
     *
     * @param testId 测试 ID
     * @return T 检验结果
     */
    public TTestResult runTTest(String testId) {
        AbTest test = getTestOrThrow(testId);

        if (!test.hasEnoughSamples()) {
            log.warn("[ModelAbTest] 样本量不足，无法执行 T 检验: testId={}, needA={}, needB={}, haveA={}, haveB={}",
                    testId, test.config().minSampleSize(), test.config().minSampleSize(),
                    test.sampleCountA(), test.sampleCountB());
            return insufficientSamplesResult(test);
        }

        List<Double> samplesA = test.samplesA();
        List<Double> samplesB = test.samplesB();

        // 计算统计量
        double meanA = mean(samplesA);
        double meanB = mean(samplesB);
        double varA = variance(samplesA, meanA);
        double varB = variance(samplesB, meanB);
        double stdA = Math.sqrt(varA);
        double stdB = Math.sqrt(varB);

        int nA = samplesA.size();
        int nB = samplesB.size();

        // T 统计量
        double seA = varA / nA;
        double seB = varB / nB;
        double se = Math.sqrt(seA + seB);

        if (se == 0) {
            // 两组方差均为 0，无法计算
            return new TTestResult(
                    testId, meanA, meanB, stdA, stdB,
                    0, 1.0, nA + nB - 2,
                    false, null, null,
                    new double[]{meanA, meanA}, new double[]{meanB, meanB},
                    LocalDateTime.now()
            );
        }

        double tStatistic = (meanA - meanB) / se;

        // Welch-Satterthwaite 自由度近似
        double dfNumerator = Math.pow(seA + seB, 2);
        double dfDenominator = Math.pow(seA, 2) / (nA - 1) + Math.pow(seB, 2) / (nB - 1);
        double df = dfDenominator > 0 ? dfNumerator / dfDenominator : nA + nB - 2;

        // P 值（双尾检验，使用 t 分布近似）
        double pValue = 2 * tDistributionCdf(-Math.abs(tStatistic), df);

        // 置信区间（95%）
        double[] ciA = confidenceInterval(meanA, stdA, nA);
        double[] ciB = confidenceInterval(meanB, stdB, nB);

        // 判断显著性
        boolean significant = pValue < test.config().significanceLevel();
        String betterModel = null;
        String worseModel = null;
        if (significant) {
            if (meanA > meanB) {
                betterModel = test.config().modelAId();
                worseModel = test.config().modelBId();
            } else {
                betterModel = test.config().modelBId();
                worseModel = test.config().modelAId();
            }

            // 统计显著时自动完成测试
            if (test.status() == AbTestStatus.RUNNING) {
                stopTest(testId, true);
            }
        }

        TTestResult result = new TTestResult(
                testId, meanA, meanB, stdA, stdB,
                tStatistic, pValue, df,
                significant, betterModel, worseModel,
                ciA, ciB, LocalDateTime.now()
        );

        log.info("[ModelAbTest] T 检验完成: testId={}, meanA={}, meanB={}, t={}, p={}, significant={}, better={}",
                testId, Math.round(meanA * 100.0) / 100.0, Math.round(meanB * 100.0) / 100.0,
                Math.round(tStatistic * 10000.0) / 10000.0, Math.round(pValue * 100000.0) / 100000.0,
                significant, betterModel);

        return result;
    }

    /**
     * 生成可视化数据。
     *
     * <p>生成供前端图表渲染的聚合数据：
     * <ul>
     *   <li>统计摘要（均值、标准差、最值）</li>
     *   <li>时间序列（滚动均值，窗口大小 10）</li>
     *   <li>分数分布直方图（10 个区间）</li>
     * </ul>
     *
     * @param testId 测试 ID
     * @return 可视化数据
     */
    public VisualizationData generateVisualization(String testId) {
        AbTest test = getTestOrThrow(testId);

        MetricSummary summaryA = computeSummary(test.samplesA());
        MetricSummary summaryB = computeSummary(test.samplesB());

        List<double[]> timeSeriesA = computeRollingMean(test.samplesA(), 10);
        List<double[]> timeSeriesB = computeRollingMean(test.samplesB(), 10);

        int[] distributionA = computeHistogram(test.samplesA(), 0, 100, 10);
        int[] distributionB = computeHistogram(test.samplesB(), 0, 100, 10);

        VisualizationData viz = new VisualizationData(
                testId,
                test.config().modelAId(),
                test.config().modelBId(),
                summaryA, summaryB,
                timeSeriesA, timeSeriesB,
                distributionA, distributionB,
                LocalDateTime.now()
        );

        log.info("[ModelAbTest] 可视化数据已生成: testId={}, samplesA={}, samplesB={}",
                testId, test.sampleCountA(), test.sampleCountB());

        return viz;
    }

    // ════════════════════════════════════════════════════════════════
    // 查询方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 查询 A/B 测试。
     *
     * @param testId 测试 ID
     * @return 测试 Optional
     */
    public Optional<AbTest> getTest(String testId) {
        return Optional.ofNullable(testRegistry.get(testId));
    }

    /**
     * 列出所有 A/B 测试。
     *
     * @return 测试列表
     */
    public List<AbTest> listTests() {
        return new ArrayList<>(testRegistry.values().stream()
                .sorted((a, b) -> b.startedAt().compareTo(a.startedAt()))
                .toList());
    }

    /**
     * 按状态过滤测试。
     *
     * @param status 测试状态
     * @return 符合状态的测试列表
     */
    public List<AbTest> listTestsByStatus(AbTestStatus status) {
        return testRegistry.values().stream()
                .filter(t -> t.status() == status)
                .toList();
    }

    // ════════════════════════════════════════════════════════════════
    // 统计计算工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算列表均值。
     */
    private double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /**
     * 计算样本方差（除以 n-1）。
     */
    private double variance(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }
        double sumSq = 0;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
        }
        return sumSq / (values.size() - 1);
    }

    /**
     * 计算均值的 95% 置信区间。
     *
     * @return [lower, upper]
     */
    private double[] confidenceInterval(double mean, double std, int n) {
        if (n < 2 || std == 0) {
            return new double[]{mean, mean};
        }
        // 使用正态近似（大样本），z=1.96 对应 95% 置信度
        double z = 1.96;
        double margin = z * std / Math.sqrt(n);
        return new double[]{mean - margin, mean + margin};
    }

    /**
     * 计算统计摘要。
     */
    private MetricSummary computeSummary(List<Double> values) {
        if (values.isEmpty()) {
            return new MetricSummary(0, 0, 0, 0, 0);
        }
        double m = mean(values);
        double s = Math.sqrt(variance(values, m));
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return new MetricSummary(m, s, min, max, values.size());
    }

    /**
     * 计算滚动均值时间序列。
     *
     * @param values    原始值列表
     * @param windowSize 滚动窗口大小
     * @return 时间序列 [index, rollingMean]
     */
    private List<double[]> computeRollingMean(List<Double> values, int windowSize) {
        List<double[]> result = new ArrayList<>();
        if (values.isEmpty()) {
            return result;
        }
        for (int i = 0; i < values.size(); i++) {
            int start = Math.max(0, i - windowSize + 1);
            double sum = 0;
            int count = 0;
            for (int j = start; j <= i; j++) {
                sum += values.get(j);
                count++;
            }
            result.add(new double[]{i, sum / count});
        }
        return result;
    }

    /**
     * 计算直方图分布。
     *
     * @param values    原始值列表
     * @param minVal    最小值边界
     * @param maxVal    最大值边界
     * @param bins      区间数
     * @return 各区间的样本数
     */
    private int[] computeHistogram(List<Double> values, double minVal, double maxVal, int bins) {
        int[] histogram = new int[bins];
        double binWidth = (maxVal - minVal) / bins;
        if (binWidth <= 0) {
            return histogram;
        }
        for (double v : values) {
            int bin = (int) ((v - minVal) / binWidth);
            if (bin >= 0 && bin < bins) {
                histogram[bin]++;
            } else if (v >= maxVal) {
                histogram[bins - 1]++;
            }
        }
        return histogram;
    }

    /**
     * t 分布累积分布函数近似（使用正态分布近似 + 小样本修正）。
     *
     * <p>对于自由度 > 30 时正态近似精度足够；自由度较小时使用
     * 基于级数展开的近似。此处实现基于 Lanczos 近似的 Gamma 函数。
     *
     * @param t  T 值
     * @param df 自由度
     * @return 累积概率 P(T ≤ t)
     */
    private double tDistributionCdf(double t, double df) {
        // 自由度较大时使用正态近似
        if (df > 30) {
            return normalCdf(t);
        }

        // 小样本使用 Beta 函数近似
        // P(T ≤ t) = 1 - 0.5 * I_x(df/2, 1/2)，其中 x = df / (df + t^2)
        double x = df / (df + t * t);
        double ibeta = incompleteBeta(df / 2.0, 0.5, x);

        if (t > 0) {
            return 1 - 0.5 * ibeta;
        } else {
            return 0.5 * ibeta;
        }
    }

    /**
     * 标准正态分布累积分布函数（使用 erf 近似）。
     */
    private double normalCdf(double z) {
        // Abramowitz & Stegun 近似公式 7.1.26
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double d = 0.3989423 * Math.exp(-z * z / 2.0);
        double prob = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
        if (z > 0) {
            return 1 - prob;
        }
        return prob;
    }

    /**
     * 不完全 Beta 函数近似（使用连分式展开）。
     *
     * <p>用于 t 分布 CDF 的小样本修正。
     */
    private double incompleteBeta(double a, double b, double x) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }

        double lbeta = logGamma(a) + logGamma(b) - logGamma(a + b);
        double front = Math.exp(Math.log(x) * a + Math.log(1 - x) * b - lbeta);

        if (x < (a + 1) / (a + b + 2)) {
            return front * betaContinuedFraction(a, b, x) / a;
        } else {
            return 1 - front * betaContinuedFraction(b, a, 1 - x) / b;
        }
    }

    /**
     * Beta 函数连分式展开（Lentz 算法）。
     */
    private double betaContinuedFraction(double a, double b, double x) {
        int maxIter = 200;
        double epsilon = 1e-10;
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < epsilon) {
            d = epsilon;
        }
        d = 1.0 / d;
        double result = d;

        for (int m = 1; m <= maxIter; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < epsilon) {
                d = epsilon;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < epsilon) {
                c = epsilon;
            }
            d = 1.0 / d;
            result *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < epsilon) {
                d = epsilon;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < epsilon) {
                c = epsilon;
            }
            d = 1.0 / d;
            double delta = d * c;
            result *= delta;
            if (Math.abs(delta - 1.0) < epsilon) {
                break;
            }
        }
        return result;
    }

    /**
     * Log Gamma 函数（Lanczos 近似）。
     */
    private double logGamma(double x) {
        double[] cof = {
                76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5
        };
        double y = x;
        double tmp = x + 5.5;
        tmp -= (y + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) {
            y += 1;
            ser += cof[j] / y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    // ════════════════════════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取测试，不存在时抛出异常。
     */
    private AbTest getTestOrThrow(String testId) {
        return getTest(testId)
                .orElseThrow(() -> new IllegalArgumentException("A/B 测试不存在: " + testId));
    }

    /**
     * 样本不足时的 T 检验结果。
     */
    private TTestResult insufficientSamplesResult(AbTest test) {
        double meanA = mean(test.samplesA());
        double meanB = mean(test.samplesB());
        double stdA = Math.sqrt(variance(test.samplesA(), meanA));
        double stdB = Math.sqrt(variance(test.samplesB(), meanB));
        return new TTestResult(
                test.testId(), meanA, meanB, stdA, stdB,
                0, 1.0, 0,
                false, null, null,
                new double[]{0, 0}, new double[]{0, 0},
                LocalDateTime.now()
        );
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     */
    private static String writeJson(Map<String, Object> map) {
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }

    /**
     * 创建并配置 ObjectMapper 实例。
     */
    private static ObjectMapper createObjectMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
