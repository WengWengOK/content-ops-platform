package com.contentops.common.knowledge.qa;

import com.contentops.common.rag.AdvancedRagService;
import com.contentops.common.rag.HybridSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索侧错误识别（第 1 类：检索错误）。
 *
 * <p>复用已有检索链路的原始信号，把「隐性质量下降」显式化：
 * <ul>
 *   <li>{@code relevantCount} 低 / {@code sufficient=false} → 可能召回不足；</li>
 *   <li>改写两轮仍不充分 → 改写耗尽信号（{@code REWRITE_EXHAUSTED}）；</li>
 *   <li>最高分低于门槛 → 向量路相似度偏低（{@code LOW_TOP_SCORE}）；</li>
 *   <li>关键词路与向量路 topK 结果差异大 → 双路分歧（{@code PATH_DIVERGENT}）。</li>
 * </ul>
 *
 * <p>这些信号会写进「高风险检索日志」，人工每周过清单时优先看这些。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalRelevanceEvaluator {

    /** 检索侧风险信号码 */
    public static final String NO_RESULT = "NO_RESULT";
    public static final String LOW_RELEVANT_COUNT = "LOW_RELEVANT_COUNT";
    public static final String LOW_TOP_SCORE = "LOW_TOP_SCORE";
    public static final String REWRITE_EXHAUSTED = "REWRITE_EXHAUSTED";
    public static final String PATH_DIVERGENT = "PATH_DIVERGENT";
    /** 检索判定「找得到」，但模型答复「答不了」——知识库盲区 / 检索假阳性 */
    public static final String MODEL_REFUSED = "MODEL_REFUSED";

    private final QaProperties properties;

    /**
     * 检索充分性评估结果。
     *
     * @param candidateCount 候选条数
     * @param relevantCount  其中得分达标的条数
     * @param topScore       最高得分
     * @param meanScore      top3 平均分
     * @param sufficient     是否足以支撑作答
     * @param signals        命中的风险信号
     * @param reason         人类可读原因（写日志 / 前端展示）
     */
    public record RelevanceAssessment(int candidateCount, int relevantCount, double topScore, double meanScore,
                                      boolean sufficient, List<String> signals, String reason) {
    }

    /**
     * 评估检索质量。
     *
     * @param results      重排后的检索结果（可为空列表）
     * @param hybridHits   混合检索原始命中（用于双路分歧检测，可为 null）
     * @param rewriteRound 当前是第几轮改写（0 = 原始查询）
     */
    public RelevanceAssessment assess(List<AdvancedRagService.RetrievalResult> results,
                                      List<HybridSearchService.HybridSearchResult> hybridHits,
                                      int rewriteRound) {
        List<AdvancedRagService.RetrievalResult> hits = results == null ? List.of() : results;
        int candidateCount = hits.size();
        int relevantCount = 0;
        double topScore = 0;
        double scoreSum = 0;
        for (int i = 0; i < candidateCount; i++) {
            double score = hits.get(i).score();
            if (score >= properties.getRelevantScoreThreshold()) {
                relevantCount++;
            }
            if (score > topScore) {
                topScore = score;
            }
            if (i < 3) {
                scoreSum += score;
            }
        }
        double meanScore = candidateCount == 0 ? 0 : round(scoreSum / Math.min(3, candidateCount));

        List<String> signals = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (candidateCount == 0) {
            signals.add(NO_RESULT);
            reasons.add("检索无任何命中");
        } else {
            if (relevantCount < properties.getMinRelevantCount()) {
                signals.add(LOW_RELEVANT_COUNT);
                reasons.add("相关条数 " + relevantCount + " < 门槛 " + properties.getMinRelevantCount());
            }
            if (topScore < properties.getMinTopScore()) {
                signals.add(LOW_TOP_SCORE);
                reasons.add("最高分 " + round(topScore) + " < 门槛 " + properties.getMinTopScore());
            }
            if (isPathDivergent(hybridHits)) {
                signals.add(PATH_DIVERGENT);
                reasons.add("关键词路与向量路 top-K 结果分歧较大");
            }
        }
        boolean sufficient = candidateCount > 0
                && relevantCount >= properties.getMinRelevantCount()
                && topScore >= properties.getMinTopScore();
        if (!sufficient && rewriteRound >= properties.getMaxRewriteRounds()) {
            signals.add(REWRITE_EXHAUSTED);
            reasons.add("改写 " + rewriteRound + " 轮后仍不充分（已达上限 " + properties.getMaxRewriteRounds() + "）");
        }
        if (reasons.isEmpty()) {
            reasons.add("检索充分：相关 " + relevantCount + " 条，最高分 " + round(topScore));
        }
        RelevanceAssessment assessment = new RelevanceAssessment(candidateCount, relevantCount,
                round(topScore), meanScore, sufficient, List.copyOf(signals), String.join("；", reasons));
        log.debug("[QaRetrieval] candidate={}, relevant={}, top={}, sufficient={}, signals={}, round={}",
                candidateCount, relevantCount, round(topScore), sufficient, signals, rewriteRound);
        return assessment;
    }

    /**
     * 双路分歧：分别取关键词路、向量路的 top-N chunkId，重合度过低即认为两路「各说各话」。
     * 这种情况往往说明查询措辞与知识库表述差异大，或分词/嵌入有问题。
     */
    private boolean isPathDivergent(List<HybridSearchService.HybridSearchResult> hybridHits) {
        if (hybridHits == null || hybridHits.isEmpty()) {
            return false;
        }
        int size = Math.max(1, properties.getPathCompareSize());
        Set<String> keywordTop = new LinkedHashSet<>();
        Set<String> vectorTop = new LinkedHashSet<>();
        hybridHits.stream()
                .filter(h -> h.keywordScore() > 0)
                .sorted((a, b) -> Double.compare(b.keywordScore(), a.keywordScore()))
                .limit(size)
                .forEach(h -> keywordTop.add(h.chunkId()));
        hybridHits.stream()
                .filter(h -> h.vectorScore() > 0)
                .sorted((a, b) -> Double.compare(b.vectorScore(), a.vectorScore()))
                .limit(size)
                .forEach(h -> vectorTop.add(h.chunkId()));
        if (keywordTop.isEmpty() || vectorTop.isEmpty()) {
            // 只有单路命中时不算「分歧」，但已在其它信号里体现召回不足
            return false;
        }
        long overlap = keywordTop.stream().filter(vectorTop::contains).count();
        double union = keywordTop.size() + vectorTop.size() - overlap;
        double jaccard = union <= 0 ? 0 : overlap / union;
        return jaccard < properties.getPathDivergenceThreshold();
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
