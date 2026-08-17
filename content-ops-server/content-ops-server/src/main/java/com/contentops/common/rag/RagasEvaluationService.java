package com.contentops.common.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/**
 * RAGAS 评估服务 —— RAG 效果量化与多策略基线对比。
 *
 * <p><b>面试高频考点（P0 字节必考）</b>：「怎么量化你的 RAG 效果？有 baseline 吗？」
 * 本组件正是该问题的工程化回答：以 <a href="https://github.com/explodinggradients/ragas">RAGAS</a>
 * 评估框架为核心，对三种检索策略进行<b>基线对比（baseline comparison）</b>，用可量化指标证明
 * 检索增强的真实收益，而不是凭感觉说「效果更好」。
 *
 * <p><b>三种对比策略（multi-strategy baseline comparison）</b>：
 * <ol>
 *   <li><b>纯向量检索（VECTOR_ONLY）</b>：仅依赖语义相似（如 PGVector + BGE 嵌入），
 *       作为 baseline 的下界。</li>
 *   <li><b>混合检索（HYBRID）</b>：向量 + BM25 关键词检索，经 RRF 融合，兼顾语义与精确匹配。</li>
 *   <li><b>混合 + 重排序（HYBRID_WITH_RERANK）</b>：在混合检索基础上叠加 Cross-Encoder 精排，
 *       进一步提升送入 LLM 上下文的相关性，作为 baseline 的上界。</li>
 * </ol>
 *
 * <p><b>RAGAS 四大指标</b>：
 * <ul>
 *   <li><b>Faithfulness（忠实度）</b>：答案是否 grounded 于检索到的上下文，越低表示幻觉越严重。
 *       本实现以「答案关键词被上下文覆盖的比例」作为近似（真实 RAGAS 用 LLM 逐 claim 校验）。</li>
 *   <li><b>Answer Relevancy（答案相关性）</b>：答案与问题的相关程度。本实现以
 *       「问题关键词在答案中命中的比例」近似（真实 RAGAS 由答案反生成问题再比对 embedding）。</li>
 *   <li><b>Context Precision（上下文精确率）</b>：检索到的分块中真正相关的比例，
 *       相关性由「与 ground truth 的关键词重叠」判定。</li>
 *   <li><b>Context Recall（上下文召回率）</b>：ground truth 关键词被检索上下文覆盖的比例。</li>
 * </ul>
 *
 * <p><b>核心方法</b>：
 * <ul>
 *   <li>{@link #evaluateStrategy(EvaluationRequest)} —— 评估单条检索策略，返回四项 RAGAS 指标；</li>
 *   <li>{@link #compareStrategies(String, String, List, List, List)} —— 三策略基线对比，
 *       自动判定 winner 并给出推荐；</li>
 *   <li>{@link #generateTestSet(String, int)} —— 按分布（simple 0.5 / reasoning 0.25 /
 *       multi_context 0.25）生成测试集，用于回归评估。</li>
 * </ul>
 *
 * <p><b>实现说明</b>：指标计算基于关键词重叠（CJK 二元组 + 拉丁词，与
 * {@link HybridSearchService}、{@link RerankService} 的分词保持一致），无需外部 LLM 即可离线评估，
 * 适合做 CI / 回归基线；生产环境如需更精准的 Faithfulness / Answer Relevancy，应接入 LLM-as-a-judge。
 * {@link #compareStrategies} 在离线模式下会从各策略 top-K 检索结果合成「伪答案」模拟生成环节，
 * 因此 Faithfulness 通常高位（作为 grounding 健全性检查），真正的策略差异体现在
 * Answer Relevancy / Context Precision / Context Recall 三项。
 *
 * <p><b>面试答题要点</b>：量化 RAG 效果 = 明确指标（RAGAS 四件套） + 建立基线（多策略对比） +
 * 持续回归（测试集 + 自动判定 winner），三者缺一不可。
 *
 * @see HybridSearchService
 * @see RerankService
 * @see AdvancedRagService
 */
@Slf4j
@Component
public class RagasEvaluationService {

    // ──────────────────── 常量 ────────────────────

    /** 测试集分布：simple 0.5 / reasoning 0.25 / multi_context 0.25（multi_context 取余数补齐） */
    private static final double DISTRIBUTION_SIMPLE = 0.5;
    private static final double DISTRIBUTION_REASONING = 0.25;

    /** 综合得分权重（和为 1.0）：Faithfulness 最重要，其次 Precision/Recall，Relevancy 次之 */
    private static final double WEIGHT_FAITHFULNESS = 0.30;
    private static final double WEIGHT_RELEVANCY = 0.20;
    private static final double WEIGHT_PRECISION = 0.25;
    private static final double WEIGHT_RECALL = 0.25;

    /** 上下文相关性阈值：分块覆盖 ground truth 关键词比例 ≥ 该值视为相关块 */
    private static final double CONTEXT_RELEVANCE_THRESHOLD = 0.10;

    /** 离线合成伪答案取 top-K 分块（模拟 LLM 生成所依据的上下文） */
    private static final int SYNTH_ANSWER_TOP_K = 3;
    private static final int SYNTH_ANSWER_CHUNK_MAX_LEN = 200;

    /** 关键词分词结果缓存大小上限（超限清空，避免无界增长；本组件为 Spring 单例） */
    private static final int KEYWORD_CACHE_MAX_SIZE = 4_096;

    /** 指标保留小数位 */
    private static final int METRIC_SCALE = 4;

    /** 折叠连续空白用的预编译正则 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // ──────────────────── 状态字段 ────────────────────

    /**
     * 关键词集合记忆化缓存。
     *
     * <p>声明为 {@code static}：{@link MetricCalculator} 各实现是静态嵌套 record，
     * 需通过静态方法访问本缓存；本组件为 Spring 单例，静态缓存等价于实例缓存。
     */
    private static final ConcurrentHashMap<String, Set<String>> keywordCache = new ConcurrentHashMap<>();

    /** 各 domain 已生成测试用例计数（用于观测 / 去重辅助） */
    private final ConcurrentHashMap<String, Integer> generatedCount = new ConcurrentHashMap<>();

    // ──────────────────── 枚举 ────────────────────

    /** 测试用例类型（对应 RAGAS 测试集生成分布）。 */
    public enum TestType {
        /** 简单事实型：单一上下文即可回答。 */
        SIMPLE,
        /** 推理型：需在上下文基础上做多步推理。 */
        REASONING,
        /** 多上下文型：需融合多个检索分块作答。 */
        MULTI_CONTEXT
    }

    /** 检索策略（三策略基线对比）。 */
    public enum RetrievalStrategy {
        /** 纯向量检索（baseline 下界）。 */
        VECTOR_ONLY,
        /** 混合检索：向量 + BM25。 */
        HYBRID,
        /** 混合检索 + Cross-Encoder 重排序（baseline 上界）。 */
        HYBRID_WITH_RERANK
    }

    // ──────────────────── 记录类型 ────────────────────

    /**
     * 单次策略评估请求。
     *
     * @param query            用户问题
     * @param answer           待评估的答案（生成环节产出）
     * @param groundTruth      标准答案（作为 Context Precision / Recall 的参照）
     * @param retrievedContexts 检索到的上下文分块列表
     */
    public record EvaluationRequest(String query, String answer, String groundTruth,
                                    List<String> retrievedContexts) {
    }

    /**
     * RAGAS 四项指标结果。
     *
     * @param faithfulness      忠实度：答案 grounded 于上下文的程度，越低幻觉越严重
     * @param answerRelevancy   答案相关性：答案对问题的覆盖程度
     * @param contextPrecision  上下文精确率：相关块占检索块的比例
     * @param contextRecall     上下文召回率：ground truth 关键词被覆盖的比例
     * @param contextCount      检索上下文块数
     * @param answerLength      答案字符长度
     */
    public record RagasMetrics(double faithfulness, double answerRelevancy,
                               double contextPrecision, double contextRecall,
                               int contextCount, int answerLength) {
    }

    /**
     * 单条检索结果（跨策略统一表示）。
     *
     * @param chunkId  分块标识
     * @param content  分块文本
     * @param score    检索 / 融合得分
     * @param strategy 产出该结果的策略名
     */
    public record RetrievalResult(String chunkId, String content, double score, String strategy) {
    }

    /**
     * 三策略基线对比结果。
     *
     * @param query              用户问题
     * @param vectorOnly         纯向量检索指标
     * @param hybrid             混合检索指标
     * @param hybridWithRerank   混合 + 重排序指标
     * @param winner             胜出策略名（综合得分最高，平局优先更高级策略）
     * @param recommendation     人类可读的推荐说明
     */
    public record BaselineComparison(String query, RagasMetrics vectorOnly, RagasMetrics hybrid,
                                     RagasMetrics hybridWithRerank, String winner,
                                     String recommendation) {
    }

    /**
     * 测试用例。
     *
     * @param question         问题
     * @param groundTruthAnswer 标准答案
     * @param context          支撑上下文
     * @param type             用例类型
     */
    public record TestCase(String question, String groundTruthAnswer, String context, TestType type) {
    }

    // ──────────────────── 密封接口 + 模式匹配 ────────────────────

    /**
     * RAGAS 指标计算器（密封接口）。
     *
     * <p>密封层级配合 Java 21 模式匹配，使 {@link #evaluateStrategy(EvaluationRequest)}
     * 能在穷尽式 switch 中分发各指标计算，编译期保证新增指标不会遗漏处理。
     */
    private sealed interface MetricCalculator
            permits FaithfulnessCalculator, AnswerRelevancyCalculator,
                    ContextPrecisionCalculator, ContextRecallCalculator {
        /** 依据评估请求计算该指标，返回 [0,1] 区间得分。 */
        double compute(EvaluationRequest request);
    }

    /** Faithfulness 计算器：答案关键词被检索上下文覆盖的比例。 */
    private record FaithfulnessCalculator() implements MetricCalculator {
        @Override
        public double compute(EvaluationRequest request) {
            return computeFaithfulness(request);
        }
    }

    /** Answer Relevancy 计算器：问题关键词在答案中命中的比例。 */
    private record AnswerRelevancyCalculator() implements MetricCalculator {
        @Override
        public double compute(EvaluationRequest request) {
            return computeAnswerRelevancy(request);
        }
    }

    /** Context Precision 计算器：相关块占检索块的比例。 */
    private record ContextPrecisionCalculator() implements MetricCalculator {
        @Override
        public double compute(EvaluationRequest request) {
            return computeContextPrecision(request);
        }
    }

    /** Context Recall 计算器：ground truth 关键词被检索上下文覆盖的比例。 */
    private record ContextRecallCalculator() implements MetricCalculator {
        @Override
        public double compute(EvaluationRequest request) {
            return computeContextRecall(request);
        }
    }

    // ──────────────────── 核心方法 ────────────────────

    /**
     * 评估单条检索策略，返回 RAGAS 四项指标。
     *
     * <p>四项指标由 {@link MetricCalculator} 密封层级各实现计算，经穷尽式模式匹配 switch 分发，
     * 任一指标计算异常不阻断整体评估（返回 0）。所有得分被裁剪到 [0,1] 并保留四位小数。
     *
     * @param request 评估请求（query / answer / groundTruth / retrievedContexts）
     * @return RAGAS 指标结果
     */
    public RagasMetrics evaluateStrategy(EvaluationRequest request) {
        Objects.requireNonNull(request, "EvaluationRequest must not be null");
        long start = System.nanoTime();

        List<MetricCalculator> calculators = List.of(
                new FaithfulnessCalculator(),
                new AnswerRelevancyCalculator(),
                new ContextPrecisionCalculator(),
                new ContextRecallCalculator());

        double faithfulness = 0.0;
        double relevancy = 0.0;
        double precision = 0.0;
        double recall = 0.0;
        for (MetricCalculator calculator : calculators) {
            double value;
            try {
                value = clamp01(calculator.compute(request));
            } catch (Exception e) {
                log.warn("RAGAS metric computation failed for {}, defaulting to 0.0", calculator, e);
                value = 0.0;
            }
            // 穷尽式模式匹配：密封接口的全部 permit 子类型均已覆盖，无需 default
            switch (calculator) {
                case FaithfulnessCalculator f -> faithfulness = value;
                case AnswerRelevancyCalculator a -> relevancy = value;
                case ContextPrecisionCalculator p -> precision = value;
                case ContextRecallCalculator r -> recall = value;
            }
        }

        int contextCount = request.retrievedContexts() != null ? request.retrievedContexts().size() : 0;
        int answerLength = request.answer() != null ? request.answer().length() : 0;
        RagasMetrics metrics = new RagasMetrics(round(faithfulness), round(relevancy),
                round(precision), round(recall), contextCount, answerLength);
        log.info("RAGAS evaluateStrategy query='{}' -> {}", truncate(request.query(), 60), metrics);
        log.debug("RAGAS evaluation took {} ms for {} contexts",
                (System.nanoTime() - start) / 1_000_000, contextCount);
        return metrics;
    }

    /**
     * 三策略基线对比：对纯向量 / 混合 / 混合+重排序 三种检索结果分别评估 RAGAS 指标，
     * 自动判定 winner 并给出推荐。
     *
     * <p>离线模式下，各策略的「答案」由其 top-K 检索结果合成（模拟生成环节），
     * groundTruthAnswer 作为 Context Precision / Recall 的参照。综合得分按权重
     * （Faithfulness 0.30 / Relevancy 0.20 / Precision 0.25 / Recall 0.25）聚合，
     * 平局时优先更高级策略（rerank > hybrid > vector）。
     *
     * @param query             用户问题
     * @param groundTruthAnswer 标准答案
     * @param vectorResults     纯向量检索结果
     * @param hybridResults     混合检索结果
     * @param rerankedResults   混合 + 重排序结果
     * @return 基线对比结果（含三组指标、winner、推荐）
     */
    public BaselineComparison compareStrategies(String query, String groundTruthAnswer,
                                                List<RetrievalResult> vectorResults,
                                                List<RetrievalResult> hybridResults,
                                                List<RetrievalResult> rerankedResults) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(groundTruthAnswer, "groundTruthAnswer must not be null");
        List<RetrievalResult> vector = safeList(vectorResults);
        List<RetrievalResult> hybrid = safeList(hybridResults);
        List<RetrievalResult> reranked = safeList(rerankedResults);

        RagasMetrics vectorMetrics = evaluateStrategy(
                toRequest(query, groundTruthAnswer, vector, RetrievalStrategy.VECTOR_ONLY));
        RagasMetrics hybridMetrics = evaluateStrategy(
                toRequest(query, groundTruthAnswer, hybrid, RetrievalStrategy.HYBRID));
        RagasMetrics rerankMetrics = evaluateStrategy(
                toRequest(query, groundTruthAnswer, reranked, RetrievalStrategy.HYBRID_WITH_RERANK));

        String winner = determineWinner(vectorMetrics, hybridMetrics, rerankMetrics);
        String recommendation = buildRecommendation(vectorMetrics, hybridMetrics, rerankMetrics, winner);

        BaselineComparison comparison = new BaselineComparison(query, vectorMetrics, hybridMetrics,
                rerankMetrics, winner, recommendation);
        log.info("Baseline comparison for query='{}': winner={}, vector={}, hybrid={}, rerank={}",
                truncate(query, 60), winner, vectorMetrics, hybridMetrics, rerankMetrics);
        return comparison;
    }

    /**
     * 按分布生成测试集：simple 0.5 / reasoning 0.25 / multi_context 0.25。
     *
     * <p>用于 RAG 效果的回归评估。count 不能被 4 整除时，按四舍五入分配并将余数补到
     * multi_context，使总数恰为 count 且尽量贴近目标分布。测试用例基于 domain 模板生成。
     *
     * @param domain 业务领域（用于填充模板）
     * @param count  用例总数（≤0 返回空列表）
     * @return 测试用例列表（类型顺序已打乱，避免评估偏差）
     */
    public List<TestCase> generateTestSet(String domain, int count) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        if (count <= 0) {
            return List.of();
        }

        int simple = (int) Math.round(count * DISTRIBUTION_SIMPLE);
        int reasoning = (int) Math.round(count * DISTRIBUTION_REASONING);
        int multi = count - simple - reasoning;
        if (multi < 0) {
            multi = 0;
            simple = Math.max(0, count - reasoning);
        }
        generatedCount.merge(domain, count, Integer::sum);

        List<TestCase> cases = new ArrayList<>(count);
        cases.addAll(buildCases(domain, TestType.SIMPLE, simple));
        cases.addAll(buildCases(domain, TestType.REASONING, reasoning));
        cases.addAll(buildCases(domain, TestType.MULTI_CONTEXT, multi));
        Collections.shuffle(cases);
        log.info("Generated {} test cases for domain '{}' (simple={}, reasoning={}, multi_context={})",
                cases.size(), domain, simple, reasoning, multi);
        return cases;
    }

    // ──────────────────── 对比辅助 ────────────────────

    /**
     * 将检索结果转为评估请求：上下文取各分块内容，答案由 top-K 分块合成。
     */
    private EvaluationRequest toRequest(String query, String groundTruthAnswer,
                                        List<RetrievalResult> results, RetrievalStrategy strategy) {
        List<String> contexts = results.stream()
                .map(RetrievalResult::content)
                .filter(Objects::nonNull)
                .toList();
        String synthesizedAnswer = synthesizeAnswer(results);
        return new EvaluationRequest(query, synthesizedAnswer, groundTruthAnswer, contexts);
    }

    /**
     * 离线合成伪答案：取 top-K 分块内容拼接（模拟 LLM 依据上下文生成答案）。
     */
    private String synthesizeAnswer(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        int topN = Math.min(SYNTH_ANSWER_TOP_K, results.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < topN; i++) {
            String content = results.get(i).content();
            if (content != null && !content.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(truncate(content, SYNTH_ANSWER_CHUNK_MAX_LEN));
            }
        }
        return sb.toString();
    }

    /**
     * 判定胜出策略：综合得分最高者；平局优先更高级策略（rerank > hybrid > vector）。
     */
    private String determineWinner(RagasMetrics vector, RagasMetrics hybrid, RagasMetrics rerank) {
        double v = compositeScore(vector);
        double h = compositeScore(hybrid);
        double r = compositeScore(rerank);
        double best = Math.max(v, Math.max(h, r));
        if (Double.compare(best, r) == 0) {
            return RetrievalStrategy.HYBRID_WITH_RERANK.name();
        }
        if (Double.compare(best, h) == 0) {
            return RetrievalStrategy.HYBRID.name();
        }
        return RetrievalStrategy.VECTOR_ONLY.name();
    }

    /** 综合得分：四项 RAGAS 指标加权求和（权重和为 1.0）。 */
    private double compositeScore(RagasMetrics m) {
        return WEIGHT_FAITHFULNESS * m.faithfulness()
                + WEIGHT_RELEVANCY * m.answerRelevancy()
                + WEIGHT_PRECISION * m.contextPrecision()
                + WEIGHT_RECALL * m.contextRecall();
    }

    /**
     * 生成人类可读推荐：基于 winner 与各策略综合得分的差值，并提示延迟权衡。
     */
    private String buildRecommendation(RagasMetrics vector, RagasMetrics hybrid,
                                       RagasMetrics rerank, String winner) {
        double v = compositeScore(vector);
        double h = compositeScore(hybrid);
        double r = compositeScore(rerank);
        String base;
        String deltas;
        switch (winner) {
            case "HYBRID_WITH_RERANK" -> {
                base = "推荐采用 HYBRID_WITH_RERANK（混合检索 + Cross-Encoder 重排序）；";
                deltas = String.format(Locale.ROOT,
                        "综合得分 %.3f，较纯向量检索 %+.3f，较混合检索 %+.3f；", r, r - v, r - h);
            }
            case "HYBRID" -> {
                base = "推荐采用 HYBRID（向量 + BM25 混合检索）；";
                deltas = String.format(Locale.ROOT,
                        "综合得分 %.3f，较纯向量检索 %+.3f，较重排序 %+.3f（重排序未带来净收益）；",
                        h, h - v, h - r);
            }
            default -> {
                base = "推荐采用 VECTOR_ONLY（纯向量检索）；";
                deltas = String.format(Locale.ROOT,
                        "综合得分 %.3f，较混合检索 %+.3f，较重排序 %+.3f（混合 / 重排序未带来收益）；",
                        v, v - h, v - r);
            }
        }
        String tradeoff = "重排序会引入额外延迟，需在质量与延迟间权衡；"
                + "Context Recall 越高代表 ground truth 信息被召回越充分，"
                + "Faithfulness 越低代表幻觉风险越大。";
        return base + deltas + tradeoff;
    }

    // ──────────────────── 指标计算 ────────────────────

    /**
     * Faithfulness：答案关键词被检索上下文覆盖的比例。
     * <p>有答案但无上下文 → 0（完全幻觉）；无答案 → 0（无法评估）。
     */
    private static double computeFaithfulness(EvaluationRequest request) {
        String answer = request.answer();
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }
        Set<String> answerKeywords = keywords(answer);
        if (answerKeywords.isEmpty()) {
            return 0.0;
        }
        Set<String> contextKeywords = contextKeywordUnion(request.retrievedContexts());
        if (contextKeywords.isEmpty()) {
            return 0.0;
        }
        long supported = answerKeywords.stream().filter(contextKeywords::contains).count();
        return (double) supported / answerKeywords.size();
    }

    /**
     * Answer Relevancy：问题关键词在答案中命中的比例。
     * <p>衡量答案是否覆盖了问题的关键概念（真实 RAGAS 由答案反生成问题再比对 embedding）。
     */
    private static double computeAnswerRelevancy(EvaluationRequest request) {
        String query = request.query();
        String answer = request.answer();
        if (query == null || query.isBlank() || answer == null || answer.isBlank()) {
            return 0.0;
        }
        Set<String> queryKeywords = keywords(query);
        if (queryKeywords.isEmpty()) {
            return 0.0;
        }
        Set<String> answerKeywords = keywords(answer);
        long hit = queryKeywords.stream().filter(answerKeywords::contains).count();
        return (double) hit / queryKeywords.size();
    }

    /**
     * Context Precision：相关块占检索块的比例。
     * <p>分块与 ground truth 关键词重叠比例 ≥ {@link #CONTEXT_RELEVANCE_THRESHOLD} 视为相关。
     */
    private static double computeContextPrecision(EvaluationRequest request) {
        List<String> contexts = request.retrievedContexts();
        if (contexts == null || contexts.isEmpty()) {
            return 0.0;
        }
        Set<String> gtKeywords = keywords(request.groundTruth());
        if (gtKeywords.isEmpty()) {
            return 0.0;
        }
        int relevant = 0;
        for (String ctx : contexts) {
            if (ctx == null || ctx.isBlank()) {
                continue;
            }
            Set<String> ctxKeywords = keywords(ctx);
            if (ctxKeywords.isEmpty()) {
                continue;
            }
            long overlap = ctxKeywords.stream().filter(gtKeywords::contains).count();
            double ratio = (double) overlap / gtKeywords.size();
            if (ratio >= CONTEXT_RELEVANCE_THRESHOLD) {
                relevant++;
            }
        }
        return (double) relevant / contexts.size();
    }

    /**
     * Context Recall：ground truth 关键词被检索上下文覆盖的比例。
     */
    private static double computeContextRecall(EvaluationRequest request) {
        Set<String> gtKeywords = keywords(request.groundTruth());
        if (gtKeywords.isEmpty()) {
            return 0.0;
        }
        Set<String> contextKeywords = contextKeywordUnion(request.retrievedContexts());
        if (contextKeywords.isEmpty()) {
            return 0.0;
        }
        long covered = gtKeywords.stream().filter(contextKeywords::contains).count();
        return (double) covered / gtKeywords.size();
    }

    /** 合并所有检索上下文的关键词集合。 */
    private static Set<String> contextKeywordUnion(List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return Set.of();
        }
        Set<String> union = new HashSet<>();
        for (String ctx : contexts) {
            if (ctx != null && !ctx.isBlank()) {
                union.addAll(keywords(ctx));
            }
        }
        return union;
    }

    // ──────────────────── 测试集模板 ────────────────────

    /** 测试用例模板（占位符 %s 由 domain 填充）。 */
    private record TestCaseTemplate(String question, String groundTruth, String context) {
    }

    /** 取指定类型的模板池。 */
    private static TestCaseTemplate[] templatesFor(TestType type) {
        return switch (type) {
            case SIMPLE -> SIMPLE_TEMPLATES;
            case REASONING -> REASONING_TEMPLATES;
            case MULTI_CONTEXT -> MULTI_CONTEXT_TEMPLATES;
        };
    }

    /** 按类型批量构建测试用例，模板循环复用。 */
    private static List<TestCase> buildCases(String domain, TestType type, int n) {
        if (n <= 0) {
            return List.of();
        }
        TestCaseTemplate[] templates = templatesFor(type);
        List<TestCase> cases = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            TestCaseTemplate t = templates[i % templates.length];
            cases.add(new TestCase(
                    t.question.formatted(domain),
                    t.groundTruth.formatted(domain),
                    t.context.formatted(domain),
                    type));
        }
        return cases;
    }

    private static final TestCaseTemplate[] SIMPLE_TEMPLATES = {
            new TestCaseTemplate(
                    "%s的主要使用场景是什么？",
                    "%s主要用于内容运营自动化、多平台分发与数据回流分析。",
                    "%s平台支持从选题、创作、发布到数据分析的完整内容运营闭环，帮助运营团队提升效率。"),
            new TestCaseTemplate(
                    "%s支持哪些社交平台？",
                    "%s支持抖音、小红书、微信公众号、B站、快手等主流平台。",
                    "平台已接入抖音、小红书、微信公众号、B站、快手等内容分发渠道，支持一键多平台发布。"),
            new TestCaseTemplate(
                    "%s的后端技术栈基于什么？",
                    "%s基于Spring Boot与Java 21构建，集成Spring AI与LangChain4j。",
                    "后端采用Spring Boot 3与Java 21，集成Spring AI与LangChain4j实现Agent与RAG能力。"),
            new TestCaseTemplate(
                    "%s如何实现RAG检索？",
                    "%s通过PGVector向量检索与BM25关键词检索融合实现混合检索。",
                    "%s的RAG模块使用PGVector存储嵌入，结合内存BM25索引，通过RRF融合两路检索结果。")
    };

    private static final TestCaseTemplate[] REASONING_TEMPLATES = {
            new TestCaseTemplate(
                    "为什么%s在RAG中引入混合检索而非纯向量检索？",
                    "纯向量检索在精确术语匹配上较弱，混合检索结合BM25可同时提升召回率与精确率。",
                    "向量检索擅长语义相似但弱于精确词项匹配；BM25补充关键词精确匹配，RRF融合后兼顾语义与精确性。"),
            new TestCaseTemplate(
                    "在%s中，何时应该启用Cross-Encoder重排序？",
                    "当候选数量较多且对最终排序质量要求高时启用，但需权衡延迟开销。",
                    "Cross-Encoder逐对打分精度高但延迟大，适合候选池较大、对top结果质量敏感的场景。"),
            new TestCaseTemplate(
                    "为什么%s需要对RAG效果做量化评估？",
                    "凭感觉无法判断检索增强是否真正有效，需用RAGAS指标建立基线并持续回归。",
                    "RAG效果受分块、嵌入、检索、重排序多环节影响，需用Faithfulness等指标量化才能定位瓶颈。")
    };

    private static final TestCaseTemplate[] MULTI_CONTEXT_TEMPLATES = {
            new TestCaseTemplate(
                    "对比%s中三种检索策略的差异。",
                    "纯向量靠语义相似、混合检索叠加BM25关键词、混合加重排序再用Cross-Encoder精排。",
                    "策略一为纯向量检索；策略二在向量基础上融合BM25；策略三进一步用Cross-Encoder对候选二次精排。"),
            new TestCaseTemplate(
                    "%s的RAG评估包含哪些指标及其含义？",
                    "包括Faithfulness忠实度、Answer Relevancy答案相关性、Context Precision上下文精确率、Context Recall上下文召回率。",
                    "Faithfulness衡量答案是否grounded于上下文；Context Precision衡量相关块占比；Context Recall衡量相关上下文被召回比例。"),
            new TestCaseTemplate(
                    "%s中混合检索与重排序分别解决什么问题？",
                    "混合检索解决召回率（语义+精确），重排序解决精确率（精排top结果）。",
                    "混合检索扩大有效召回；Cross-Encoder重排序对召回候选精排，提升送入LLM上下文的相关性。")
    };

    // ──────────────────── 关键词 / 分词工具 ────────────────────

    /**
     * 提取关键词集合（带记忆化缓存）。CJK 二元组 + 拉丁词（小写），过滤停用词，
     * 与 {@link HybridSearchService}、{@link RerankService} 的分词保持一致。
     */
    private static Set<String> keywords(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        if (keywordCache.size() > KEYWORD_CACHE_MAX_SIZE) {
            keywordCache.clear();
        }
        return keywordCache.computeIfAbsent(text, RagasEvaluationService::extractKeywords);
    }

    /** 分词后去停用词，保留顺序。 */
    private static Set<String> extractKeywords(String text) {
        Map<String, Integer> freq = termFrequency(text);
        Set<String> keywords = new LinkedHashSet<>();
        for (String term : freq.keySet()) {
            if (!isStopword(term)) {
                keywords.add(term);
            }
        }
        return keywords;
    }

    /** 词频向量：CJK 字符二元组 + 拉丁/数字词（小写）。 */
    private static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> freq = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return freq;
        }
        StringBuilder latin = new StringBuilder();
        char prevCjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                if (!latin.isEmpty()) {
                    freq.merge(latin.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
                    latin.setLength(0);
                }
                if (prevCjk != 0) {
                    freq.merge("" + prevCjk + c, 1, Integer::sum);
                }
                prevCjk = c;
            } else {
                prevCjk = 0;
                if (Character.isLetterOrDigit(c)) {
                    latin.append(c);
                } else if (!latin.isEmpty()) {
                    freq.merge(latin.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
                    latin.setLength(0);
                }
            }
        }
        if (!latin.isEmpty()) {
            freq.merge(latin.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return freq;
    }

    /** 判断字符是否为 CJK 汉字。 */
    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /** 停用词集合（中文虚词 / 助词 / 高频无义字 + 英文停用词）。 */
    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "及", "或", "等", "也", "都", "就", "还", "又", "把", "被",
            "让", "使", "为", "对", "由", "从", "向", "到", "于", "以", "之", "其", "这", "那", "你", "我",
            "他", "她", "它", "们", "个", "些", "中", "上", "下", "里", "外", "前", "后", "而", "但", "却",
            "若", "如", "即", "则", "且", "并", "要", "可", "能", "会", "一", "不", "没", "有", "无",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being", "of", "to", "in", "on",
            "at", "by", "for", "with", "about", "as", "into", "like", "through", "after", "over",
            "between", "out", "against", "during", "without", "before", "under", "around", "among",
            "and", "but", "or", "so", "yet", "nor", "if", "then", "that", "this", "these", "those",
            "it", "its", "i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them",
            "my", "your", "his", "our", "their", "what", "which", "who", "whom", "whose", "when",
            "where", "why", "how", "all", "any", "both", "each", "few", "more", "most", "other",
            "some", "such", "no", "not", "only", "own", "same", "than", "too", "very", "can", "will",
            "just", "should", "now"
    );

    private static boolean isStopword(String term) {
        return STOPWORDS.contains(term);
    }

    // ──────────────────── 通用工具 ────────────────────

    /** 空安全列表。 */
    private static List<RetrievalResult> safeList(List<RetrievalResult> list) {
        return list != null ? list : List.of();
    }

    /** 裁剪到 [0,1]。 */
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 保留 {@link #METRIC_SCALE} 位小数。 */
    private static double round(double v) {
        double pow = Math.pow(10, METRIC_SCALE);
        return Math.round(v * pow) / pow;
    }

    /** 折叠连续空白并去除首尾空白。 */
    private static String collapseWhitespace(String s) {
        if (s == null) {
            return "";
        }
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    /** 截断文本（折叠空白后），超出加省略号。 */
    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String collapsed = collapseWhitespace(text);
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max) + "...";
    }
}
