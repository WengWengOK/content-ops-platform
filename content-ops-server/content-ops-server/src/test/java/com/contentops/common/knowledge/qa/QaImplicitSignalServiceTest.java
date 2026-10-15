package com.contentops.common.knowledge.qa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户隐式信号归因测试（第 4 层）。
 */
class QaImplicitSignalServiceTest {

    private QaRepository qaRepository;
    private QaSignalRepository signalRepository;
    private QaImplicitSignalService service;

    @BeforeEach
    void setUp() {
        qaRepository = mock(QaRepository.class);
        signalRepository = mock(QaSignalRepository.class);
        service = new QaImplicitSignalService(new QaProperties(), qaRepository, signalRepository);
    }

    private QaRecord record(String qaId, String question) {
        return QaRecord.builder().qaId(qaId).question(question).answer("旧答案")
                .createdAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("追问出现质疑词：DOUBT_REPHRASE + 上一条问答被标疑似错误")
    void doubtRephrase() {
        when(qaRepository.findRecent(any(), any(), anyInt()))
                .thenReturn(List.of(record("qa-prev", "退款怎么申请")));

        List<QaSignal> signals = service.detectOnNewQuestion("qa-new", "不对吧，还是找不到退款入口", "u1");

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).getSignalType()).isEqualTo(QaImplicitSignalService.DOUBT_REPHRASE);
        assertThat(signals.get(0).getRelatedQaId()).isEqualTo("qa-prev");
        verify(qaRepository).markSuspected(eq("qa-prev"), contains("追问质疑"));
        verify(signalRepository).insert(any(QaSignal.class));
    }

    @Test
    @DisplayName("时间窗内重复提问：REPEAT_QUESTION + 上一条被标疑似错误")
    void repeatQuestion() {
        when(qaRepository.findRecent(any(), any(), anyInt()))
                .thenReturn(List.of(record("qa-prev", "退款怎么申请")));

        List<QaSignal> signals = service.detectOnNewQuestion("qa-new", "退款怎么申请流程", "u1");

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).getSignalType()).isEqualTo(QaImplicitSignalService.REPEAT_QUESTION);
        verify(qaRepository).markSuspected(eq("qa-prev"), contains("重复提问"));
    }

    @Test
    @DisplayName("无关新问题不产生信号")
    void noSignalForUnrelatedQuestion() {
        when(qaRepository.findRecent(any(), any(), anyInt()))
                .thenReturn(List.of(record("qa-prev", "退款怎么申请")));

        List<QaSignal> signals = service.detectOnNewQuestion("qa-new", "今天天气怎么样", "u1");

        assertThat(signals).isEmpty();
        verify(qaRepository, never()).markSuspected(anyString(), anyString());
    }

    @Test
    @DisplayName("引用秒退：停留过短才产生 CITATION_BOUNCE")
    void citationBounce() {
        QaSignal bounce = service.citationClick("qa-1", "u1", 500);
        assertThat(bounce).isNotNull();
        assertThat(bounce.getSignalType()).isEqualTo(QaImplicitSignalService.CITATION_BOUNCE);
        assertThat(service.citationClick("qa-1", "u1", 8000)).isNull();
    }

    @Test
    @DisplayName("转人工确认答案错误：高置信度样本（0.95）")
    void handoffConfirmed() {
        assertThat(service.handoff("qa-1", "u1", false, null)).isNull();

        QaSignal signal = service.handoff("qa-1", "u1", true, "实施反馈：步骤写错了");

        assertThat(signal.getSignalType()).isEqualTo(QaImplicitSignalService.HANDOFF_CONFIRMED);
        assertThat(signal.getConfidence()).isEqualTo(0.95);
        verify(qaRepository).markSuspected(eq("qa-1"), contains("HANDOFF_CONFIRMED"));
    }

    @Test
    @DisplayName("显式点踩：记为错误样本")
    void explicitDislike() {
        QaSignal signal = service.explicitDislike("qa-1", "u1", "答非所问");

        assertThat(signal.getSignalType()).isEqualTo(QaImplicitSignalService.EXPLICIT_DISLIKE);
        verify(qaRepository).markSuspected(eq("qa-1"), contains("答非所问"));
    }
}
