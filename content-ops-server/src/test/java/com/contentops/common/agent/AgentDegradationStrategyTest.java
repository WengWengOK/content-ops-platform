package com.contentops.common.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentDegradationStrategy} 单元测试 — 三级降级策略。
 *
 * <p>覆盖面试考点（字节必考）：
 * <ul>
 *   <li>Level 1: 失败 1-2 次 → 重试 + 指数退避</li>
 *   <li>Level 2: 失败 3 次 → 切换备用模型</li>
 *   <li>Level 3: 失败 4 次 → 模板兜底 + cacheable=false</li>
 *   <li>Level 4: 失败 5+ 次 → 死信队列 + 人工接管</li>
 * </ul>
 */
@DisplayName("Agent 三级降级策略测试")
class AgentDegradationStrategyTest {

    @Nested
    @DisplayName("Level 1: 重试 + 指数退避")
    class Level1Retry {

        @Test
        @DisplayName("第 1 次失败应返回 Level 1 重试")
        void firstFailure_shouldReturnRetry() {
            AgentDegradationStrategy strategy = new AgentDegradationStrategy();

            AgentDegradationStrategy.DegradationDecision decision = strategy.onFailure(
                    "task-1", "topic-planning", "LLM timeout",
                    AgentDegradationStrategy.FailureReason.LLM_TIMEOUT, null);

            assertEquals(1, decision.level(), "应为 Level 1");
            assertEquals(AgentDegradationStrategy.DegradationAction.RETRY, decision.action());
            assertTrue(decision.retryDelayMs() > 0, "应有退避延迟");
            assertTrue(decision.cacheable(), "Level 1 结果可缓存");
        }

        @Test
        @DisplayName("第 2 次失败退避时间应大于第 1 次（指数递增）")
        void secondFailure_backoffShouldIncrease() {
            AgentDegradationStrategy strategy = new AgentDegradationStrategy();

            AgentDegradationStrategy.DegradationDecision d1 = strategy.onFailure(
                    "task-2", "content-creation", "error",
                    AgentDegradationStrategy.FailureReason.LLM_ERROR, null);
            AgentDegradationStrategy.DegradationDecision d2 = strategy.onFailure(
                    "task-2", "content-creation", "error",
                    AgentDegradationStrategy.FailureReason.LLM_ERROR, null);

            assertTrue(d2.retryDelayMs() > d1.retryDelayMs(),
                    "第 2 次退避应大于第 1 次: " + d2.retryDelayMs() + " > " + d1.retryDelayMs());
        }
    }

    @Nested
    @DisplayName("Level 2: 切换备用模型")
    class Level2SwitchModel {

        @Test
        @DisplayName("第 3 次失败应返回 Level 2 切换模型")
        void thirdFailure_shouldSwitchModel() {
            AgentDegradationStrategy strategy = new AgentDegradationStrategy();
            String taskId = "task-switch";

            strategy.onFailure(taskId, "topic-planning", "e",
                    AgentDegradationStrategy.FailureReason.LLM_ERROR, null);
            strategy.onFailure(taskId, "topic-planning", "e",
                    AgentDegradationStrategy.FailureReason.LLM_ERROR, null);
            AgentDegradationStrategy.DegradationDecision d3 = strategy.onFailure(
                    taskId, "topic-planning", "e",
                    AgentDegradationStrategy.FailureReason.LLM_ERROR, null);

            assertEquals(2, d3.level(), "应为 Level 2");
            assertTrue(d3.shouldSwitchModel(), "应建议切换模型");
            assertFalse(d3.cacheable(), "Level 2 结果不应缓存");
            assertTrue(strategy.isModelSwitched(taskId), "应标记已切换模型");
        }
    }

    @Nested
    @DisplayName("Level 3: 模板兜底")
    class Level3TemplateFallback {

        @Test
        @DisplayName("第 4 次失败应返回 Level 3 模板兜底")
        void fourthFailure_shouldReturnTemplate() {
            AgentDegradationStrategy strategy = new AgentDegradationStrategy();
            String taskId = "task-template";

            for (int i = 0; i < 3; i++) {
                strategy.onFailure(taskId, "topic-planning", "e",
                        AgentDegradationStrategy.FailureReason.LLM_ERROR, null);
            }
            AgentDegradationStrategy.DegradationDecision d4 = strategy.onFailure(
                    taskId, "topic-planning", "e",
                    AgentDegradationStrategy.FailureReason.LLM_ERROR, null);

            assertEquals(3, d4.level(), "应为 Level 3");
            assertEquals(AgentDegradationStrategy.DegradationAction.TEMPLATE_FALLBACK, d4.action());
            assertNotNull(d4.fallbackTemplate(), "应返回模板兜底文案");
            assertFalse(d4.cacheable(), "cacheable 必须为 false");
            assertTrue(d4.fallbackTemplate().contains("降级"), "模板应包含降级说明");
        }

        @Test
        @DisplayName("不同阶段应返回不同的模板兜底文案")
        void differentStages_shouldReturnDifferentTemplates() {
            AgentDegradationStrategy strategy = new AgentDegradationStrategy();

            // topic-planning 阶段：4 次失败后第 4 次（failureCount=4）返回 Level 3 模板兜底
            String topicTask = "task-topic";
            for (int i = 0; i < 3; i++) {
                strategy.onFailure(topicTask, "topic-planning", "e",
                        AgentDegradationStrategy.FailureReason.LLM_ERROR, null);
            }
            AgentDegradationStrategy.DegradationDecision topicDecision =
                    strategy.onFailure(topicTask, "topic-planning", "e",
                            AgentDegradationStrategy.FailureReason.LLM_ERROR, null);
            assertEquals(3, topicDecision.level(), "应为 Level 3 模板兜底");
            assertNotNull(topicDecision.fallbackTemplate(), "应返回模板兜底文案");
            assertTrue(topicDecision.fallbackTemplate().contains("选题"), "选题阶段应包含选题关键词");

            // content-creation 阶段
            String contentTask = "task-content";
            for (int i = 0; i < 3; i++) {
                strategy.onFailure(contentTask, "content-creation", "e",
                        AgentDegradationStrategy.FailureReason.LLM_ERROR, null);
            }
            AgentDegradationStrategy.DegradationDecision contentDecision =
                    strategy.onFailure(contentTask, "content-creation", "e",
                            AgentDegradationStrategy.FailureReason.LLM_ERROR, null);
            assertEquals(3, contentDecision.level(), "应为 Level 3 模板兜底");
            assertNotNull(contentDecision.fallbackTemplate(), "应返回模板兜底文案");
            assertTrue(contentDecision.fallbackTemplate().contains("内容初稿"), "内容阶段应包含内容初稿关键词");
        }
    }

    @Nested
    @DisplayName("Level 4: 死信队列")
    class Level4DeadLetter {

        @Test
        @DisplayName("第 5 次失败应写入死信队列")
        void fifthFailure_shouldWriteToDeadLetter() {
            AgentDegradationStrategy strategy = new AgentDegradationStrategy();
            String taskId = "task-deadletter";

            for (int i = 0; i < 4; i++) {
                strategy.onFailure(taskId, "image-design", "e",
                        AgentDegradationStrategy.FailureReason.TOOL_FAILURE, null);
            }
            AgentDegradationStrategy.DegradationDecision d5 = strategy.onFailure(
                    taskId, "image-design", "e",
                    AgentDegradationStrategy.FailureReason.TOOL_FAILURE,
                    java.util.Map.of("prompt", "generate image"));

            assertEquals(4, d5.level(), "应为 Level 4");
            assertEquals(AgentDegradationStrategy.DegradationAction.DEAD_LETTER, d5.action());
            assertEquals(1, strategy.getDeadLetterQueueSize(), "死信队列应有 1 条记录");

            AgentDegradationStrategy.DeadLetterEntry entry = strategy.pollDeadLetter();
            assertNotNull(entry, "应能取出死信条目");
            assertEquals(taskId, entry.taskId());
            assertEquals("image-design", entry.agentStage());
            assertEquals(AgentDegradationStrategy.FailureReason.TOOL_FAILURE, entry.reason());
        }
    }

    @Test
    @DisplayName("成功后应清除失败上下文")
    void onSuccess_shouldClearFailureContext() {
        AgentDegradationStrategy strategy = new AgentDegradationStrategy();
        String taskId = "task-recovery";

        strategy.onFailure(taskId, "topic-planning", "e",
                AgentDegradationStrategy.FailureReason.LLM_ERROR, null);
        assertEquals(1, strategy.getFailureCount(taskId));

        strategy.onSuccess(taskId);
        assertEquals(0, strategy.getFailureCount(taskId), "成功后失败计数应清零");
    }
}
