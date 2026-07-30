package com.contentops.analysis.tool;

import com.contentops.analysis.tool.MetricsCalculator.AggregatedMetrics;
import com.contentops.analysis.tool.MetricsCalculator.CategoryPerformance;
import com.contentops.analysis.tool.MetricsCalculator.TimeSlotPerformance;
import com.contentops.common.platform.MetricsParser;
import com.contentops.common.platform.MetricsParser.ParsedMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnalysisTools 与 MetricsCalculator 单元测试。
 *
 * <p>验证将"框架性引导文本"改造为"程序化精确计算"后的正确性：
 * <ul>
 *   <li>{@link AnalysisTools#calculateMetrics} 使用真实数值文本输入，验证解析与聚合计算</li>
 *   <li>{@link AnalysisTools#analyzeByCategory} 验证内容分类与平均互动率计算</li>
 *   <li>{@link AnalysisTools#analyzeByTimeSlot} 验证时段分组与最佳时段排名</li>
 *   <li>{@link AnalysisTools#generateChartData} 验证生成的 ECharts JSON 含真实数据</li>
 *   <li>{@link MetricsCalculator} 各方法（聚合/分类/时段/图表）的独立验证</li>
 * </ul>
 *
 * <p>四个分析方法不依赖平台 Service（仅使用 metricsParser 与 metricsCalculator），
 * 故构造 AnalysisTools 时将平台 Service 传入 null。
 */
@DisplayName("AnalysisTools 与 MetricsCalculator 程序化计算测试")
class AnalysisToolsTest {

    private MetricsParser metricsParser;
    private MetricsCalculator metricsCalculator;
    private AnalysisTools analysisTools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        metricsParser = new MetricsParser();
        metricsCalculator = new MetricsCalculator(metricsParser, objectMapper);
        // 四个分析方法仅依赖 metricsParser 与 metricsCalculator，平台 Service 及画像服务传 null 即可
        analysisTools = new AnalysisTools(
                null, null, null, null, null, metricsParser, metricsCalculator, null, null);
    }

    /** 微信公众号组合数据（图文阅读 + 用户增减 + 图文详细），用于聚合指标测试。 */
    private static final String WECHAT_COMBINED_DATA =
            "[微信图文阅读数据] 日期: 2024-01-15\n"
                    + "- 标题: 干货教程：公众号涨粉方法\n"
                    + "  阅读人数: 1000, 阅读次数: 1500, 分享人数: 50, 分享次数: 80\n"
                    + "汇总: 阅读人数 1000, 阅读次数 1500, 分享人数 50, 分享次数 80\n\n"
                    + "[微信用户数据] 日期范围: 2024-01-08 ~ 2024-01-15\n"
                    + "- 2024-01-15 [全部] 新增: 100, 取消: 10\n"
                    + "汇总: 净增粉丝 90 (新增 100 - 取消 10)\n\n"
                    + "[微信图文详细数据] 日期范围: 2024-01-15 ~ 2024-01-15\n"
                    + "- 标题: 干货教程：公众号涨粉方法\n"
                    + "  统计日期: 2024-01-15\n"
                    + "  阅读人数: 1000, 分享人数: 50, 评论数: 30, 收藏人数: 200\n"
                    + "  阅读完成率: 45.2%, 平均阅读时长: 3.5分钟, 阅读送达率: 92.30%\n";

    /** 含发布时间与完整互动字段的干净多标题数据，用于分类/时段/趋势测试。 */
    private static final String MULTI_ITEM_DATA =
            "[内容数据]\n"
                    + "- 标题: 干货教程：涨粉技巧\n"
                    + "  发布时间: 2024-01-15 08:30:00\n"
                    + "  阅读人数: 5000, 点赞: 600, 评论数: 80, 分享次数: 40, 收藏人数: 100\n"
                    + "- 标题: 我的成长故事\n"
                    + "  发布时间: 2024-01-15 12:30:00\n"
                    + "  阅读人数: 3000, 点赞: 500, 评论数: 100, 分享次数: 60, 收藏人数: 150\n"
                    + "- 标题: 热点解读：最新事件\n"
                    + "  发布时间: 2024-01-15 21:00:00\n"
                    + "  阅读人数: 8000, 点赞: 400, 评论数: 50, 分享次数: 20, 收藏人数: 80\n";

    /** 抖音视频列表数据（创建时间为 Unix 时间戳），用于时段测试。 */
    private static final String DOUYIN_DATA =
            "[抖音视频列表] 共 3 条视频\n"
                    + "- 标题: 干货教程：涨粉技巧\n"
                    + "  video_id: v1\n"
                    + "  创建时间: 1705276800\n"
                    + "  播放: 5000, 点赞: 600, 评论: 80, 分享: 40\n"
                    + "- 标题: 我的成长故事\n"
                    + "  video_id: v2\n"
                    + "  创建时间: 1705291200\n"
                    + "  播放: 3000, 点赞: 500, 评论: 100, 分享: 60\n"
                    + "- 标题: 热点解读：最新事件\n"
                    + "  video_id: v3\n"
                    + "  创建时间: 1705323600\n"
                    + "  播放: 8000, 点赞: 400, 评论: 50, 分享: 20\n";

    // ════════════════ AnalysisTools.calculateMetrics ════════════════

    @Nested
    @DisplayName("calculateMetrics 聚合指标计算")
    class CalculateMetricsTest {

        @Test
        @DisplayName("应解析真实数值并输出聚合计算结果（含具体数值）")
        void shouldParseAndCalculateRealMetrics() {
            String result = analysisTools.calculateMetrics(WECHAT_COMBINED_DATA);

            assertNotNull(result);
            // 不再返回框架引导文本
            assertFalse(result.contains("请基于以上数据计算"),
                    "不应返回框架性引导文本");

            // 真实计算数值
            assertTrue(result.contains("总阅读量: 1,000"), "应包含真实总阅读量");
            assertTrue(result.contains("总播放量: 1,500"), "应包含真实总播放量");
            assertTrue(result.contains("总评论: 30"), "应包含真实总评论");
            assertTrue(result.contains("总分享/转发: 80"), "应包含真实总分享");
            assertTrue(result.contains("总收藏: 200"), "应包含真实总收藏");
            assertTrue(result.contains("总互动量(赞+评+转+收藏): 310"), "应包含真实总互动量");
            // 平均互动率 = 310 / max(1000,1500) = 20.67%
            assertTrue(result.contains("平均互动率: 20.67%"),
                    "应包含程序化计算的平均互动率");
            // 完读率 = 45.2%
            assertTrue(result.contains("完读率/完播率: 45.2%"), "应包含真实完读率");
            // 粉丝净增长 +90
            assertTrue(result.contains("粉丝净增长: +90"), "应包含粉丝净增长");
            // 环比增速 = 90/100 = 90.0%
            assertTrue(result.contains("环比增速: +90.0%"), "应包含环比增速");
        }

        @Test
        @DisplayName("无有效数据时应返回明确的未检测到数据提示")
        void shouldReturnNoDataHintWhenUnparseable() {
            String result = analysisTools.calculateMetrics("这是一段没有任何指标数值的文本");
            assertTrue(result.contains("未检测到数据"),
                    "解析不到数值时应返回明确的未检测到数据提示");
        }

        @Test
        @DisplayName("空输入应返回未检测到数据提示而非空结果")
        void shouldReturnNoDataHintForEmptyInput() {
            String result = analysisTools.calculateMetrics("");
            assertTrue(result.contains("未检测到数据"));
            assertNotNull(result);
            assertFalse(result.isBlank());
        }
    }

    // ════════════════ AnalysisTools.analyzeByCategory ════════════════

    @Nested
    @DisplayName("analyzeByCategory 内容类型分析")
    class AnalyzeByCategoryTest {

        @Test
        @DisplayName("应按关键词分类并输出量化对比表")
        void shouldCategorizeAndComputeAvgEngagement() {
            String data = MULTI_ITEM_DATA
                    + "- 标题: 清单盘点：精选推荐合集\n"
                    + "  阅读人数: 2000, 点赞: 300, 评论数: 40, 分享次数: 30, 收藏人数: 250\n"
                    + "- 标题: 观点：为什么应该思考\n"
                    + "  阅读人数: 1500, 点赞: 200, 评论数: 150, 分享次数: 10, 收藏人数: 50\n";

            String result = analysisTools.analyzeByCategory(data);

            assertNotNull(result);
            assertFalse(result.contains("请基于各平台数据"), "不应返回框架引导文本");
            assertTrue(result.contains("量化对比表"));
            assertTrue(result.contains("干货教程"));
            assertTrue(result.contains("个人故事"));
            assertTrue(result.contains("热点解读"));
            assertTrue(result.contains("清单盘点"));
            assertTrue(result.contains("观点输出"));
            assertTrue(result.contains("表现最优"));
            assertTrue(result.contains("排名"));
        }

        @Test
        @DisplayName("无内容标题时应返回未检测到数据提示")
        void shouldReturnNoDataWhenNoTitles() {
            String result = analysisTools.analyzeByCategory("没有标题和数据的普通文本");
            assertTrue(result.contains("未检测到数据"));
        }
    }

    // ════════════════ AnalysisTools.analyzeByTimeSlot ════════════════

    @Nested
    @DisplayName("analyzeByTimeSlot 时段分析")
    class AnalyzeByTimeSlotTest {

        @Test
        @DisplayName("应按时段分组并输出最佳时段排名")
        void shouldGroupByTimeSlotAndRank() {
            String result = analysisTools.analyzeByTimeSlot(MULTI_ITEM_DATA);

            assertNotNull(result);
            assertFalse(result.contains("请基于各平台数据中的发布时间"), "不应返回框架引导文本");
            assertTrue(result.contains("最佳发文时段"));
            assertTrue(result.contains("早高峰"));
            assertTrue(result.contains("午休"));
            assertTrue(result.contains("晚高峰"));
            // 午休互动率最高(0.27)，应为最佳时段
            assertTrue(result.contains("午休"));
            assertTrue(result.contains("黄金时段"));
        }

        @Test
        @DisplayName("应支持抖音 create_time 的 Unix 时间戳格式")
        void shouldSupportDouyinCreateTimeTimestamp() {
            String result = analysisTools.analyzeByTimeSlot(DOUYIN_DATA);
            assertNotNull(result);
            // 三个时间戳分别对应早高峰(08)、午休(12)、晚高峰(21)
            assertTrue(result.contains("早高峰"));
            assertTrue(result.contains("午休"));
            assertTrue(result.contains("晚高峰"));
            assertTrue(result.contains("最佳发文时段"));
        }
    }

    // ════════════════ AnalysisTools.generateChartData ════════════════

    @Nested
    @DisplayName("generateChartData 图表 JSON 生成")
    class GenerateChartDataTest {

        @Test
        @DisplayName("pie 图表应包含真实互动构成数据")
        void shouldGeneratePieChartWithRealData() throws Exception {
            String json = analysisTools.generateChartData(WECHAT_COMBINED_DATA, "pie");

            Map<String, Object> chart = parseJson(json);
            assertEquals("pie", chart.get("chartType"));
            // 收藏=200、分享=80、评论=30 应作为真实数据出现
            assertPieContainsValue(chart, 200);
            assertPieContainsValue(chart, 80);
            assertPieContainsValue(chart, 30);
        }

        @Test
        @DisplayName("bar 图表应包含真实分类互动率对比")
        void shouldGenerateBarChartWithRealCategories() throws Exception {
            String json = analysisTools.generateChartData(MULTI_ITEM_DATA, "bar");

            Map<String, Object> chart = parseJson(json);
            assertEquals("bar", chart.get("chartType"));
            assertNotNull(chart.get("series"));
            assertNotNull(chart.get("xAxis"));
        }

        @Test
        @DisplayName("trend 图表应包含真实趋势数据点")
        void shouldGenerateTrendChartWithRealDataPoints() throws Exception {
            String json = analysisTools.generateChartData(MULTI_ITEM_DATA, "trend");

            Map<String, Object> chart = parseJson(json);
            assertEquals("trend", chart.get("chartType"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> series = (List<Map<String, Object>>) chart.get("series");
            assertNotNull(series);
            assertFalse(series.isEmpty(), "趋势图应有真实数据 series");
            // 触达量系列应包含三条内容的真实触达数据
            List<Object> reachData = findSeriesData(series, "触达量");
            assertNotNull(reachData);
            assertEquals(3, reachData.size(), "应有3个真实数据点");
        }

        @Test
        @DisplayName("scatter 图表应包含真实散点数据")
        void shouldGenerateScatterChartWithRealPoints() throws Exception {
            String json = analysisTools.generateChartData(MULTI_ITEM_DATA, "scatter");

            Map<String, Object> chart = parseJson(json);
            assertEquals("scatter", chart.get("chartType"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> series = (List<Map<String, Object>>) chart.get("series");
            assertNotNull(series);
            @SuppressWarnings("unchecked")
            List<Object> data = (List<Object>) series.get(0).get("data");
            assertEquals(3, data.size(), "应有3个真实散点");
        }

        @Test
        @DisplayName("无有效数据时应返回 no_data JSON 提示")
        void shouldReturnNoDataJson() throws Exception {
            String json = analysisTools.generateChartData("无可解析数值的文本", "trend");
            Map<String, Object> chart = parseJson(json);
            assertEquals("no_data", chart.get("status"));
            assertTrue(json.contains("未检测到数据"));
        }

        @Test
        @DisplayName("默认图表类型应为 trend")
        void shouldDefaultToTrend() throws Exception {
            String json = analysisTools.generateChartData(MULTI_ITEM_DATA, "");
            Map<String, Object> chart = parseJson(json);
            assertEquals("trend", chart.get("chartType"));
        }
    }

    // ════════════════ MetricsCalculator 独立测试 ════════════════

    @Nested
    @DisplayName("MetricsCalculator 指标计算工具")
    class MetricsCalculatorTest {

        @Test
        @DisplayName("calculateAggregatedMetrics 应正确聚合真实指标")
        void shouldAggregateMetricsCorrectly() {
            AggregatedMetrics agg = metricsCalculator.calculateAggregatedMetrics(WECHAT_COMBINED_DATA);

            assertTrue(agg.hasData(), "应检测到有效数据");
            assertEquals(1000L, agg.totalReads(), "总阅读量");
            assertEquals(1500L, agg.totalPlays(), "总播放量");
            assertEquals(30L, agg.totalComments(), "总评论");
            assertEquals(80L, agg.totalShares(), "总分享");
            assertEquals(200L, agg.totalCollects(), "总收藏");
            assertEquals(310L, agg.totalEngagement(), "总互动量=赞+评+转+收藏");
            assertEquals(0.2067, agg.avgEngagementRate(), 0.001, "平均互动率=310/1500");
            assertEquals(0.452, agg.readFinishRate(), 0.001, "完读率");
            assertEquals(90L, agg.netGrowth(), "粉丝净增长");
            assertEquals(0.9, agg.growthRate(), 0.001, "环比增速=90/100");
            assertEquals(1, agg.platformCount(), "检测到1个平台");
            assertTrue(agg.platformsDetected().contains("微信公众号"));
        }

        @Test
        @DisplayName("calculateAggregatedMetrics 空输入应返回 hasData=false")
        void shouldReturnNoDataForEmptyInput() {
            AggregatedMetrics agg = metricsCalculator.calculateAggregatedMetrics("");
            assertFalse(agg.hasData());
            assertEquals(0, agg.platformCount());
        }

        @Test
        @DisplayName("categorizeContent 应按关键词分类并填充表现排名")
        void shouldCategorizeAndRankContent() {
            String data = MULTI_ITEM_DATA
                    + "- 标题: 清单盘点：精选推荐合集\n"
                    + "  阅读人数: 2000, 点赞: 300, 评论数: 40, 分享次数: 30, 收藏人数: 250\n"
                    + "- 标题: 观点：为什么应该思考\n"
                    + "  阅读人数: 1500, 点赞: 200, 评论数: 150, 分享次数: 10, 收藏人数: 50\n";

            List<CategoryPerformance> cats = metricsCalculator.categorizeContent(data);

            assertEquals(5, cats.size(), "应有5个分类");

            // 平均互动率：清单盘点=620/2000=0.31 最高，应排第1
            CategoryPerformance best = cats.get(0);
            assertEquals("清单盘点", best.contentType());
            assertEquals(1, best.performanceRank());
            assertEquals(0.31, best.avgEngagementRate(), 0.001);
            assertEquals(1, best.contentCount());

            // 热点解读=550/8000=0.06875 最低，应排第5
            CategoryPerformance worst = cats.get(cats.size() - 1);
            assertEquals("热点解读", worst.contentType());
            assertEquals(5, worst.performanceRank());

            // 排名应连续且从1开始
            for (int i = 0; i < cats.size(); i++) {
                assertEquals(i + 1, cats.get(i).performanceRank());
            }
        }

        @Test
        @DisplayName("analyzeTimeSlots 应按时段分组并按互动率排序")
        void shouldAnalyzeTimeSlotsAndRank() {
            List<TimeSlotPerformance> slots = metricsCalculator.analyzeTimeSlots(MULTI_ITEM_DATA);

            assertEquals(3, slots.size(), "应有3个有时段数据的分组");

            // 全部为黄金时段
            assertTrue(slots.stream().allMatch(TimeSlotPerformance::isGoldenHour));

            // 午休互动率最高(810/3000=0.27)，应排第1
            TimeSlotPerformance best = slots.get(0);
            assertEquals("午休(12:00-13:00)", best.timeSlot());
            assertEquals(0.27, best.avgEngagementRate(), 0.001);
            assertEquals(1, best.contentCount());
            assertFalse(best.recommendation().isBlank(), "应有发布建议");

            // 早高峰(820/5000=0.164)与晚高峰(550/8000=0.06875)均存在
            TimeSlotPerformance morning = findSlot(slots, "早高峰");
            assertNotNull(morning);
            assertEquals(0.164, morning.avgEngagementRate(), 0.001);

            TimeSlotPerformance evening = findSlot(slots, "晚高峰");
            assertNotNull(evening);
            assertEquals(0.06875, evening.avgEngagementRate(), 0.0001);
        }

        @Test
        @DisplayName("generateTrendChart 应生成含真实数据的 ECharts JSON")
        void shouldGenerateTrendChart() throws Exception {
            List<ParsedMetrics> metrics = List.of(
                    metricsParser.parse("- 标题: a\n  阅读人数: 5000, 点赞: 600\n"),
                    metricsParser.parse("- 标题: b\n  阅读人数: 3000, 点赞: 500\n"));

            String json = metricsCalculator.generateTrendChart(metrics);

            Map<String, Object> chart = parseJson(json);
            assertEquals("trend", chart.get("chartType"));
            @SuppressWarnings("unchecked")
            List<String> xData = (List<String>) ((Map<String, Object>) chart.get("xAxis")).get("data");
            assertEquals(List.of("第1期", "第2期"), xData);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> series = (List<Map<String, Object>>) chart.get("series");
            List<Object> reachData = findSeriesData(series, "触达量");
            assertEquals(List.of(5000, 3000), reachData);
        }

        @Test
        @DisplayName("generateTrendChart 空数据应返回 no_data JSON")
        void shouldReturnNoDataTrendChart() throws Exception {
            String json = metricsCalculator.generateTrendChart(List.of());
            Map<String, Object> chart = parseJson(json);
            assertEquals("no_data", chart.get("status"));
        }

        @Test
        @DisplayName("generateDistributionChart 应生成含真实占比的饼图 JSON")
        void shouldGenerateDistributionChart() throws Exception {
            Map<String, Double> dist = new LinkedHashMap<>();
            dist.put("点赞", 600.0);
            dist.put("评论", 80.0);

            String json = metricsCalculator.generateDistributionChart(dist, "互动分布");

            Map<String, Object> chart = parseJson(json);
            assertEquals("pie", chart.get("chartType"));
            assertEquals("互动分布", ((Map<String, Object>) chart.get("title")).get("text"));
        }

        @Test
        @DisplayName("generateComparisonChart 应生成含真实分类的柱状图 JSON")
        void shouldGenerateComparisonChart() throws Exception {
            List<CategoryPerformance> cats = metricsCalculator.categorizeContent(MULTI_ITEM_DATA);

            String json = metricsCalculator.generateComparisonChart(cats);

            Map<String, Object> chart = parseJson(json);
            assertEquals("bar", chart.get("chartType"));
            assertNotNull(chart.get("series"));
            @SuppressWarnings("unchecked")
            List<String> xData = (List<String>) ((Map<String, Object>) chart.get("xAxis")).get("data");
            assertFalse(xData.isEmpty());
        }

        @Test
        @DisplayName("generateComparisonChart 空数据应返回 no_data JSON")
        void shouldReturnNoDataComparisonChart() throws Exception {
            String json = metricsCalculator.generateComparisonChart(List.of());
            Map<String, Object> chart = parseJson(json);
            assertEquals("no_data", chart.get("status"));
        }
    }

    // ════════════════ 测试辅助方法 ════════════════

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) throws Exception {
        return objectMapper.readValue(json, Map.class);
    }

    /** 在 series 列表中按 name 查找对应的 data。 */
    @SuppressWarnings("unchecked")
    private List<Object> findSeriesData(List<Map<String, Object>> series, String name) {
        for (Map<String, Object> s : series) {
            if (name.equals(s.get("name"))) {
                return (List<Object>) s.get("data");
            }
        }
        return null;
    }

    /** 在饼图 data 中断言存在指定数值。 */
    @SuppressWarnings("unchecked")
    private void assertPieContainsValue(Map<String, Object> chart, Number expected) {
        List<Map<String, Object>> series = (List<Map<String, Object>>) chart.get("series");
        assertNotNull(series);
        List<Map<String, Object>> data = (List<Map<String, Object>>) series.get(0).get("data");
        boolean found = data.stream()
                .anyMatch(d -> Number.class.cast(d.get("value")).doubleValue() == expected.doubleValue());
        assertTrue(found, "饼图应包含真实数值 " + expected);
    }

    /** 在时段列表中按名称片段查找。 */
    private TimeSlotPerformance findSlot(List<TimeSlotPerformance> slots, String nameFragment) {
        return slots.stream()
                .filter(s -> s.timeSlot().contains(nameFragment))
                .findFirst()
                .orElse(null);
    }
}
