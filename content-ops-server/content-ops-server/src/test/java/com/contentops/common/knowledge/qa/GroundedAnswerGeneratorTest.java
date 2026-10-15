package com.contentops.common.knowledge.qa;

import com.contentops.common.rag.AdvancedRagService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接地答案生成测试：模型拒答识别 + 模型不可用时的抽取式兜底。
 */
class GroundedAnswerGeneratorTest {

    private ChatModel chatModel;
    private GroundedAnswerGenerator generator;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        generator = new GroundedAnswerGenerator(chatModel, new QaProperties());
    }

    private AdvancedRagService.RetrievalResult hit() {
        return new AdvancedRagService.RetrievalResult("c1", "退款在 3 个工作日内原路退回。", 0.9, 0.9,
                Map.of("title", "售后手册"), "advanced-rag");
    }

    @Test
    @DisplayName("识别模型主动拒答（知识库没有答案）")
    void detectsRefusal() {
        assertThat(generator.looksLikeRefusal("知识库中没有找到关于导出报表的相关内容，建议转人工咨询。")).isTrue();
        assertThat(generator.looksLikeRefusal("退款在 3 个工作日内原路退回 [1]。")).isFalse();
        assertThat(generator.looksLikeRefusal("")).isTrue();
    }

    @Test
    @DisplayName("模型不可用时抽取式兜底：直接引用最高分证据且引用编号合法")
    void fallsBackToExtractive() {
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("401 Unauthorized"));

        GroundedAnswerGenerator.GeneratedAnswer answer = generator.generate("退款要多久", List.of(hit()));

        assertThat(answer.source()).isEqualTo("template");
        assertThat(answer.answer()).contains("退款在 3 个工作日内原路退回").contains("[1]").contains("售后手册");
        assertThat(new CitationGuard().check(answer.answer(), 1).valid()).isTrue();
    }

    @Test
    @DisplayName("没有证据时直接走拒答话术")
    void refusesWithoutEvidence() {
        GroundedAnswerGenerator.GeneratedAnswer answer = generator.generate("随便问问", List.of());

        assertThat(answer.source()).isEqualTo("refuse");
        assertThat(generator.looksLikeRefusal(answer.answer())).isTrue();
    }
}
