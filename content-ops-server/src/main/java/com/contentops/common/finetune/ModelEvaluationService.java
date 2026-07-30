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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模型评估服务（编排层，不依赖 GPU 推理）。
 *
 * <p>对微调后（或基线）模型的输出进行五维度启发式评估，为模型选型、A/B 测试、
 * 灰度发布提供量化依据。评估算法基于文本统计特征，不调用 LLM，可在毫秒级完成。
 *
 * <h3>五维评估体系</h3>
 * <ul>
 *   <li><b>准确性 (Accuracy)</b>：与标准答案的文本相似度（基于 Jaccard 相似系数与字符重叠率）</li>
 *   <li><b>流畅性 (Fluency)</b>：困惑度（Perplexity）估算，基于字符 n-gram 频率分布</li>
 *   <li><b>相关性 (Relevance)</b>：回答与问题的关键词重合度和语义覆盖度</li>
 *   <li><b>安全性 (Safety)</b>：是否包含有害内容（基于关键词黑名单）</li>
 *   <li><b>一致性 (Consistency)</b>：多次生成结果的稳定性（方差倒数映射）</li>
 * </ul>
 *
 * <h3>评分规则</h3>
 * <p>每个维度评分 0-100，加权总分 = Σ(维度分 × 权重)。权重由
 * {@link FineTuneProperties.EvaluationConfig#getWeights()} 配置。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 单条评估
 * EvaluationResult result = evaluationService.evaluate(
 *     "qwen2.5-7b-finetuned-v1",
 *     "请解释什么是LoRA微调",
 *     "LoRA是一种低秩适配方法，通过...",
 *     "LoRA（Low-Rank Adaptation）通过在冻结的预训练权重旁注入低秩分解矩阵..."
 * );
 *
 * // 批量评估
 * List<EvaluationResult> results = evaluationService.evaluateBatch(
 *     "qwen2.5-7b-finetuned-v1", evaluationItems
 * );
 * }</pre>
 *
 * @see FineTuneProperties
 * @see ModelFineTuneManager
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelEvaluationService {

    private final FineTuneProperties properties;

    /** Jackson ObjectMapper，用于评估结果序列化 */
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    // ──────────────── 重复短语检测正则 ────────────────
    private static final Pattern REPEAT_PHRASE_PATTERN = Pattern.compile("(.{4,})\\1{1,}");

    // ════════════════════════════════════════════════════════════════
    // 评估维度枚举
    // ════════════════════════════════════════════════════════════════

    /**
     * 评估维度枚举。
     */
    public enum Dimension {
        /** 准确性：与标准答案的相似度 */
        ACCURACY("准确性"),
        /** 流畅性：困惑度估算 */
        FLUENCY("流畅性"),
        /** 相关性：回答与问题的相关性 */
        RELEVANCE("相关性"),
        /** 安全性：是否包含有害内容 */
        SAFETY("安全性"),
        /** 一致性：多次生成的稳定性 */
        CONSISTENCY("一致性");

        private final String label;

        Dimension(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /** 从配置 key 转换为枚举 */
        public static Dimension fromKey(String key) {
            return Dimension.valueOf(key.toUpperCase(Locale.ROOT));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 评估结果记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 单条评估结果。
     *
     * @param modelId     被评估的模型 ID
     * @param question    评估问题
     * @param answer      模型生成的回答
     * @param reference   标准答案（可为 null）
     * @param scores      各维度评分（0-100）
     * @param totalScore  加权总分（0-100）
     * @param passed      是否达到通过阈值
     * @param details     评估详情（各维度的具体指标值）
     * @param evaluatedAt 评估时间
     */
    public record EvaluationResult(
            String modelId,
            String question,
            String answer,
            String reference,
            Map<Dimension, Integer> scores,
            double totalScore,
            boolean passed,
            Map<String, Object> details,
            LocalDateTime evaluatedAt
    ) {
        public EvaluationResult {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
            details = details == null ? Map.of() : Map.copyOf(details);
            evaluatedAt = evaluatedAt == null ? LocalDateTime.now() : evaluatedAt;
        }

        /**
         * 获取指定维度的评分。
         *
         * @param dimension 评估维度
         * @return 评分（0-100），未评估时返回 0
         */
        public int getScore(Dimension dimension) {
            return scores.getOrDefault(dimension, 0);
        }

        /**
         * 将评估结果序列化为 JSON 字符串。
         *
         * @return JSON 字符串
         */
        public String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modelId", modelId);
            map.put("question", question);
            map.put("answer", answer);
            map.put("reference", reference);
            Map<String, Integer> scoresMap = new LinkedHashMap<>();
            scores.forEach((dim, score) -> scoresMap.put(dim.name().toLowerCase(Locale.ROOT), score));
            map.put("scores", scoresMap);
            map.put("totalScore", Math.round(totalScore * 100.0) / 100.0);
            map.put("passed", passed);
            map.put("details", details);
            map.put("evaluatedAt", evaluatedAt.toString());
            return writeJson(map);
        }
    }

    /**
     * 批量评估结果汇总。
     *
     * @param modelId        被评估的模型 ID
     * @param results        逐条评估结果列表
     * @param averageScores  各维度平均评分
     * @param averageTotal   平均加权总分
     * @param passRate       通过率（0.0 - 1.0）
     * @param evaluatedAt    评估时间
     */
    public record BatchEvaluationResult(
            String modelId,
            List<EvaluationResult> results,
            Map<Dimension, Double> averageScores,
            double averageTotal,
            double passRate,
            LocalDateTime evaluatedAt
    ) {
        public BatchEvaluationResult {
            results = results == null ? List.of() : List.copyOf(results);
            averageScores = averageScores == null ? Map.of() : Map.copyOf(averageScores);
            evaluatedAt = evaluatedAt == null ? LocalDateTime.now() : evaluatedAt;
        }

        /**
         * 将批量评估结果序列化为 JSON 字符串。
         *
         * @return JSON 字符串
         */
        public String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modelId", modelId);
            map.put("sampleCount", results.size());
            Map<String, Double> avgMap = new LinkedHashMap<>();
            averageScores.forEach((dim, score) ->
                    avgMap.put(dim.name().toLowerCase(Locale.ROOT), Math.round(score * 100.0) / 100.0));
            map.put("averageScores", avgMap);
            map.put("averageTotal", Math.round(averageTotal * 100.0) / 100.0);
            map.put("passRate", Math.round(passRate * 10000.0) / 10000.0);
            map.put("evaluatedAt", evaluatedAt.toString());
            return writeJson(map);
        }
    }

    /**
     * 单条评估输入项。
     *
     * @param question   评估问题
     * @param answer     模型生成的回答
     * @param reference  标准答案（可为 null）
     * @param variations 多次生成变体（用于一致性评估，可为空列表）
     */
    public record EvaluationItem(
            String question,
            String answer,
            String reference,
            List<String> variations
    ) {
        public EvaluationItem {
            variations = variations == null ? List.of() : List.copyOf(variations);
        }

        /** 简易构造：仅问题和回答 */
        public static EvaluationItem of(String question, String answer) {
            return new EvaluationItem(question, answer, null, List.of());
        }

        /** 带标准答案的构造 */
        public static EvaluationItem withReference(String question, String answer, String reference) {
            return new EvaluationItem(question, answer, reference, List.of());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 核心评估方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 对单条模型输出进行五维度评估。
     *
     * @param modelId   被评估的模型 ID
     * @param question  评估问题
     * @param answer    模型生成的回答
     * @param reference 标准答案（可为 null，为 null 时准确性维度不评分）
     * @return 评估结果
     */
    public EvaluationResult evaluate(String modelId, String question, String answer, String reference) {
        return evaluate(modelId, new EvaluationItem(question, answer, reference, List.of()));
    }

    /**
     * 对单条评估项进行五维度评估。
     *
     * <p>评估流程：
     * <ol>
     *   <li>准确性：若有标准答案，计算 Jaccard 相似系数 + 字符重叠率</li>
     *   <li>流畅性：基于字符 n-gram 困惑度估算</li>
     *   <li>相关性：问题与回答的关键词重合度</li>
     *   <li>安全性：关键词黑名单匹配</li>
     *   <li>一致性：若有多组变体，计算变体间相似度方差</li>
     * </ol>
     *
     * @param modelId 被评估的模型 ID
     * @param item    评估输入项
     * @return 评估结果
     */
    public EvaluationResult evaluate(String modelId, EvaluationItem item) {
        log.debug("[ModelEvaluation] 开始评估: modelId={}, question={}", modelId, item.question());

        Map<Dimension, Integer> scores = new LinkedHashMap<>();
        Map<String, Object> details = new LinkedHashMap<>();

        // 1. 准确性评估
        int accuracyScore;
        if (item.reference() != null && !item.reference().isBlank()) {
            double jaccard = jaccardSimilarity(item.answer(), item.reference());
            double overlap = charOverlapRate(item.answer(), item.reference());
            double accuracy = (jaccard * 0.5 + overlap * 0.5) * 100;
            accuracyScore = clamp((int) Math.round(accuracy), 0, 100);
            details.put("accuracyJaccard", Math.round(jaccard * 10000.0) / 10000.0);
            details.put("accuracyCharOverlap", Math.round(overlap * 10000.0) / 10000.0);
        } else {
            // 无标准答案时，基于回答完整度给中性分
            accuracyScore = item.answer() != null && item.answer().length() > 20 ? 60 : 30;
            details.put("accuracySkipped", true);
        }
        scores.put(Dimension.ACCURACY, accuracyScore);

        // 2. 流畅性评估
        double perplexity = estimatePerplexity(item.answer());
        int fluencyScore = perplexityToScore(perplexity);
        scores.put(Dimension.FLUENCY, fluencyScore);
        details.put("estimatedPerplexity", Math.round(perplexity * 100.0) / 100.0);

        // 3. 相关性评估
        double relevance = relevanceScore(item.question(), item.answer());
        int relevanceScore = clamp((int) Math.round(relevance * 100), 0, 100);
        scores.put(Dimension.RELEVANCE, relevanceScore);
        details.put("keywordOverlapRate", Math.round(relevance * 10000.0) / 10000.0);

        // 4. 安全性评估
        List<String> violations = checkSafety(item.answer());
        int safetyScore = violations.isEmpty() ? 100 : Math.max(0, 100 - violations.size() * 30);
        scores.put(Dimension.SAFETY, safetyScore);
        details.put("safetyViolations", violations);

        // 5. 一致性评估
        int consistencyScore;
        if (!item.variations().isEmpty()) {
            double avgSim = averageVariationSimilarity(item.answer(), item.variations());
            consistencyScore = clamp((int) Math.round(avgSim * 100), 0, 100);
            details.put("variationSimilarity", Math.round(avgSim * 10000.0) / 10000.0);
            details.put("variationCount", item.variations().size());
        } else {
            // 无变体时给中性分
            consistencyScore = 70;
            details.put("consistencySkipped", true);
        }
        scores.put(Dimension.CONSISTENCY, consistencyScore);

        // 加权总分
        double totalScore = calculateWeightedTotal(scores);
        double threshold = properties.getEvaluation().getPassThreshold();
        boolean passed = totalScore >= threshold;

        EvaluationResult result = new EvaluationResult(
                modelId, item.question(), item.answer(), item.reference(),
                scores, totalScore, passed, details, LocalDateTime.now()
        );

        log.info("[ModelEvaluation] 评估完成: modelId={}, total={}/{}, passed={}, scores={}",
                modelId, Math.round(totalScore), threshold, passed, scores);

        return result;
    }

    /**
     * 批量评估模型输出。
     *
     * @param modelId 被评估的模型 ID
     * @param items   评估项列表
     * @return 批量评估汇总结果
     */
    public BatchEvaluationResult evaluateBatch(String modelId, List<EvaluationItem> items) {
        if (items == null || items.isEmpty()) {
            log.warn("[ModelEvaluation] 批量评估项为空: modelId={}", modelId);
            return new BatchEvaluationResult(modelId, List.of(), Map.of(), 0.0, 0.0, LocalDateTime.now());
        }

        List<EvaluationResult> results = new ArrayList<>();
        for (EvaluationItem item : items) {
            results.add(evaluate(modelId, item));
        }

        // 计算各维度平均分
        Map<Dimension, Double> avgScores = new LinkedHashMap<>();
        double totalSum = 0;
        int passCount = 0;
        for (Dimension dim : Dimension.values()) {
            double sum = 0;
            int count = 0;
            for (EvaluationResult r : results) {
                Integer score = r.scores().get(dim);
                if (score != null) {
                    sum += score;
                    count++;
                }
            }
            avgScores.put(dim, count > 0 ? sum / count : 0.0);
        }
        for (EvaluationResult r : results) {
            totalSum += r.totalScore();
            if (r.passed()) {
                passCount++;
            }
        }
        double avgTotal = totalSum / results.size();
        double passRate = (double) passCount / results.size();

        BatchEvaluationResult batchResult = new BatchEvaluationResult(
                modelId, results, avgScores, avgTotal, passRate, LocalDateTime.now()
        );

        log.info("[ModelEvaluation] 批量评估完成: modelId={}, samples={}, avgTotal={}, passRate={}",
                modelId, results.size(), Math.round(avgTotal * 100.0) / 100.0,
                Math.round(passRate * 10000.0) / 10000.0);

        return batchResult;
    }

    // ════════════════════════════════════════════════════════════════
    // 准确性评估
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算两段文本的 Jaccard 相似系数（基于字符 bigram 集合）。
     *
     * <p>Jaccard = |A ∩ B| / |A ∪ B|，取值 0-1。
     *
     * @param text1 文本 1
     * @param text2 文本 2
     * @return Jaccard 相似系数
     */
    private double jaccardSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null || text1.length() < 2 || text2.length() < 2) {
            return 0.0;
        }
        Set<String> bigrams1 = extractBigrams(text1);
        Set<String> bigrams2 = extractBigrams(text2);

        Set<String> intersection = new HashSet<>(bigrams1);
        intersection.retainAll(bigrams2);

        Set<String> union = new HashSet<>(bigrams1);
        union.addAll(bigrams2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * 计算字符重叠率（基于唯一字符集合的重叠比例）。
     *
     * @param text1 文本 1
     * @param text2 文本 2
     * @return 重叠率（0-1）
     */
    private double charOverlapRate(String text1, String text2) {
        if (text1 == null || text2 == null || text1.isEmpty() || text2.isEmpty()) {
            return 0.0;
        }
        Set<Character> chars1 = new HashSet<>();
        for (char c : text1.toCharArray()) {
            chars1.add(c);
        }
        Set<Character> chars2 = new HashSet<>();
        for (char c : text2.toCharArray()) {
            chars2.add(c);
        }
        Set<Character> intersection = new HashSet<>(chars1);
        intersection.retainAll(chars2);
        Set<Character> union = new HashSet<>(chars1);
        union.addAll(chars2);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * 提取文本的字符 bigram 集合。
     */
    private Set<String> extractBigrams(String text) {
        Set<String> bigrams = new HashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            bigrams.add(text.substring(i, i + 2));
        }
        return bigrams;
    }

    // ════════════════════════════════════════════════════════════════
    // 流畅性评估（困惑度估算）
    // ════════════════════════════════════════════════════════════════

    /**
     * 估算文本的困惑度（Perplexity）。
     *
     * <p>基于字符 n-gram 频率分布的近似估算：
     * <ul>
     *   <li>统计字符 unigram 和 bigram 频率</li>
     *   <li>计算平均条件概率的几何平均</li>
     *   <li>映射为困惑度（越低越流畅）</li>
     * </ul>
     *
     * <p>注意：此为启发式估算，非真实模型困惑度。
     *
     * @param text 待评估文本
     * @return 困惑度估算值（通常 1-100，越低越流畅）
     */
    private double estimatePerplexity(String text) {
        if (text == null || text.length() < 2) {
            return 100.0; // 极短文本给高困惑度
        }

        // 统计 bigram 频率
        Map<String, Integer> bigramFreq = new LinkedHashMap<>();
        Map<Character, Integer> charFreq = new LinkedHashMap<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            charFreq.merge(c, 1, Integer::sum);
            if (i < text.length() - 1) {
                String bigram = text.substring(i, i + 2);
                bigramFreq.merge(bigram, 1, Integer::sum);
            }
        }

        // 计算平均对数概率
        double logProbSum = 0;
        int count = 0;
        for (Map.Entry<String, Integer> entry : bigramFreq.entrySet()) {
            String bigram = entry.getKey();
            char firstChar = bigram.charAt(0);
            int firstCharCount = charFreq.getOrDefault(firstChar, 1);
            double prob = (double) entry.getValue() / firstCharCount;
            if (prob > 0) {
                logProbSum += Math.log(prob);
                count++;
            }
        }

        if (count == 0) {
            return 100.0;
        }

        double avgLogProb = logProbSum / count;
        double perplexity = Math.exp(-avgLogProb);

        // 重复短语惩罚
        var matcher = REPEAT_PHRASE_PATTERN.matcher(text);
        int repeatCount = 0;
        while (matcher.find()) {
            repeatCount++;
        }
        perplexity *= (1 + repeatCount * 0.5);

        return perplexity;
    }

    /**
     * 将困惑度映射为 0-100 的评分。
     *
     * <p>映射规则（经验值）：
     * <ul>
     *   <li>困惑度 ≤ 5：90-100 分（非常流畅）</li>
     *   <li>困惑度 5-15：70-89 分</li>
     *   <li>困惑度 15-30：50-69 分</li>
     *   <li>困惑度 > 30：0-49 分</li>
     * </ul>
     *
     * @param perplexity 困惑度
     * @return 流畅性评分（0-100）
     */
    private int perplexityToScore(double perplexity) {
        if (perplexity <= 5) {
            return clamp((int) (100 - (perplexity - 1) * 2.5), 90, 100);
        } else if (perplexity <= 15) {
            return clamp((int) (90 - (perplexity - 5) * 2.0), 70, 89);
        } else if (perplexity <= 30) {
            return clamp((int) (70 - (perplexity - 15) * 1.33), 50, 69);
        } else if (perplexity <= 60) {
            return clamp((int) (50 - (perplexity - 30) * 1.0), 20, 49);
        } else {
            return clamp((int) Math.max(0, 20 - (perplexity - 60) * 0.3), 0, 19);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 相关性评估
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算回答与问题的相关性。
     *
     * <p>基于关键词重合度：
     * <ul>
     *   <li>从问题中提取关键词（中文 2-4 字、英文单词）</li>
     *   <li>计算关键词在回答中出现的比例</li>
     *   <li>加入回答长度因子（过短回答扣分）</li>
     * </ul>
     *
     * @param question 问题
     * @param answer   回答
     * @return 相关性得分（0-1）
     */
    private double relevanceScore(String question, String answer) {
        if (question == null || answer == null || question.isBlank() || answer.isBlank()) {
            return 0.0;
        }

        // 提取问题关键词
        Set<String> questionKeywords = extractKeywords(question);
        if (questionKeywords.isEmpty()) {
            return 0.5; // 无法提取关键词时给中性分
        }

        // 统计关键词在回答中的命中率
        int hitCount = 0;
        for (String keyword : questionKeywords) {
            if (answer.contains(keyword)) {
                hitCount++;
            }
        }
        double hitRate = (double) hitCount / questionKeywords.size();

        // 回答长度因子（50-2000 字为理想区间）
        double lengthFactor;
        int answerLen = answer.length();
        if (answerLen >= 50 && answerLen <= 2000) {
            lengthFactor = 1.0;
        } else if (answerLen >= 20) {
            lengthFactor = 0.8;
        } else {
            lengthFactor = 0.5;
        }

        return hitRate * lengthFactor;
    }

    /**
     * 从文本中提取关键词。
     *
     * <p>提取中文 2-4 字词组和英文单词（长度 ≥ 2）。
     *
     * @param text 输入文本
     * @return 关键词集合
     */
    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();

        // 提取中文词组（连续中文字符，2-4 字）
        var chinesePattern = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}");
        var cnMatcher = chinesePattern.matcher(text);
        while (cnMatcher.find()) {
            keywords.add(cnMatcher.group());
        }

        // 提取英文单词（长度 ≥ 2）
        var englishPattern = Pattern.compile("[a-zA-Z]{2,}");
        var enMatcher = englishPattern.matcher(text);
        while (enMatcher.find()) {
            keywords.add(enMatcher.group().toLowerCase(Locale.ROOT));
        }

        return keywords;
    }

    // ════════════════════════════════════════════════════════════════
    // 安全性评估
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查文本是否包含有害内容。
     *
     * <p>基于 {@link FineTuneProperties.EvaluationConfig#getSafetyKeywords()} 配置的关键词黑名单。
     *
     * @param text 待检查文本
     * @return 命中的关键词列表（空列表表示安全）
     */
    private List<String> checkSafety(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        for (String keyword : properties.getEvaluation().getSafetyKeywords()) {
            if (text.contains(keyword)) {
                violations.add(keyword);
            }
        }
        return violations;
    }

    // ════════════════════════════════════════════════════════════════
    // 一致性评估
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算主回答与变体之间的平均相似度。
     *
     * @param mainAnswer 主回答
     * @param variations 变体列表
     * @return 平均相似度（0-1）
     */
    private double averageVariationSimilarity(String mainAnswer, List<String> variations) {
        if (variations.isEmpty()) {
            return 0.7; // 无变体时给中性分
        }
        double sum = 0;
        for (String variation : variations) {
            sum += jaccardSimilarity(mainAnswer, variation);
        }
        return sum / variations.size();
    }

    // ════════════════════════════════════════════════════════════════
    // 加权总分计算
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据配置的权重计算加权总分。
     *
     * @param scores 各维度评分
     * @return 加权总分（0-100）
     */
    private double calculateWeightedTotal(Map<Dimension, Integer> scores) {
        Map<String, Double> weights = properties.getEvaluation().getWeights();
        double total = 0;
        double weightSum = 0;
        for (Map.Entry<Dimension, Integer> entry : scores.entrySet()) {
            String key = entry.getKey().name().toLowerCase(Locale.ROOT);
            double weight = weights.getOrDefault(key, 0.0);
            total += entry.getValue() * weight;
            weightSum += weight;
        }
        // 权重未归一化时进行归一
        return weightSum > 0 ? total / weightSum * (weights.values().stream().mapToDouble(Double::doubleValue).sum()) : total;
    }

    // ════════════════════════════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 将分数限制在 [min, max] 范围内。
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
