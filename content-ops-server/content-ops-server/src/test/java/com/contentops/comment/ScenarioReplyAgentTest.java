package com.contentops.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 场景客服回复 Agent 测试：场景配置驱动、模型失败走模板兜底、校验标记随草稿返回。
 */
class ScenarioReplyAgentTest {

    private ChatModel chatModel;
    private ScenarioReplyAgent agent;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        agent = new ScenarioReplyAgent(chatModel, new ReplyComplianceChecker(), new ObjectMapper());
    }

    private ReplyAgentProfile ecommerce() {
        return ReplyAgentProfile.builder()
                .profileId("reply-agent-ecommerce-default")
                .name("电商带货客服")
                .scenario("ECOMMERCE")
                .tone("热情但不夸张，口语化")
                .systemPrompt("你是一位电商带货客服。")
                .productFacts("48 小时内发货，支持 7 天无理由退换")
                .forbiddenClaims("绝对正品,保证见效")
                .escalateKeywords("退款,投诉,发票")
                .replyMaxChars(120)
                .allowLinks(false)
                .enabled(true)
                .defaultProfile(true)
                .build();
    }

    @Nested
    @DisplayName("模型可用")
    class ModelAvailable {

        @Test
        @DisplayName("返回模型草稿，来源标记 llm，且提示词注入场景事实与禁止承诺")
        void returnsLlmDraft() {
            when(chatModel.chat(anyString())).thenReturn("  亲，48 小时内发货的哦～  ");

            ScenarioReplyAgent.DraftResult result =
                    agent.draft(ecommerce(), "多久发货", List.of(), "多久发货", null);

            assertThat(result.reply()).isEqualTo("亲，48 小时内发货的哦～");
            assertThat(result.source()).isEqualTo("llm");
            assertThat(result.profileId()).isEqualTo("reply-agent-ecommerce-default");
            assertThat(result.scenario()).isEqualTo("ECOMMERCE");
            assertThat(result.reviewState()).isEqualTo("NONE");
            assertThat(result.confidence()).isEqualTo(0.95);
            String prompt = capturedPrompt();
            assertThat(prompt)
                    .contains("48 小时内发货")
                    .contains("绝对正品")
                    .contains("退款,投诉,发票")
                    .contains("不超过 120 字")
                    .contains("多久发货");
        }

        @Test
        @DisplayName("模型给出合规风险话术：原样保留草稿，只附加标记与复核状态")
        void keepsDraftButFlagsIt() {
            String risky = "我们是绝对正品，而且保证见效";
            when(chatModel.chat(anyString())).thenReturn(risky);

            ScenarioReplyAgent.DraftResult result =
                    agent.draft(ecommerce(), "是正品吗", List.of(), "是正品吗", null);

            assertThat(result.reply()).isEqualTo(risky);
            assertThat(result.flags()).isNotEmpty();
            assertThat(result.reviewState()).isEqualTo("NEEDS_REVIEW");
            assertThat(result.confidence()).isLessThan(0.95);
        }

        @Test
        @DisplayName("对话历史与产品上下文都会进入提示词")
        void historyAndContextInPrompt() {
            when(chatModel.chat(anyString())).thenReturn("好的");

            agent.draft(ecommerce(), "能便宜点吗", List.of(Map.of("role", "user", "content", "上一条")),
                    "能便宜点吗", "当前商品：某款保温杯");

            assertThat(capturedPrompt()).contains("上一条").contains("某款保温杯");
        }
    }

    private String capturedPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).chat(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("模型不可用（降级不中断）")
    class ModelUnavailable {

        @Test
        @DisplayName("调用异常时走场景模板兜底，来源标记 template")
        void fallsBackToTemplateOnException() {
            when(chatModel.chat(anyString())).thenThrow(new RuntimeException("401 Unauthorized"));

            ScenarioReplyAgent.DraftResult result =
                    agent.draft(ecommerce(), "什么时候发货", List.of(), "什么时候发货", null);

            assertThat(result.source()).isEqualTo("template");
            assertThat(result.reply()).isNotBlank();
            assertThat(result.reply()).contains("客服");
            assertThat(result.profileId()).isEqualTo("reply-agent-ecommerce-default");
        }

        @Test
        @DisplayName("模型返回空白时也走模板兜底")
        void fallsBackToTemplateOnBlank() {
            when(chatModel.chat(anyString())).thenReturn("   ");

            ScenarioReplyAgent.DraftResult result =
                    agent.draft(ecommerce(), "在吗", List.of(), "在吗", null);

            assertThat(result.source()).isEqualTo("template");
            assertThat(result.reply()).isNotBlank();
        }

        @Test
        @DisplayName("课程场景模板兜底也带场景口吻")
        void courseTemplate() {
            when(chatModel.chat(anyString())).thenThrow(new RuntimeException("timeout"));
            ReplyAgentProfile course = ReplyAgentProfile.builder()
                    .profileId("reply-agent-course-template").scenario("COURSE").name("课程客服")
                    .replyMaxChars(120).build();

            ScenarioReplyAgent.DraftResult result = agent.draft(course, "这个课适合我吗", List.of(), "适合我吗", null);

            assertThat(result.source()).isEqualTo("template");
            assertThat(result.reply()).contains("课程");
            assertThat(result.scenario()).isEqualTo("COURSE");
        }
    }

    @Nested
    @DisplayName("复检")
    class Recheck {

        @Test
        @DisplayName("人工修改后再复检：只重新产出标记，不改动文本")
        void recheckReproducesFlags() {
            String edited = "你好，退款我转人工帮你处理";

            ReplyComplianceChecker.CheckResult result = agent.recheck(edited, ecommerce(), "我要退款", null);

            assertThat(result.flags()).noneMatch(f -> "ESCALATION_MISSING".equals(f.code()));
            assertThat(result.reviewState()).isEqualTo("NONE");
        }
    }
}
