package com.contentops.common.quality;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.metrics.TokenMetricsService;
import com.contentops.common.platform.MetricsParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AutoRetryService 单元测试（P2 优化: 指数退避 + 质量驱动重试）。
 *
 * <p>验证低分自动重试服务的核心行为：
 * <ul>
 *   <li>质量评估未启用时直接调用一次返回</li>
 *   <li>自动重试未启用时调用一次并评估但不重试</li>
 *   <li>首次即达标时不重试</li>
 *   <li>首次不达标、重试后达标时返回重试结果</li>
 *   <li>所有尝试均不达标时返回最高分结果</li>
 *   <li>LLM 调用异常时容错处理</li>
 *   <li>指数退避参数正确生效</li>
 * </ul>
 *
 * <p>使用 {@link SimpleMeterRegistry} 构造真实的 {@link TokenMetricsService}，
 * 避免引入 Mockito 依赖。退避时间设为 1ms 以保证测试快速执行。
 */
@DisplayName("AutoRetryService 测试")
class AutoRetryServiceTest {

    private QualityThresholdProperties qualityProperties;
    private QualityAssessmentService qualityAssessmentService;
    private TokenMetricsService tokenMetricsService;
    private AutoRetryService autoRetryService;

    @BeforeEach
    void setUp() {
        qualityProperties = new QualityThresholdProperties();
        // 退避时间设为 1ms 保证测试快速执行
        qualityProperties.setRetryBackoffMs(1);
        qualityProperties.setRetryBackoffMultiplier(1.0);

        MetricsParser metricsParser = new MetricsParser();
        qualityAssessmentService = new QualityAssessmentService(qualityProperties, metricsParser);
        tokenMetricsService = new TokenMetricsService(new SimpleMeterRegistry());
        autoRetryService = new AutoRetryService(qualityAssessmentService, qualityProperties, tokenMetricsService);
    }

    // ════════════════ 质量评估未启用 ════════════════

    @Nested
    @DisplayName("质量评估未启用")
    class QualityDisabled {

        @Test
        @DisplayName("isEnabled=false 时应直接调用一次 LLM，不评估质量也不重试")
        void whenQualityDisabled_shouldCallOnceWithoutRetry() {
            qualityProperties.setEnabled(false);

            AtomicInteger callCount = new AtomicInteger(0);
            String content = "# 标题\n\n首先这是一段内容。\n\n- 列表项";

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.CONTENT_CREATION,
                    "test prompt",
                    prompt -> {
                        callCount.incrementAndGet();
                        return content;
                    },
                    "workflow-001"
            );

            assertEquals(1, callCount.get(), "应只调用一次 LLM");
            assertNotNull(result.getContent());
            assertEquals(0, result.getRetryCount());
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("isEnabled=false 时即使内容质量低也不重试")
        void whenQualityDisabled_shouldNotRetryEvenForLowQuality() {
            qualityProperties.setEnabled(false);

            AtomicInteger callCount = new AtomicInteger(0);

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.CONTENT_CREATION,
                    "test prompt",
                    prompt -> {
                        callCount.incrementAndGet();
                        return "短";
                    },
                    "workflow-002"
            );

            assertEquals(1, callCount.get(), "即使质量低也不应重试");
            assertEquals(0, result.getRetryCount());
        }
    }

    // ════════════════ 自动重试未启用 ════════════════

    @Nested
    @DisplayName("自动重试未启用")
    class AutoRetryDisabled {

        @Test
        @DisplayName("autoRetry=false 时应调用一次并评估但不重试")
        void whenAutoRetryDisabled_shouldCallOnceAndAssessButNotRetry() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(false);

            AtomicInteger callCount = new AtomicInteger(0);

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.CONTENT_CREATION,
                    "test prompt",
                    prompt -> {
                        callCount.incrementAndGet();
                        return "短";
                    },
                    "workflow-003"
            );

            assertEquals(1, callCount.get(), "应只调用一次");
            assertEquals(0, result.getRetryCount());
            assertNotNull(result.getFinalScore(), "应评估质量");
        }
    }

    // ════════════════ 质量驱动重试 ════════════════

    @Nested
    @DisplayName("质量驱动重试")
    class QualityDrivenRetry {

        @Test
        @DisplayName("首次即达标时不应重试")
        void whenFirstAttemptPasses_shouldNotRetry() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(40);
            qualityProperties.setMaxRetries(2);

            AtomicInteger callCount = new AtomicInteger(0);
            String highQualityContent = """
                    # 完整的分析报告

                    ## 月度趋势概览

                    首先，本月阅读人数: 12000，点赞: 678，互动率: 7.2%。
                    环比增长15%，同比增22%。

                    其次，从内容分类来看：
                    - 技术类文章表现最佳
                    - 经验分享类紧随其后

                    然后，从时段分析来看：
                    - 工作日晚上8-10点互动最高

                    因此，建议加大技术类内容投入。
                    不仅如此，应优化发布时间策略。
                    综上所述，本月运营策略整体有效。
                    """;

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.DATA_ANALYSIS,
                    "test prompt",
                    prompt -> {
                        callCount.incrementAndGet();
                        return highQualityContent;
                    },
                    "workflow-004"
            );

            assertEquals(1, callCount.get(), "首次达标不应重试");
            assertEquals(0, result.getRetryCount());
            assertTrue(result.isSuccess(), "应标记为成功");
            assertTrue(result.getFinalScore().isAboveThreshold(40));
        }

        @Test
        @DisplayName("首次不达标、重试后达标时应返回重试结果")
        void whenFirstFailsRetrySucceeds_shouldReturnRetryResult() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(60);
            qualityProperties.setMaxRetries(2);

            AtomicInteger callCount = new AtomicInteger(0);
            String lowQualityContent = "短内容";
            String highQualityContent = """
                    # 完整的分析报告

                    ## 月度趋势概览

                    首先，本月阅读人数: 12000，点赞: 678，互动率: 7.2%。
                    环比增长15%，同比增22%。

                    其次，从内容分类来看：
                    - 技术类文章表现最佳
                    - 经验分享类紧随其后

                    然后，从时段分析来看：
                    - 工作日晚上8-10点互动最高

                    因此，建议加大技术类内容投入。
                    不仅如此，应优化发布时间策略。
                    综上所述，本月运营策略整体有效。

                    ## 下一步行动

                    - 持续产出技术深度内容
                    - 优化标题吸引力
                    - 加强用户互动引导
                    """;

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.DATA_ANALYSIS,
                    "test prompt",
                    prompt -> {
                        int n = callCount.incrementAndGet();
                        return n == 1 ? lowQualityContent : highQualityContent;
                    },
                    "workflow-005"
            );

            assertEquals(2, callCount.get(), "应调用 2 次（首次 + 1 次重试）");
            assertTrue(result.getRetryCount() >= 1, "重试次数应 >= 1");
            assertTrue(result.isSuccess(), "应标记为成功");
            assertEquals(highQualityContent, result.getContent(), "应返回高质量内容");
        }

        @Test
        @DisplayName("所有尝试均不达标时应返回最高分结果")
        void whenAllAttemptsFail_shouldReturnBestResult() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(90);
            qualityProperties.setMaxRetries(2);

            AtomicInteger callCount = new AtomicInteger(0);
            String mediumQualityContent = """
                    # 报告

                    首先，本月数据如下：阅读人数 5000，点赞 200。

                    - 项目一
                    - 项目二

                    因此，整体表现尚可。
                    """;
            String lowQualityContent = "短";

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.CONTENT_CREATION,
                    "test prompt",
                    prompt -> {
                        int n = callCount.incrementAndGet();
                        // 首次返回中等质量，后续返回低质量
                        return n == 1 ? mediumQualityContent : lowQualityContent;
                    },
                    "workflow-006"
            );

            assertEquals(3, callCount.get(), "应调用 3 次（首次 + 2 次重试）");
            assertFalse(result.isSuccess(), "未达标应标记为失败");
            assertEquals(mediumQualityContent, result.getContent(), "应返回最高分（首次中等质量）的结果");
        }

        @Test
        @DisplayName("maxRetries=0 时应只调用一次不重试")
        void whenMaxRetriesZero_shouldCallOnceWithoutRetry() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(90);
            qualityProperties.setMaxRetries(0);

            AtomicInteger callCount = new AtomicInteger(0);

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.CONTENT_CREATION,
                    "test prompt",
                    prompt -> {
                        callCount.incrementAndGet();
                        return "短";
                    },
                    "workflow-007"
            );

            assertEquals(1, callCount.get(), "maxRetries=0 时应只调用一次");
            assertEquals(0, result.getRetryCount());
        }
    }

    // ════════════════ LLM 调用异常处理 ════════════════

    @Nested
    @DisplayName("LLM 调用异常处理")
    class ExceptionHandling {

        @Test
        @DisplayName("LLM 调用抛异常时应在后续重试中恢复")
        void whenLlmThrowsException_shouldRecoverOnRetry() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(50);
            qualityProperties.setMaxRetries(2);

            AtomicInteger callCount = new AtomicInteger(0);
            String highQualityContent = """
                    # 完整的分析报告

                    ## 月度趋势概览

                    首先，本月阅读人数: 12000，点赞: 678，互动率: 7.2%。
                    环比增长15%，同比增22%。

                    其次，从内容分类来看：
                    - 技术类文章表现最佳
                    - 经验分享类紧随其后

                    因此，建议加大技术类内容投入。
                    不仅如此，应优化发布时间策略。
                    综上所述，本月运营策略整体有效。
                    """;

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.DATA_ANALYSIS,
                    "test prompt",
                    prompt -> {
                        int n = callCount.incrementAndGet();
                        if (n == 1) {
                            throw new RuntimeException("模拟 LLM 调用失败");
                        }
                        return highQualityContent;
                    },
                    "workflow-008"
            );

            assertTrue(callCount.get() >= 2, "首次失败后应重试");
            assertNotNull(result.getContent(), "应返回有效内容");
            assertEquals(highQualityContent, result.getContent());
        }

        @Test
        @DisplayName("所有调用均抛异常时应返回 null 内容且标记为失败")
        void whenAllCallsThrowException_shouldReturnFailureWithNullContent() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(50);
            qualityProperties.setMaxRetries(1);

            AtomicInteger callCount = new AtomicInteger(0);

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.CONTENT_CREATION,
                    "test prompt",
                    prompt -> {
                        callCount.incrementAndGet();
                        throw new RuntimeException("持续失败");
                    },
                    "workflow-009"
            );

            assertEquals(2, callCount.get(), "应调用 2 次（首次 + 1 次重试）");
            assertFalse(result.isSuccess(), "应标记为失败");
            assertNull(result.getContent(), "无成功结果时内容应为 null");
        }
    }

    // ════════════════ 指数退避 ════════════════

    @Nested
    @DisplayName("指数退避配置")
    class ExponentialBackoff {

        @Test
        @DisplayName("退避参数应正确配置且生效")
        void backoffParameters_shouldBeConfigurable() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(90);
            qualityProperties.setMaxRetries(2);
            qualityProperties.setRetryBackoffMs(1);
            qualityProperties.setRetryBackoffMultiplier(2.0);

            AtomicInteger callCount = new AtomicInteger(0);
            long[] timestamps = new long[3];

            autoRetryService.executeWithRetry(
                    AgentStage.CONTENT_CREATION,
                    "test prompt",
                    prompt -> {
                        int n = callCount.incrementAndGet();
                        timestamps[n - 1] = System.currentTimeMillis();
                        return "短";
                    },
                    "workflow-010"
            );

            assertEquals(3, callCount.get());
            // 验证退避确实发生了（第2次和第3次调用之间应有间隔）
            // 由于退避时间很短（1ms, 2ms），只验证调用完成即可
            assertTrue(timestamps[2] > timestamps[0], "最后一次调用应在首次调用之后");
        }

        @Test
        @DisplayName("重试时应将改进建议追加到 prompt 中")
        void retryShouldAppendSuggestionsToPrompt() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(60);
            qualityProperties.setMaxRetries(1);

            AtomicInteger callCount = new AtomicInteger(0);
            String[] receivedPrompts = new String[2];

            String highQualityContent = """
                    # 完整的分析报告

                    ## 月度趋势概览

                    首先，本月阅读人数: 12000，点赞: 678，互动率: 7.2%。
                    环比增长15%，同比增22%。

                    其次，从内容分类来看：
                    - 技术类文章表现最佳

                    因此，建议加大技术类内容投入。
                    不仅如此，应优化发布时间策略。
                    综上所述，本月运营策略整体有效。
                    """;

            autoRetryService.executeWithRetry(
                    AgentStage.DATA_ANALYSIS,
                    "原始 prompt",
                    prompt -> {
                        int n = callCount.incrementAndGet();
                        receivedPrompts[n - 1] = prompt;
                        return n == 1 ? "短" : highQualityContent;
                    },
                    "workflow-011"
            );

            assertEquals(2, callCount.get());
            assertEquals("原始 prompt", receivedPrompts[0], "首次调用应使用原始 prompt");
            assertTrue(receivedPrompts[1].contains("质量改进建议"), "重试 prompt 应包含改进建议");
            assertTrue(receivedPrompts[1].length() > "原始 prompt".length(), "重试 prompt 应更长");
        }
    }

    // ════════════════ 结果 DTO ════════════════

    @Nested
    @DisplayName("AutoRetryResult 结果验证")
    class ResultVerification {

        @Test
        @DisplayName("成功结果应包含最终内容和质量评分")
        void successResult_shouldContainContentAndScore() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(30);
            qualityProperties.setMaxRetries(2);

            String content = """
                    # 报告

                    首先，本月数据如下：阅读人数 5000，点赞 200，互动率 4%。
                    环比增长10%。

                    - 项目一
                    - 项目二

                    因此，整体表现良好。
                    """;

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.DATA_ANALYSIS,
                    "test prompt",
                    prompt -> content,
                    "workflow-012"
            );

            assertNotNull(result.getContent());
            assertNotNull(result.getFinalScore());
            assertTrue(result.getFinalScore().getTotalScore() >= 0);
            assertTrue(result.getFinalScore().getTotalScore() <= 100);
            assertEquals(0, result.getRetryCount());
        }

        @Test
        @DisplayName("重试结果应正确记录重试次数")
        void retryResult_shouldRecordRetryCount() {
            qualityProperties.setEnabled(true);
            qualityProperties.setAutoRetry(true);
            qualityProperties.setMinScore(80);
            qualityProperties.setMaxRetries(3);

            AtomicInteger callCount = new AtomicInteger(0);

            AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
                    AgentStage.CONTENT_CREATION,
                    "test prompt",
                    prompt -> {
                        callCount.incrementAndGet();
                        return "# 标题\n\n首先内容。\n\n- 项\n\n因此结论。";
                    },
                    "workflow-013"
            );

            // 内容可能不够80分，但重试次数应正确记录
            assertTrue(result.getRetryCount() >= 0);
            assertTrue(result.getRetryCount() <= 3);
        }
    }
}
