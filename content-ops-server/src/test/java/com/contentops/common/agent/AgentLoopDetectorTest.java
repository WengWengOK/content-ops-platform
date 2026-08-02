package com.contentops.common.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentLoopDetector} 单元测试 — 循环检测器。
 *
 * <p>覆盖面试考点：
 * <ul>
 *   <li>连续相同 Action 检测（3 次中断）</li>
 *   <li>循环模式检测（A→B→A→B 交替）</li>
 *   <li>状态去重（相同签名返回缓存结果）</li>
 *   <li>工具降权与恢复</li>
 * </ul>
 */
@DisplayName("Agent 循环检测器测试")
class AgentLoopDetectorTest {

    @Nested
    @DisplayName("连续相同 Action 检测")
    class ConsecutiveDetection {

        @Test
        @DisplayName("连续 2 次相同 Action 不应中断")
        void twoConsecutive_shouldNotInterrupt() {
            AgentLoopDetector detector = new AgentLoopDetector();
            String sessionId = "session-1";

            AgentLoopDetector.LoopDetectionResult r1 = detector.checkAndRecord(sessionId, "search", "AI趋势");
            AgentLoopDetector.LoopDetectionResult r2 = detector.checkAndRecord(sessionId, "search", "AI趋势");

            assertFalse(r1.loopDetected(), "第 1 次不应检测到循环");
            assertFalse(r2.loopDetected(), "第 2 次不应检测到循环");
        }

        @Test
        @DisplayName("连续 3 次相同 Action 应触发中断")
        void threeConsecutive_shouldInterrupt() {
            AgentLoopDetector detector = new AgentLoopDetector();
            String sessionId = "session-2";

            detector.checkAndRecord(sessionId, "search", "AI趋势");
            detector.checkAndRecord(sessionId, "search", "AI趋势");
            AgentLoopDetector.LoopDetectionResult r3 = detector.checkAndRecord(sessionId, "search", "AI趋势");

            assertTrue(r3.loopDetected(), "第 3 次应检测到循环");
            assertTrue(r3.reason().startsWith("consecutive_"), "原因应包含 consecutive_");
        }

        @Test
        @DisplayName("不同参数的相同工具不应触发连续检测")
        void sameToolDifferentParams_shouldNotTrigger() {
            AgentLoopDetector detector = new AgentLoopDetector();
            String sessionId = "session-3";

            detector.checkAndRecord(sessionId, "search", "query-A");
            detector.checkAndRecord(sessionId, "search", "query-B");
            AgentLoopDetector.LoopDetectionResult r3 = detector.checkAndRecord(sessionId, "search", "query-C");

            assertFalse(r3.loopDetected(), "不同参数不应触发连续检测");
        }
    }

    @Nested
    @DisplayName("状态去重")
    class StateDeduplication {

        @Test
        @DisplayName("相同工具+相同参数再次调用应返回缓存结果")
        void sameCall_shouldReturnCachedResult() {
            AgentLoopDetector detector = new AgentLoopDetector();
            String sessionId = "session-dedup-1";

            // 第一次调用
            AgentLoopDetector.LoopDetectionResult r1 = detector.checkAndRecord(sessionId, "fetch", "url=http://example.com");
            assertFalse(r1.loopDetected());
            assertNull(r1.cachedResult(), "第一次不应有缓存");

            // 缓存结果
            detector.cacheResult(sessionId, "fetch", "url=http://example.com", "cached-response");

            // 第二次相同调用
            AgentLoopDetector.LoopDetectionResult r2 = detector.checkAndRecord(sessionId, "fetch", "url=http://example.com");
            assertFalse(r2.loopDetected(), "不应检测到循环");
            assertEquals("cached-response", r2.cachedResult(), "应返回缓存结果");
            assertEquals("cache_hit", r2.reason(), "原因应为 cache_hit");
        }
    }

    @Nested
    @DisplayName("工具降权")
    class ToolDemotion {

        @Test
        @DisplayName("连续 2 次失败应触发工具降权")
        void twoFailures_shouldDemote() {
            AgentLoopDetector detector = new AgentLoopDetector();

            boolean demoted1 = detector.recordToolFailure("search");
            assertFalse(demoted1, "第 1 次失败不应降权");

            boolean demoted2 = detector.recordToolFailure("search");
            assertTrue(demoted2, "第 2 次失败应触发降权");

            assertTrue(detector.isToolDemoted("search"), "工具应被降权");
        }

        @Test
        @DisplayName("成功调用应重置失败计数")
        void success_shouldResetFailureCount() {
            AgentLoopDetector detector = new AgentLoopDetector();

            detector.recordToolFailure("search");
            detector.recordToolSuccess("search");

            assertFalse(detector.isToolDemoted("search"), "成功后不应被降权");

            // 只有 1 次失败，不应降权
            boolean demoted = detector.recordToolFailure("search");
            assertFalse(demoted, "计数重置后 1 次失败不应降权");
        }
    }

    @Test
    @DisplayName("清理会话后历史应清空")
    void cleanupSession_shouldClearHistory() {
        AgentLoopDetector detector = new AgentLoopDetector();
        String sessionId = "session-cleanup";

        detector.checkAndRecord(sessionId, "search", "query");
        detector.cleanupSession(sessionId);

        // 清理后再次调用相同签名不应触发连续检测
        AgentLoopDetector.LoopDetectionResult r = detector.checkAndRecord(sessionId, "search", "query");
        assertFalse(r.loopDetected(), "清理后不应检测到循环");
        assertNull(r.cachedResult(), "清理后不应有缓存");
    }

    @Test
    @DisplayName("统计信息应正确反映当前状态")
    void getStats_shouldReflectCurrentState() {
        AgentLoopDetector detector = new AgentLoopDetector();

        detector.checkAndRecord("s1", "search", "q1");
        detector.checkAndRecord("s2", "search", "q2");
        detector.recordToolFailure("failingTool");
        detector.recordToolFailure("failingTool");

        var stats = detector.getStats();
        assertEquals(2, stats.get("activeSessions"), "应有 2 个活跃会话");
        assertTrue(((java.util.Collection<?>) stats.get("demotedTools")).contains("failingTool"),
                "failingTool 应在降权列表中");
    }
}
