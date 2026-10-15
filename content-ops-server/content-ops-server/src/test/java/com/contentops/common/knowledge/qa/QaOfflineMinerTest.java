package com.contentops.common.knowledge.qa;

import com.contentops.common.observability.LlmEvalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 离线聚类与回归集提升测试（第 5 层）。
 */
class QaOfflineMinerTest {

    private QaRepository qaRepository;
    private QaSignalRepository signalRepository;
    private LlmEvalRepository llmEvalRepository;
    private QaOfflineMiner miner;

    @BeforeEach
    void setUp() {
        qaRepository = mock(QaRepository.class);
        signalRepository = mock(QaSignalRepository.class);
        llmEvalRepository = mock(LlmEvalRepository.class);
        miner = new QaOfflineMiner(qaRepository, signalRepository, llmEvalRepository);
    }

    private QaRecord refused(String qaId, String question) {
        return QaRecord.builder().qaId(qaId).question(question).refused(true)
                .answer("拒答").riskLevel("MEDIUM").createdAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("拒答问题聚类：相似问题归为一簇，按样本数降序")
    void clustersSimilarRefusedQuestions() {
        when(qaRepository.list(any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(List.of(
                        refused("qa-1", "退款怎么申请"),
                        refused("qa-2", "退款要怎么申请"),
                        refused("qa-3", "发票怎么开")));

        List<QaOfflineMiner.QaCluster> clusters = miner.cluster("u1", QaOfflineMiner.SCOPE_REFUSED, 100, null);

        assertThat(clusters).hasSize(2);
        assertThat(clusters.get(0).size()).isEqualTo(2);
        assertThat(clusters.get(0).sampleQuestions()).contains("退款怎么申请");
        assertThat(clusters.get(0).signals()).contains("REFUSED");
        assertThat(clusters.get(0).qaIds()).containsExactlyInAnyOrder("qa-1", "qa-2");
    }

    @Test
    @DisplayName("把错误样本提升进评测集：写入 llm_eval_case，stage=kb-qa")
    void promoteToEvalSet() {
        when(qaRepository.findById("qa-1")).thenReturn(Optional.of(refused("qa-1", "退款怎么申请")));
        when(qaRepository.findById("qa-2")).thenReturn(Optional.empty());

        Map<String, Object> result = miner.promote("u1", List.of("qa-1", "qa-2"));

        assertThat(result.get("requested")).isEqualTo(2);
        assertThat(result.get("promoted")).isEqualTo(1);
        assertThat(result.get("stage")).isEqualTo(QaOfflineMiner.EVAL_STAGE);
        verify(llmEvalRepository).insertCase(eq("kbqa-qa-1"), eq("kb-qa"), anyString(), anyString(),
                anyString(), any(Timestamp.class));
        verify(llmEvalRepository, times(1)).insertCase(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(Timestamp.class));
    }

    @Test
    @DisplayName("盲区总览返回样本数与簇数")
    void report() {
        when(qaRepository.list(any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(List.of(refused("qa-1", "退款怎么申请")));
        when(signalRepository.typeStats(any())).thenReturn(List.of(Map.of("signal_type", "REPEAT_QUESTION")));

        Map<String, Object> report = miner.report("u1", 100);

        assertThat(report).containsKeys("totalCount", "refusedCount", "suspectedCount",
                "refusedClusters", "highRiskClusters", "signalTypeStats");
    }
}
