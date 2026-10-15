package com.contentops.common.knowledge.qa;

import com.contentops.common.rag.AdvancedRagService;
import com.contentops.common.rag.HybridSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库问答编排测试：拒答、引用不实、生成幻觉、复检四条主线。
 */
class KbQaServiceTest {

    private QaProperties properties;
    private AdvancedRagService advancedRagService;
    private HybridSearchService hybridSearchService;
    private FaithfulnessLlmJudge llmJudge;
    private GroundedAnswerGenerator generator;
    private QaRepository qaRepository;
    private KbQaService service;
    /** 拒答识别委托给真实实现（generator 本身是 mock） */
    private GroundedAnswerGenerator refusalDetector;

    @BeforeEach
    void setUp() {
        properties = new QaProperties();
        advancedRagService = mock(AdvancedRagService.class);
        hybridSearchService = mock(HybridSearchService.class);
        llmJudge = mock(FaithfulnessLlmJudge.class);
        generator = mock(GroundedAnswerGenerator.class);
        qaRepository = mock(QaRepository.class);
        refusalDetector = new GroundedAnswerGenerator(mock(dev.langchain4j.model.chat.ChatModel.class), properties);

        when(llmJudge.reJudge(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(generator.looksLikeRefusal(anyString()))
                .thenAnswer(invocation -> refusalDetector.looksLikeRefusal(invocation.getArgument(0)));
        when(hybridSearchService.search(anyString(), anyInt(), any())).thenReturn(List.of());
        when(advancedRagService.rewriteQuery(anyString()))
                .thenAnswer(invocation -> List.of((String) invocation.getArgument(0)));

        service = new KbQaService(properties, advancedRagService, hybridSearchService,
                new RetrievalRelevanceEvaluator(properties), new CitationGuard(),
                new AnswerFaithfulnessChecker(properties), llmJudge, generator, qaRepository,
                new ObjectMapper());
    }

    private AdvancedRagService.RetrievalResult hit(String chunkId, double score, String content) {
        return new AdvancedRagService.RetrievalResult(chunkId, content, score, score,
                Map.of("title", "售后手册", "docId", "doc-1"), "advanced-rag");
    }

    private void retrieve(AdvancedRagService.RetrievalResult... hits) {
        when(advancedRagService.retrieveAndRerank(anyString(), any(), anyInt()))
                .thenReturn(List.of(hits));
    }

    private void generate(String answer, String source) {
        when(generator.generate(anyString(), any()))
                .thenReturn(new GroundedAnswerGenerator.GeneratedAnswer(answer, source));
    }

    @Test
    @DisplayName("检索充分 + 引用合法 + 内容一致：无风险，正常落库")
    void healthyAnswer() {
        retrieve(hit("c1", 0.9, "退款政策：支持 7 天无理由退货，需商品不影响二次销售"));
        generate("退款政策是支持 7 天无理由退货，需商品不影响二次销售 [1]。", "llm");

        QaRecord record = service.ask("退款政策是什么", "u1", 5, null);

        assertThat(record.isRefused()).isFalse();
        assertThat(record.getSource()).isEqualTo("llm");
        assertThat(record.getRiskLevel()).isEqualTo("NONE");
        assertThat(record.getReviewState()).isEqualTo("NONE");
        assertThat(record.getCitations()).contains("c1").contains("售后手册");
        assertThat(record.getRetrieval()).contains("\"sufficient\":true");
        verify(qaRepository).insert(any(QaRecord.class));
    }

    @Test
    @DisplayName("检索无命中：拒答 + 检索侧风险信号 + 不改写空转")
    void refuseWhenInsufficient() {
        retrieve();

        QaRecord record = service.ask("知识库里没有的问题", "u1", 5, null);

        assertThat(record.isRefused()).isTrue();
        assertThat(record.getSource()).isEqualTo("refuse");
        assertThat(record.getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(record.getReviewState()).isEqualTo("SAMPLED");
        assertThat(record.getRetrieval()).contains("NO_RESULT").contains("REWRITE_EXHAUSTED");
        verify(generator, never()).generate(anyString(), any());
    }

    @Test
    @DisplayName("检索不充分但关闭拒答：照常生成，仍打检索风险标记")
    void generateWhenRefusalDisabled() {
        properties.setRefuseOnInsufficient(false);
        retrieve(hit("c1", 0.1, "无关内容"));
        generate("这部分我帮你确认一下 [1]。", "llm");

        QaRecord record = service.ask("说明一下", "u1", 5, null);

        assertThat(record.isRefused()).isFalse();
        assertThat(record.getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(record.getRetrieval()).contains("LOW_TOP_SCORE");
    }

    @Test
    @DisplayName("生成幻觉（编造步骤）：HIGH + 进人工复核清单")
    void hallucinatedAnswerIsHighRisk() {
        retrieve(hit("c1", 0.9, "发货时间：下单后 48 小时内发货。"));
        generate("在设置页开启双因素认证，然后等待 99 天生效 [1]。", "llm");

        QaRecord record = service.ask("怎么发货", "u1", 5, null);

        assertThat(record.getRiskLevel()).isEqualTo("HIGH");
        assertThat(record.getReviewState()).isEqualTo("NEEDS_REVIEW");
        assertThat(record.getFaithfulness()).contains("UNSUPPORTED");
    }

    @Test
    @DisplayName("引用不实（引用不存在的章节）：HIGH")
    void invalidCitationIsHighRisk() {
        retrieve(hit("c1", 0.9, "发货时间：下单后 48 小时内发货。"));
        generate("根据资料，发货时间是 48 小时 [3]。", "llm");

        QaRecord record = service.ask("发货时间", "u1", 5, null);

        assertThat(record.getRiskLevel()).isEqualTo("HIGH");
        assertThat(record.getCitationCheck()).contains("\"valid\":false");
        assertThat(record.getReviewState()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    @DisplayName("模型主动拒答（检索说找得到）：按知识库盲区记 MODEL_REFUSED，不按引用不实判 HIGH")
    void modelRefusalIsKnowledgeGapNotHighRisk() {
        retrieve(hit("c1", 0.9, "发货时间：下单后 48 小时内发货。"));
        generate("知识库中没有找到关于导出报表的相关内容，建议转人工咨询。", "llm");

        QaRecord record = service.ask("如何导出报表", "u1", 5, null);

        assertThat(record.isRefused()).isTrue();
        assertThat(record.getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(record.getRetrieval()).contains("MODEL_REFUSED");
        assertThat(record.getFaithfulness()).contains("拒答");
    }

    @Test
    @DisplayName("复检：用当前检索结果重标已存答案，不改动答案文本")
    void recheckRemarksStoredAnswer() {
        QaRecord stored = QaRecord.builder()
                .qaId("qa-1").question("退款要多久").answer("需要提供身份证照片和开箱视频，审核通过后 99 个工作日退款 [1]")
                .refused(false).source("llm").createdAt(java.time.LocalDateTime.now())
                .build();
        when(qaRepository.findById("qa-1")).thenReturn(Optional.of(stored));
        retrieve(hit("c1", 0.9, "退款将在 3 个工作日内原路退回。"));

        QaRecord updated = service.recheck("qa-1");

        assertThat(updated.getAnswer()).isEqualTo(stored.getAnswer());
        assertThat(updated.getRiskLevel()).isEqualTo("HIGH");
        assertThat(updated.getReviewState()).isEqualTo("NEEDS_REVIEW");
        verify(qaRepository).updateMarkers(eq("qa-1"), any(), any(), any(), any(),
                eq("HIGH"), eq("NEEDS_REVIEW"));
    }
}
