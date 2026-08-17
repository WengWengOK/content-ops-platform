package com.contentops.common.methodology;

import com.contentops.common.dto.AnalysisReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TrendAggregationEnforcer 单元测试。
 *
 * <p>验证 v2.2.0 方法论「趋势而非单篇」强制器的核心行为：
 * <ul>
 *   <li>{@link TrendAggregationEnforcer#validateTrendCoverage} — 启用/关闭、关键词命中/缺失、
 *       insights 条数校验</li>
 *   <li>{@link TrendAggregationEnforcer#enforceMonthlyAggregation} — 透传、已达标透传、
 *       软/硬强制、月份提取与按日期排序</li>
 * </ul>
 */
@DisplayName("TrendAggregationEnforcer 测试")
class TrendAggregationEnforcerTest {

    private TrendAggregationProperties properties;
    private TrendAggregationEnforcer enforcer;

    @BeforeEach
    void setUp() {
        // 默认配置：enabled=true, requireKeywords=[月度,趋势,环比,同比], minInsights=3,
        //          hardEnforce=false, minMonthsForTrend=2；各用例按需覆盖
        properties = new TrendAggregationProperties();
        enforcer = new TrendAggregationEnforcer(properties);
    }

    // ════════════════ validateTrendCoverage 校验 ════════════════

    @Nested
    @DisplayName("validateTrendCoverage — 趋势覆盖校验")
    class ValidateTrendCoverage {

        @Test
        @DisplayName("校验器关闭时应直接放行，返回 valid=true")
        void whenDisabled_shouldReturnValid() {
            properties.setEnabled(false);
            AnalysisReport report = AnalysisReport.builder().build();

            var result = enforcer.validateTrendCoverage(report);

            assertTrue(result.valid());
            assertTrue(result.matchedKeywords().isEmpty());
            assertTrue(result.missingKeywords().isEmpty());
            assertEquals(0, result.insightsCount());
            assertEquals(properties.getMinInsights(), result.minInsightsRequired());
        }

        @Test
        @DisplayName("启用且命中'月度'关键词、insights 充足时应返回 valid=true")
        void whenEnabledAndKeywordMatchedWithEnoughInsights_shouldReturnValid() {
            properties.setEnabled(true);
            properties.setRequireKeywords(List.of("月度"));
            properties.setMinInsights(3);
            AnalysisReport report = AnalysisReport.builder()
                    .insights(new ArrayList<>(List.of(
                            "本月月度阅读量稳定增长",
                            "用户互动率有所提升",
                            "内容产出保持节奏"
                    )))
                    .build();

            var result = enforcer.validateTrendCoverage(report);

            assertTrue(result.valid());
            assertTrue(result.matchedKeywords().contains("月度"));
            assertTrue(result.missingKeywords().isEmpty());
            assertEquals(3, result.insightsCount());
            assertEquals(3, result.minInsightsRequired());
        }

        @Test
        @DisplayName("启用但未命中任何趋势关键词时应返回 valid=false 且包含缺失关键词")
        void whenEnabledButNoTrendKeyword_shouldReturnInvalidWithMissing() {
            properties.setEnabled(true);
            properties.setRequireKeywords(List.of("月度"));
            properties.setMinInsights(3);
            AnalysisReport report = AnalysisReport.builder()
                    .insights(new ArrayList<>(List.of(
                            "总体阅读量稳定",
                            "用户互动率提升",
                            "内容产出节奏正常"
                    )))
                    .build();

            var result = enforcer.validateTrendCoverage(report);

            assertFalse(result.valid());
            assertTrue(result.missingKeywords().contains("月度"));
            assertTrue(result.matchedKeywords().isEmpty());
            assertEquals(3, result.insightsCount());
        }
    }

    // ════════════════ enforceMonthlyAggregation 强制聚合 ════════════════

    @Nested
    @DisplayName("enforceMonthlyAggregation — 月度聚合强制")
    class EnforceMonthlyAggregation {

        @Test
        @DisplayName("校验器关闭时应原样返回报告，不做任何修改")
        void whenDisabled_shouldReturnReportUnchanged() {
            properties.setEnabled(false);
            AnalysisReport report = AnalysisReport.builder()
                    .insights(new ArrayList<>(List.of("总体阅读量稳定")))
                    .build();

            AnalysisReport result = enforcer.enforceMonthlyAggregation(report, "2026-06: 1000");

            assertSame(report, result);
            assertEquals(1, result.getInsights().size());
            assertNull(result.getChartData());
        }

        @Test
        @DisplayName("报告已满足趋势覆盖时应不修改原样返回")
        void whenReportAlreadyValid_shouldReturnUnchanged() {
            properties.setEnabled(true);
            properties.setRequireKeywords(List.of("月度"));
            properties.setMinInsights(3);
            AnalysisReport report = AnalysisReport.builder()
                    .insights(new ArrayList<>(List.of(
                            "月度阅读趋势良好",
                            "用户互动稳步提升",
                            "内容产出保持节奏"
                    )))
                    .build();
            int originalSize = report.getInsights().size();

            AnalysisReport result = enforcer.enforceMonthlyAggregation(report, null);

            assertSame(report, result);
            assertEquals(originalSize, result.getInsights().size());
            assertNull(result.getChartData());
        }

        @Test
        @DisplayName("报告无效且 rawData 含月度数据时应追加环比补充洞察并按月份排序")
        void whenInvalidWithMonthlyData_shouldAppendComparisonAndSortMonths() {
            properties.setEnabled(true);
            properties.setRequireKeywords(List.of("月度"));
            properties.setMinInsights(3);
            properties.setHardEnforce(false);
            AnalysisReport report = AnalysisReport.builder()
                    .insights(new ArrayList<>(List.of(
                            "总体阅读量稳定",
                            "用户互动率提升"
                    )))
                    .build();
            String rawData = "2026-04: 10000\n2026-05: 12000\n2026-06: 8000";

            AnalysisReport result = enforcer.enforceMonthlyAggregation(report, rawData);

            assertSame(report, result);
            assertEquals(3, result.getInsights().size());
            String supplementary = result.getInsights().get(result.getInsights().size() - 1);
            assertTrue(supplementary.contains("月度聚合"), "应追加月度聚合补充洞察");
            assertTrue(supplementary.contains("环比"), "补充洞察应包含环比对比");
            assertNotNull(result.getChartData());
            assertEquals(Boolean.TRUE, result.getChartData().get("trendEnforced"));
            assertNotNull(result.getChartData().get("monthlyComparison"));
            @SuppressWarnings("unchecked")
            Map<String, Double> monthlyTrend = (Map<String, Double>) result.getChartData().get("monthlyTrend");
            List<String> months = new ArrayList<>(monthlyTrend.keySet());
            assertEquals(List.of("2026-04", "2026-05", "2026-06"), months, "月份应按时间顺序排列");
        }

        @Test
        @DisplayName("软强制模式下 rawData 无月度数据时应追加占位洞察且不抛异常")
        void whenSoftEnforceAndNoMonthlyData_shouldAppendPlaceholderWithoutThrowing() {
            properties.setEnabled(true);
            properties.setRequireKeywords(List.of("月度"));
            properties.setMinInsights(3);
            properties.setHardEnforce(false);
            AnalysisReport report = AnalysisReport.builder()
                    .insights(new ArrayList<>(List.of("总体阅读量稳定")))
                    .build();
            String rawData = "此处没有任何月份数据格式";

            AnalysisReport result = enforcer.enforceMonthlyAggregation(report, rawData);

            assertSame(report, result);
            assertTrue(result.getInsights().size() > 1);
            boolean hasPlaceholder = result.getInsights().stream()
                    .anyMatch(s -> s.contains("月度聚合"));
            assertTrue(hasPlaceholder, "应追加月度聚合占位洞察");
            assertNotNull(result.getChartData());
            assertEquals(Boolean.TRUE, result.getChartData().get("trendEnforced"));
        }

        @Test
        @DisplayName("硬强制模式下 rawData 仅 1 个月数据时应抛出 TrendEnforcementException")
        void whenHardEnforceAndInsufficientMonths_shouldThrow() {
            properties.setEnabled(true);
            properties.setRequireKeywords(List.of("月度"));
            properties.setMinInsights(3);
            properties.setHardEnforce(true);
            properties.setMinMonthsForTrend(2);
            AnalysisReport report = AnalysisReport.builder()
                    .insights(new ArrayList<>(List.of("总体阅读量稳定")))
                    .build();
            String rawData = "2026-06: 8000";

            TrendEnforcementException ex = assertThrows(TrendEnforcementException.class,
                    () -> enforcer.enforceMonthlyAggregation(report, rawData));

            assertNotNull(ex.getMessage());
            assertNotNull(ex.getValidationResult());
            assertFalse(ex.getValidationResult().valid());
        }

        @Test
        @DisplayName("硬强制模式下 rawData 月份充足时应成功并追加补充洞察")
        void whenHardEnforceAndEnoughMonths_shouldSucceed() {
            properties.setEnabled(true);
            properties.setRequireKeywords(List.of("月度"));
            properties.setMinInsights(3);
            properties.setHardEnforce(true);
            properties.setMinMonthsForTrend(2);
            AnalysisReport report = AnalysisReport.builder()
                    .insights(new ArrayList<>(List.of(
                            "总体阅读量稳定",
                            "用户互动率提升"
                    )))
                    .build();
            String rawData = "2026-05: 12000\n2026-06: 8000";

            AnalysisReport result = enforcer.enforceMonthlyAggregation(report, rawData);

            assertSame(report, result);
            assertEquals(3, result.getInsights().size());
            String supplementary = result.getInsights().get(result.getInsights().size() - 1);
            assertTrue(supplementary.contains("月度聚合"));
            assertNotNull(result.getChartData());
            assertNotNull(result.getChartData().get("monthlyTrend"));
        }

        @Test
        @DisplayName("rawData 中乱序月份应被按日期排序后写入 chartData.monthlyTrend")
        void shouldSortMonthsChronologicallyInChartData() {
            properties.setEnabled(true);
            properties.setRequireKeywords(List.of("月度"));
            properties.setMinInsights(3);
            properties.setHardEnforce(false);
            AnalysisReport report = AnalysisReport.builder()
                    .insights(new ArrayList<>(List.of("总体阅读量稳定")))
                    .build();
            String rawData = "2026-06: 100\n2026-04: 50\n2026-05: 75";

            AnalysisReport result = enforcer.enforceMonthlyAggregation(report, rawData);

            assertNotNull(result.getChartData());
            @SuppressWarnings("unchecked")
            Map<String, Double> monthlyTrend = (Map<String, Double>) result.getChartData().get("monthlyTrend");
            List<String> months = new ArrayList<>(monthlyTrend.keySet());
            assertEquals(List.of("2026-04", "2026-05", "2026-06"), months, "月份应按时间顺序排列");
            assertEquals(50.0, monthlyTrend.get("2026-04"), 0.0001);
            assertEquals(75.0, monthlyTrend.get("2026-05"), 0.0001);
            assertEquals(100.0, monthlyTrend.get("2026-06"), 0.0001);
        }
    }
}
