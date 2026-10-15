package com.contentops.common.knowledge.qa;

import com.contentops.common.rag.AdvancedRagService;
import com.contentops.common.rag.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索侧错误识别测试：相关条数 / 最高分 / 改写耗尽 / 双路分歧。
 */
class RetrievalRelevanceEvaluatorTest {

    private QaProperties properties;
    private RetrievalRelevanceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new QaProperties();
        evaluator = new RetrievalRelevanceEvaluator(properties);
    }

    private AdvancedRagService.RetrievalResult hit(String chunkId, double score) {
        return new AdvancedRagService.RetrievalResult(chunkId, "内容 " + chunkId, score, score,
                Map.of("title", chunkId), "advanced-rag");
    }

    private HybridSearchService.HybridSearchResult hybrid(String chunkId, double vector, double keyword) {
        return new HybridSearchService.HybridSearchResult(chunkId, "内容 " + chunkId, vector, keyword,
                vector + keyword, Map.of());
    }

    @Test
    @DisplayName("命中充分时无风险信号")
    void sufficient() {
        RetrievalRelevanceEvaluator.RelevanceAssessment assessment =
                evaluator.assess(List.of(hit("c1", 0.8), hit("c2", 0.6)), List.of(), 0);

        assertThat(assessment.sufficient()).isTrue();
        assertThat(assessment.relevantCount()).isEqualTo(2);
        assertThat(assessment.signals()).isEmpty();
        assertThat(assessment.reason()).contains("检索充分");
    }

    @Test
    @DisplayName("无命中：NO_RESULT + 改写耗尽（第 2 轮）")
    void noResultAndRewriteExhausted() {
        RetrievalRelevanceEvaluator.RelevanceAssessment assessment =
                evaluator.assess(List.of(), List.of(), properties.getMaxRewriteRounds());

        assertThat(assessment.sufficient()).isFalse();
        assertThat(assessment.signals())
                .contains(RetrievalRelevanceEvaluator.NO_RESULT,
                        RetrievalRelevanceEvaluator.REWRITE_EXHAUSTED);
    }

    @Test
    @DisplayName("相关条数与最高分不足：两个信号同时命中，且未到上限时不报改写耗尽")
    void lowRelevantAndLowTopScore() {
        RetrievalRelevanceEvaluator.RelevanceAssessment assessment =
                evaluator.assess(List.of(hit("c1", 0.12)), List.of(), 0);

        assertThat(assessment.sufficient()).isFalse();
        assertThat(assessment.signals())
                .contains(RetrievalRelevanceEvaluator.LOW_RELEVANT_COUNT,
                        RetrievalRelevanceEvaluator.LOW_TOP_SCORE)
                .doesNotContain(RetrievalRelevanceEvaluator.REWRITE_EXHAUSTED);
        assertThat(assessment.reason()).contains("相关条数");
    }

    @Test
    @DisplayName("双路分歧：关键词路与向量路 topK 完全不重合")
    void pathDivergent() {
        List<HybridSearchService.HybridSearchResult> hybridHits = new ArrayList<>();
        hybridHits.add(hybrid("k1", 0.0, 3.0));
        hybridHits.add(hybrid("k2", 0.0, 2.0));
        hybridHits.add(hybrid("v1", 0.9, 0.0));
        hybridHits.add(hybrid("v2", 0.8, 0.0));

        RetrievalRelevanceEvaluator.RelevanceAssessment assessment =
                evaluator.assess(List.of(hit("c1", 0.9)), hybridHits, 0);

        assertThat(assessment.signals()).contains(RetrievalRelevanceEvaluator.PATH_DIVERGENT);
        assertThat(assessment.sufficient()).isTrue();
    }

    @Test
    @DisplayName("两路结果一致时不报分歧")
    void pathAgree() {
        List<HybridSearchService.HybridSearchResult> hybridHits = List.of(
                hybrid("c1", 0.9, 3.0), hybrid("c2", 0.8, 2.0));

        RetrievalRelevanceEvaluator.RelevanceAssessment assessment =
                evaluator.assess(List.of(hit("c1", 0.9)), hybridHits, 0);

        assertThat(assessment.signals()).doesNotContain(RetrievalRelevanceEvaluator.PATH_DIVERGENT);
    }
}
