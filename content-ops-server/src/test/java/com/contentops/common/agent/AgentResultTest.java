package com.contentops.common.agent;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentResult} 单元测试。
 *
 * <p>覆盖工厂方法（success / failure / timeout）、合并逻辑（merge / mergeAll）、
 * 质量阈值判断（meetsQuality）以及 token 便捷方法。测试不依赖 Spring 上下文与 LLM。
 */
@DisplayName("AgentResult 单元测试")
class AgentResultTest {

    private static final TokenUsage ZERO_USAGE = new TokenUsage(0, 0, 0);

    // ──────────────────────── 工厂方法 ────────────────────────

    @Nested
    @DisplayName("工厂方法")
    class FactoryMethods {

        @Test
        @DisplayName("success：应创建成功结果，success=true、output 已设置、errors 为空")
        void success_shouldCreateSuccessResult() {
            TokenUsage usage = new TokenUsage(10, 20, 30);
            AgentResult result = AgentResult.success("t-1", "调研完成", 1200L, usage, 85);

            assertEquals("t-1", result.taskId());
            assertTrue(result.success());
            assertEquals("调研完成", result.output());
            assertTrue(result.errors().isEmpty());
            assertEquals(1200L, result.executionTime());
            assertEquals(85, result.qualityScore());
            assertSame(usage, result.tokenUsage());
        }

        @Test
        @DisplayName("success（不带评分）：qualityScore 应为 SCORE_NOT_RATED（-1）")
        void success_withoutScore_shouldHaveNotRatedScore() {
            TokenUsage usage = new TokenUsage(1, 2, 3);
            AgentResult result = AgentResult.success("t-2", "无评分结果", 500L, usage);

            assertTrue(result.success());
            assertEquals(AgentResult.SCORE_NOT_RATED, result.qualityScore());
            assertEquals(-1, result.qualityScore());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("failure：应创建失败结果，success=false、errors 含错误信息、output 为 null")
        void failure_shouldCreateFailureResult() {
            AgentResult result = AgentResult.failure("t-3", "模型调用失败");

            assertFalse(result.success());
            assertNull(result.output());
            assertEquals(List.of("模型调用失败"), result.errors());
            assertEquals(0L, result.executionTime());
            assertEquals(AgentResult.SCORE_NOT_RATED, result.qualityScore());
        }

        @Test
        @DisplayName("failure（带执行耗时）：应保留传入的 executionTime")
        void failure_withExecutionTime_shouldPreserveTime() {
            AgentResult result = AgentResult.failure("t-4", "重试耗尽", 3500L);

            assertFalse(result.success());
            assertEquals(3500L, result.executionTime());
            assertEquals(List.of("重试耗尽"), result.errors());
            assertNull(result.output());
        }

        @Test
        @DisplayName("timeout：应创建超时降级结果，success=false、output 为部分输出、error 提示超时")
        void timeout_shouldCreateTimeoutResult() {
            AgentResult result = AgentResult.timeout("t-5", "部分输出内容", 10000L);

            assertFalse(result.success());
            assertEquals("部分输出内容", result.output());
            assertEquals(10000L, result.executionTime());
            assertFalse(result.errors().isEmpty());
            assertEquals(1, result.errors().size());
            assertTrue(result.errors().get(0).contains("超时"));
            assertEquals(AgentResult.SCORE_NOT_RATED, result.qualityScore());
        }
    }

    // ──────────────────────── merge 合并逻辑 ────────────────────────

    @Nested
    @DisplayName("merge 合并逻辑")
    class MergeLogic {

        @Test
        @DisplayName("合并两个成功结果：output 以分隔符拼接、success=true、token 累加")
        void merge_twoSuccess_shouldCombineOutputs() {
            TokenUsage usage1 = new TokenUsage(10, 20, 30);
            TokenUsage usage2 = new TokenUsage(5, 5, 10);
            AgentResult r1 = AgentResult.success("t-1", "调研结果", 1000L, usage1, 80);
            AgentResult r2 = AgentResult.success("t-1", "写作结果", 3000L, usage2, 90);

            AgentResult merged = r1.merge(r2);

            assertTrue(merged.success());
            assertEquals("调研结果" + "\n\n---\n\n" + "写作结果", merged.output());
            assertEquals(15, merged.inputTokens());
            assertEquals(25, merged.outputTokens());
            assertTrue(merged.errors().isEmpty());
        }

        @Test
        @DisplayName("合并成功与失败结果：success 应为 false")
        void merge_successAndFailure_shouldReturnFailed() {
            AgentResult ok = AgentResult.success("t-1", "ok", 100L, ZERO_USAGE, 80);
            AgentResult bad = AgentResult.failure("t-1", "boom");

            AgentResult merged = ok.merge(bad);

            assertFalse(merged.success());
            assertTrue(merged.errors().contains("boom"));
        }

        @Test
        @DisplayName("合并应取较大的 executionTime")
        void merge_shouldTakeMaxExecutionTime() {
            AgentResult r1 = AgentResult.success("t-1", "a", 500L, ZERO_USAGE, 70);
            AgentResult r2 = AgentResult.success("t-1", "b", 2000L, ZERO_USAGE, 70);

            AgentResult merged = r1.merge(r2);

            assertEquals(2000L, merged.executionTime());
        }

        @Test
        @DisplayName("合并应取较高的 qualityScore")
        void merge_shouldTakeMaxQualityScore() {
            AgentResult r1 = AgentResult.success("t-1", "a", 1L, ZERO_USAGE, 60);
            AgentResult r2 = AgentResult.success("t-1", "b", 1L, ZERO_USAGE, 95);

            AgentResult merged = r1.merge(r2);

            assertEquals(95, merged.qualityScore());
        }

        @Test
        @DisplayName("合并 null 应返回当前对象自身")
        void merge_nullOther_shouldReturnSelf() {
            AgentResult r = AgentResult.success("t-1", "solo", 1L, ZERO_USAGE, 50);

            AgentResult merged = r.merge(null);

            assertSame(r, merged);
        }
    }

    // ──────────────────────── mergeAll 多结果聚合 ────────────────────────

    @Nested
    @DisplayName("mergeAll 多结果聚合")
    class MergeAllLogic {

        @Test
        @DisplayName("合并多个结果应正确聚合输出、token、耗时与评分")
        void mergeAll_multipleResults_shouldAggregateCorrectly() {
            AgentResult r1 = AgentResult.success("agg", "第一段", 100L, new TokenUsage(10, 10, 20), 60);
            AgentResult r2 = AgentResult.success("agg", "第二段", 300L, new TokenUsage(20, 20, 40), 80);
            AgentResult r3 = AgentResult.success("agg", "第三段", 200L, new TokenUsage(30, 30, 60), 70);

            AgentResult merged = AgentResult.mergeAll("agg", List.of(r1, r2, r3));

            assertEquals("agg", merged.taskId());
            assertTrue(merged.success());
            assertTrue(merged.output().contains("第一段"));
            assertTrue(merged.output().contains("第二段"));
            assertTrue(merged.output().contains("第三段"));
            assertEquals(60, merged.inputTokens());    // 10 + 20 + 30
            assertEquals(60, merged.outputTokens());   // 10 + 20 + 30
            assertEquals(300L, merged.executionTime()); // Math.max
            assertEquals(80, merged.qualityScore());   // Math.max
        }

        @Test
        @DisplayName("空列表应返回一个空的成功结果")
        void mergeAll_emptyList_shouldReturnEmptySuccess() {
            AgentResult merged = AgentResult.mergeAll("agg", List.of());

            assertEquals("agg", merged.taskId());
            assertTrue(merged.success());
            assertEquals("", merged.output());
            assertTrue(merged.errors().isEmpty());
            assertEquals(0L, merged.executionTime());
            assertEquals(AgentResult.SCORE_NOT_RATED, merged.qualityScore());
        }
    }

    // ──────────────────────── meetsQuality 质量阈值 ────────────────────────

    @Nested
    @DisplayName("meetsQuality 质量阈值判断")
    class MeetsQualityLogic {

        @Test
        @DisplayName("评分高于阈值应返回 true")
        void meetsQuality_aboveThreshold_shouldReturnTrue() {
            AgentResult result = AgentResult.success("t-1", "ok", 1L, ZERO_USAGE, 85);

            assertTrue(result.meetsQuality(80));
            assertTrue(result.meetsQuality(85)); // 等于阈值也算达标
        }

        @Test
        @DisplayName("评分低于阈值应返回 false")
        void meetsQuality_belowThreshold_shouldReturnFalse() {
            AgentResult result = AgentResult.success("t-1", "ok", 1L, ZERO_USAGE, 70);

            assertFalse(result.meetsQuality(80));
        }

        @Test
        @DisplayName("未评分（SCORE_NOT_RATED）无论阈值如何都应返回 false")
        void meetsQuality_notRated_shouldReturnFalse() {
            AgentResult result = AgentResult.success("t-1", "ok", 1L, ZERO_USAGE); // 不带评分

            assertEquals(AgentResult.SCORE_NOT_RATED, result.qualityScore());
            assertFalse(result.meetsQuality(0));
            assertFalse(result.meetsQuality(60));
        }
    }

    // ──────────────────────── token 便捷方法 ────────────────────────

    @Nested
    @DisplayName("token 便捷方法")
    class TokenConvenienceMethods {

        @Test
        @DisplayName("inputTokens 应返回输入 token 数")
        void inputTokens_shouldReturnCorrectValue() {
            TokenUsage usage = new TokenUsage(100, 200, 300);
            AgentResult result = AgentResult.success("t-1", "ok", 1L, usage, 80);

            assertEquals(100, result.inputTokens());
        }

        @Test
        @DisplayName("totalTokens 在 totalTokenCount 为 null 时应返回 input + output")
        void totalTokens_shouldReturnInputPlusOutput() {
            // langchain4j 1.0.1 中 3 参构造器允许显式传入 null total，
            // 以此触发 totalTokens() 的回退分支（inputTokens + outputTokens）
            TokenUsage usage = new TokenUsage(100, 200, null);
            assertNull(usage.totalTokenCount());
            AgentResult result = AgentResult.success("t-1", "ok", 1L, usage, 80);

            assertEquals(300, result.totalTokens());
        }
    }
}
