package com.contentops.analysis.tool;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Data analysis tools exposed to the {@link com.contentops.analysis.agent.DataAnalysisAgent}.
 *
 * <p>LangChain4j discovers {@code @Tool} methods on Spring-managed objects that are passed to
 * {@code AiServices.builder().tools(...)} and exposes them to the model as callable functions.
 * The implementations here return simulated data; in production they would delegate to real
 * analytics / BI APIs.
 */
@Slf4j
@Component
public class AnalysisTools {

    @Tool("计算内容的平均表现指标")
    public String calculateMetrics(String rawData) {
        log.info("[Tool] calculateMetrics invoked, rawData length: {}",
                rawData != null ? rawData.length() : 0);
        String preview = rawData != null && rawData.length() > 30
                ? rawData.substring(0, 30) : rawData;
        return "[模拟指标] 基于「" + preview + "…」计算的平均表现指标：\n"
                + "- 平均阅读量：18560\n"
                + "- 平均点赞：612\n"
                + "- 平均转发：148\n"
                + "- 平均评论：93\n"
                + "- 平均互动率：4.6%\n"
                + "- 平均完读率：62.3%\n"
                + "- 本周期净增粉丝：+12480\n"
                + "- 发文总数：24篇\n"
                + "提示：以上指标基于原始数据按月聚合统计得出，可作为后续维度分析的基础。";
    }

    @Tool("按内容类型分组分析表现")
    public String analyzeByCategory(String rawData) {
        log.info("[Tool] analyzeByCategory invoked, rawData length: {}",
                rawData != null ? rawData.length() : 0);
        return "[模拟数据] 按内容类型分组的平均表现对比：\n"
                + "1. 干货教程类（8篇）：平均阅读24300，互动率6.1%，表现最佳\n"
                + "2. 个人故事类（6篇）：平均阅读19800，互动率5.8%，粉丝增长贡献最大\n"
                + "3. 热点解读类（5篇）：平均阅读28700，互动率4.2%，阅读最高但互动偏低\n"
                + "4. 清单盘点类（3篇）：平均阅读15200，互动率3.9%，表现平稳\n"
                + "5. 观点输出类（2篇）：平均阅读8900，互动率2.1%，表现最弱\n"
                + "结论：干货教程类和个人故事类是当前账号的核心优势方向，"
                + "观点输出类建议减少或调整角度。";
    }

    @Tool("按发布时间分析最佳发文时段")
    public String analyzeByTimeSlot(String rawData) {
        log.info("[Tool] analyzeByTimeSlot invoked, rawData length: {}",
                rawData != null ? rawData.length() : 0);
        return "[模拟数据] 按发布时间段的表现分析：\n"
                + "周一 07:00-09:00：平均阅读22100，互动率5.3%（通勤早高峰，表现优秀）\n"
                + "周二 12:00-13:00：平均阅读18900，互动率4.8%（午休时段，表现良好）\n"
                + "周三 21:00-22:00：平均阅读26800，互动率6.7%（晚间最佳时段）\n"
                + "周四 20:00-21:00：平均阅读24300，互动率6.2%（晚间次佳时段）\n"
                + "周五 18:00-19:00：平均阅读17600，互动率4.1%（下班通勤，表现一般）\n"
                + "周六 10:00-11:00：平均阅读19800，互动率5.5%（周末上午，表现良好）\n"
                + "周日 22:00-23:00：平均阅读12300，互动率3.4%（深夜，表现最弱）\n"
                + "结论：周三21:00-22:00和周四20:00-21:00是最佳发文窗口，"
                + "建议将核心干货内容安排在这两个时段发布。";
    }

    @Tool("生成可视化图表数据")
    public String generateChartData(String metricsData, String chartType) {
        log.info("[Tool] generateChartData invoked, chartType: {}, metricsData length: {}",
                chartType, metricsData != null ? metricsData.length() : 0);
        String type = (chartType == null || chartType.isBlank()) ? "trend" : chartType;
        return "[模拟图表数据] 图表类型：" + type + "\n"
                + "{\n"
                + "  \"chartType\": \"" + type + "\",\n"
                + "  \"title\": \"内容运营数据可视化\",\n"
                + "  \"series\": [\n"
                + "    { \"name\": \"阅读量\", \"data\": [18200, 19500, 22300, 24100, 20800, 25600, 28900] },\n"
                + "    { \"name\": \"互动率(%)\", \"data\": [3.8, 4.1, 4.6, 5.2, 4.9, 5.8, 6.3] }\n"
                + "  ],\n"
                + "  \"categories\": [\"第1周\", \"第2周\", \"第3周\", \"第4周\", \"第5周\", \"第6周\", \"第7周\"],\n"
                + "  \"categoryDistribution\": [\n"
                + "    { \"name\": \"干货教程\", \"value\": 33 },\n"
                + "    { \"name\": \"个人故事\", \"value\": 25 },\n"
                + "    { \"name\": \"热点解读\", \"value\": 21 },\n"
                + "    { \"name\": \"清单盘点\", \"value\": 13 },\n"
                + "    { \"name\": \"观点输出\", \"value\": 8 }\n"
                + "  ]\n"
                + "}\n"
                + "提示：该JSON可直接用于前端图表组件（如ECharts）渲染趋势折线图和类型分布饼图。";
    }
}
