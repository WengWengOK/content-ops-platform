package com.contentops.analysis.tool;

import com.contentops.common.platform.MetricsParser;
import com.contentops.common.platform.MetricsParser.ParsedMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 独立的指标计算工具类，供 {@link AnalysisTools} 及其他模块复用。
 *
 * <p>本类与 {@link MetricsParser} 配合：先由 {@code MetricsParser.parse} 从平台原始文本中
 * 解析出真实数值，再由本类完成聚合、分类、时段分组与 ECharts JSON 生成等精确计算。
 *
 * <p>改造背景：原先 {@code AnalysisTools} 的四个分析方法只返回"请基于以上数据计算"的
 * 框架性引导文本，由 LLM 自行推理；现改为程序化精确计算，与 {@code OptimizeTools} 使用
 * {@code MetricsParser} 做真实数值解析的架构保持一致。
 *
 * <p>所有正则均预编译为 {@code static final} 以提升性能。
 *
 * <p>降级策略：当输入文本无法解析出任何有效数值时，返回明确的"未检测到数据"提示，
 * 而非空结果或框架引导文本。
 */
@Slf4j
@Component
public class MetricsCalculator {

    /** 内容块标题行的正则（支持全角/半角冒号），如 "- 标题: xxx" 或 "- 标题：xxx"。 */
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("(?m)^-\\s*标题\\s*[：:]\\s*(.+)");

    /** 完整日期时间正则，用于提取小时，如 "2024-01-15 21:30:00" 或 "2024/01/15T21:30"。 */
    private static final Pattern DATETIME_PATTERN =
            Pattern.compile("(\\d{4})[-/](\\d{2})[-/](\\d{2})[ T](\\d{2}):(\\d{2})");

    /** 创建时间标签正则，匹配 "创建时间: xxx" 或 "create_time: xxx"。 */
    private static final Pattern CREATE_TIME_PATTERN =
            Pattern.compile("(?:创建时间|create_time)\\s*[：:]\\s*(\\S+)");

    /** 纯数字（9位以上）判断，用于识别 Unix 时间戳。 */
    private static final Pattern EPOCH_PATTERN = Pattern.compile("^\\d{9,}$");

    /** 中国时区，用于把 Unix 时间戳转换为本地小时。 */
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final MetricsParser metricsParser;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数（Spring 自动注入）。
     *
     * @param metricsParser 平台指标解析器
     * @param objectMapper  JSON 序列化器，用于生成 ECharts JSON
     */
    public MetricsCalculator(MetricsParser metricsParser, ObjectMapper objectMapper) {
        this.metricsParser = metricsParser;
        this.objectMapper = objectMapper;
    }

    // ════════════════ 内容分类关键词 ════════════════

    /** 内容类型 → 关键词数组（保持插入顺序，决定分类匹配优先级）。 */
    private static final LinkedHashMap<String, String[]> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        CATEGORY_KEYWORDS.put("干货教程", new String[]{"教程", "指南", "方法", "技巧", "如何", "步骤", "攻略", "干货"});
        CATEGORY_KEYWORDS.put("个人故事", new String[]{"经历", "故事", "我的", "回忆", "成长", "转变", "真实"});
        CATEGORY_KEYWORDS.put("热点解读", new String[]{"热点", "最新", "刚刚", "突发", "解读", "分析", "事件"});
        CATEGORY_KEYWORDS.put("清单盘点", new String[]{"盘点", "清单", "推荐", "合集", "TOP", "排名", "精选"});
        CATEGORY_KEYWORDS.put("观点输出", new String[]{"观点", "认为", "应该", "为什么", "思考", "看法", "反思"});
    }

    // ════════════════ 核心计算方法 ════════════════

    /**
     * 聚合指标计算：使用 {@link MetricsParser#parse} 解析真实数值，返回跨平台聚合指标。
     *
     * <p>计算口径：
     * <ul>
     *   <li>总阅读量 / 总播放量：直接取解析值</li>
     *   <li>总互动量 = 点赞 + 评论 + 分享 + 收藏</li>
     *   <li>平均互动率 = 总互动量 / max(总阅读量, 总播放量)</li>
     *   <li>完读率：优先用解析的阅读完成率，缺失时用收藏率估算</li>
     *   <li>粉丝净增长：取解析的净增粉丝</li>
     *   <li>环比增速：优先用解析的环比，缺失时用 净增/新增 估算</li>
     * </ul>
     *
     * @param rawData 各平台返回的原始数据文本
     * @return 聚合指标 {@link AggregatedMetrics}；无有效数据时 {@code hasData=false}
     */
    public AggregatedMetrics calculateAggregatedMetrics(String rawData) {
        if (rawData == null || rawData.isBlank()) {
            return new AggregatedMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, List.of(), false);
        }

        ParsedMetrics m = metricsParser.parse(rawData);

        long totalReads = m.readCount();
        long totalPlays = m.playCount();
        long totalLikes = m.likes();
        long totalComments = m.commentCount();
        long totalShares = m.shareCount();
        long totalCollects = m.collectCount();
        long totalEngagement = totalLikes + totalComments + totalShares + totalCollects;

        long base = Math.max(totalReads, totalPlays);
        double avgEngagementRate;
        if (base > 0) {
            avgEngagementRate = (double) totalEngagement / base;
        } else {
            // 触达量缺失时回退到文本中直接解析的互动率
            avgEngagementRate = m.engagementRate();
        }

        // 完读率：有则用，无则用收藏率估算
        double readFinishRate = metricsParser.getReadFinishRate(m);

        long netGrowth = m.netGrowth();
        double growthRate = m.growthRate() != 0 ? m.growthRate() : metricsParser.computeGrowthRate(m);

        List<String> platforms = detectPlatforms(rawData);

        boolean hasData = m.hasData();

        log.debug("[MetricsCalculator] 聚合指标计算完成: reads={}, plays={}, engagement={}, rate={}/{}, hasData={}",
                totalReads, totalPlays, totalEngagement, avgEngagementRate, platforms.size(), hasData);

        return new AggregatedMetrics(
                totalReads, totalPlays, totalLikes, totalComments, totalShares, totalCollects,
                totalEngagement, avgEngagementRate, readFinishRate, netGrowth, growthRate,
                platforms.size(), platforms, hasData);
    }

    /**
     * 按内容类型分类计算：从文本中提取标题，按关键词分类（干货/故事/热点/清单/观点），
     * 对每类内容计算平均互动率，输出量化对比。
     *
     * @param rawData 各平台返回的原始数据文本
     * @return 各内容类型的表现列表（按平均互动率降序排序后填充 performanceRank）
     */
    public List<CategoryPerformance> categorizeContent(String rawData) {
        List<CategoryPerformance> result = new ArrayList<>();
        if (rawData == null || rawData.isBlank()) {
            return result;
        }

        List<ContentBlock> blocks = splitIntoContentBlocks(rawData);
        if (blocks.isEmpty()) {
            log.debug("[MetricsCalculator] 分类分析：未提取到内容标题块");
            return result;
        }

        // 按内容类型分组累积统计
        Map<String, List<ItemStat>> buckets = new LinkedHashMap<>();
        for (String cat : CATEGORY_KEYWORDS.keySet()) {
            buckets.put(cat, new ArrayList<>());
        }
        buckets.put("其他", new ArrayList<>());

        for (ContentBlock block : blocks) {
            String category = matchCategory(block.title);
            ParsedMetrics pm = metricsParser.parse(block.text);
            long read = Math.max(pm.readCount(), pm.playCount());
            long engagement = pm.likes() + pm.commentCount() + pm.shareCount() + pm.collectCount();
            double rate = read > 0 ? (double) engagement / read : 0.0;
            buckets.get(category).add(new ItemStat(read, engagement, rate));
        }

        // 计算每类的平均指标
        for (Map.Entry<String, List<ItemStat>> entry : buckets.entrySet()) {
            List<ItemStat> stats = entry.getValue();
            if (stats.isEmpty()) {
                continue;
            }
            int count = stats.size();
            double sumRate = 0, sumRead = 0;
            for (ItemStat s : stats) {
                sumRate += s.rate;
                sumRead += s.read;
            }
            double avgRate = sumRate / count;
            long avgRead = Math.round((double) sumRead / count);
            String summary = String.format("共%d篇，平均互动率%.2f%%，平均触达%,d",
                    count, avgRate * 100, avgRead);
            result.add(new CategoryPerformance(entry.getKey(), count, avgRate, avgRead, 0, summary));
        }

        // 按平均互动率降序排序并填充名次
        result.sort((a, b) -> Double.compare(b.avgEngagementRate(), a.avgEngagementRate()));
        List<CategoryPerformance> ranked = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            CategoryPerformance c = result.get(i);
            ranked.add(new CategoryPerformance(c.contentType(), c.contentCount(),
                    c.avgEngagementRate(), c.avgReadCount(), i + 1, c.summary()));
        }
        return ranked;
    }

    /**
     * 按时段分组分析：从文本中提取时间信息（正则匹配"发布时间"、"create_time"、
     * "创建时间"及日期时间格式），按时段分组（早高峰/午休/晚高峰/其他），
     * 计算各时段平均互动率，输出最佳时段排名。
     *
     * @param rawData 各平台返回的原始数据文本（含发布时间信息）
     * @return 各时段的表现列表（按平均互动率降序排序）
     */
    public List<TimeSlotPerformance> analyzeTimeSlots(String rawData) {
        List<TimeSlotPerformance> result = new ArrayList<>();
        if (rawData == null || rawData.isBlank()) {
            return result;
        }

        List<ContentBlock> blocks = splitIntoContentBlocks(rawData);
        if (blocks.isEmpty()) {
            log.debug("[MetricsCalculator] 时段分析：未提取到内容标题块");
            return result;
        }

        // 按时段分组累积统计
        Map<String, List<ItemStat>> buckets = new LinkedHashMap<>();
        buckets.put("早高峰(07:00-09:00)", new ArrayList<>());
        buckets.put("午休(12:00-13:00)", new ArrayList<>());
        buckets.put("晚高峰(20:00-22:00)", new ArrayList<>());
        buckets.put("其他时段", new ArrayList<>());

        for (ContentBlock block : blocks) {
            Integer hour = extractHour(block.text);
            String slot = classifyHour(hour);
            ParsedMetrics pm = metricsParser.parse(block.text);
            long read = Math.max(pm.readCount(), pm.playCount());
            long engagement = pm.likes() + pm.commentCount() + pm.shareCount() + pm.collectCount();
            double rate = read > 0 ? (double) engagement / read : 0.0;
            buckets.get(slot).add(new ItemStat(read, engagement, rate));
        }

        // 计算整体平均互动率，用于生成建议
        double overallRate = buckets.values().stream()
                .flatMap(List::stream)
                .mapToDouble(s -> s.rate)
                .average()
                .orElse(0.0);

        // 计算每个时段的平均指标
        for (Map.Entry<String, List<ItemStat>> entry : buckets.entrySet()) {
            List<ItemStat> stats = entry.getValue();
            if (stats.isEmpty()) {
                continue;
            }
            int count = stats.size();
            double sumRate = 0, sumRead = 0;
            for (ItemStat s : stats) {
                sumRate += s.rate;
                sumRead += s.read;
            }
            double avgRate = sumRate / count;
            long avgRead = Math.round((double) sumRead / count);
            boolean goldenHour = isGoldenHour(entry.getKey());
            String recommendation = buildSlotRecommendation(entry.getKey(), avgRate, overallRate, goldenHour);
            result.add(new TimeSlotPerformance(entry.getKey(), count, avgRate, avgRead,
                    goldenHour, recommendation));
        }

        // 按平均互动率降序排序，找出最佳时段
        result.sort((a, b) -> Double.compare(b.avgEngagementRate(), a.avgEngagementRate()));
        return result;
    }

    /**
     * 将原始数据按 "- 标题:" 切分为多个内容条目，逐条解析为 {@link ParsedMetrics}。
     *
     * <p>供 {@link AnalysisTools} 及其他模块复用，消除重复的标题切分逻辑。
     *
     * @param rawData 原始数据文本
     * @return 每条内容对应的解析指标列表；无标题块时返回空列表
     */
    public List<ParsedMetrics> parsePerItemMetrics(String rawData) {
        List<ParsedMetrics> list = new ArrayList<>();
        if (rawData == null || rawData.isBlank()) {
            return list;
        }
        List<ContentBlock> blocks = splitIntoContentBlocks(rawData);
        for (ContentBlock block : blocks) {
            list.add(metricsParser.parse(block.text()));
        }
        return list;
    }

    /**
     * 保留两位小数（公共工具方法，供 {@link AnalysisTools} 等复用）。
     *
     * @param value 原始值
     * @return 保留两位小数后的值
     */
    public static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * 计算含收藏的总互动量（赞+评+转+收藏），与 {@link AggregatedMetrics#totalEngagement} 口径一致。
     *
     * @param m 解析指标
     * @return 含收藏的总互动量
     */
    private static long fullEngagement(ParsedMetrics m) {
        return m.totalEngagement() + m.collectCount();
    }

    /**
     * 生成趋势图 ECharts JSON：基于多期指标列表生成折线趋势图。
     *
     * <p>互动量口径与 {@link AggregatedMetrics#totalEngagement} 一致：赞+评+转+收藏。
     *
     * @param weeklyMetrics 多期（如每周）的解析指标列表
     * @return 可直接用于 ECharts 渲染的 JSON 字符串；空数据时返回提示 JSON
     */
    public String generateTrendChart(List<ParsedMetrics> weeklyMetrics) {
        if (weeklyMetrics == null || weeklyMetrics.isEmpty()) {
            return buildEmptyChartJson("trend", "趋势图生成失败：未提供任何期次数据");
        }

        List<String> xData = new ArrayList<>();
        List<Object> reachSeries = new ArrayList<>();
        List<Object> engagementSeries = new ArrayList<>();
        List<Object> rateSeries = new ArrayList<>();

        for (int i = 0; i < weeklyMetrics.size(); i++) {
            ParsedMetrics m = weeklyMetrics.get(i);
            xData.add("第" + (i + 1) + "期");
            reachSeries.add(Math.max(m.readCount(), m.playCount()));
            engagementSeries.add(fullEngagement(m));
            rateSeries.add(round2(metricsParser.computeEngagementRate(m) * 100));
        }

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartType", "trend");
        chart.put("title", Map.of("text", "内容数据趋势图"));
        chart.put("tooltip", Map.of("trigger", "axis"));
        chart.put("legend", Map.of("data", List.of("触达量", "互动量", "互动率(%)")));
        chart.put("xAxis", Map.of("type", "category", "data", xData));
        chart.put("yAxis", List.of(
                Map.of("type", "value", "name", "数量"),
                Map.of("type", "value", "name", "互动率(%)")));

        List<Map<String, Object>> series = new ArrayList<>();
        series.add(seriesMap("触达量", "line", reachSeries, 0));
        series.add(seriesMap("互动量", "line", engagementSeries, 0));
        series.add(seriesMap("互动率(%)", "line", rateSeries, 1));
        chart.put("series", series);

        return writeJson(chart);
    }

    /**
     * 生成分布图 ECharts JSON：基于名称-占比映射生成饼图。
     *
     * @param distribution 名称到数值的映射（如各互动类型占比）
     * @param title         图表标题
     * @return 可直接用于 ECharts 渲染的 JSON 字符串；空数据时返回提示 JSON
     */
    public String generateDistributionChart(Map<String, Double> distribution, String title) {
        if (distribution == null || distribution.isEmpty()) {
            return buildEmptyChartJson("pie", "分布图生成失败：未提供任何分布数据");
        }

        List<Map<String, Object>> data = new ArrayList<>();
        for (Map.Entry<String, Double> e : distribution.entrySet()) {
            data.add(Map.of("name", e.getKey(), "value", round2(e.getValue())));
        }

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartType", "pie");
        chart.put("title", Map.of("text", title != null ? title : "数据分布"));
        chart.put("tooltip", Map.of("trigger", "item", "formatter", "{b}: {c} ({d}%)"));
        chart.put("series", List.of(Map.of(
                "name", title != null ? title : "数据分布",
                "type", "pie",
                "radius", "60%",
                "data", data)));

        return writeJson(chart);
    }

    /**
     * 生成对比图 ECharts JSON：基于内容类型表现列表生成柱状对比图。
     *
     * @param categories 内容类型表现列表（来自 {@link #categorizeContent}）
     * @return 可直接用于 ECharts 渲染的 JSON 字符串；空数据时返回提示 JSON
     */
    public String generateComparisonChart(List<CategoryPerformance> categories) {
        if (categories == null || categories.isEmpty()) {
            return buildEmptyChartJson("bar", "对比图生成失败：未提供任何分类数据");
        }

        List<String> cats = new ArrayList<>();
        List<Object> rates = new ArrayList<>();
        List<Object> counts = new ArrayList<>();
        for (CategoryPerformance c : categories) {
            cats.add(c.contentType());
            rates.add(round2(c.avgEngagementRate() * 100));
            counts.add(c.contentCount());
        }

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartType", "bar");
        chart.put("title", Map.of("text", "内容类型互动率对比"));
        chart.put("tooltip", Map.of("trigger", "axis"));
        chart.put("legend", Map.of("data", List.of("平均互动率(%)", "内容数量")));
        chart.put("xAxis", Map.of("type", "category", "data", cats));
        chart.put("yAxis", List.of(
                Map.of("type", "value", "name", "平均互动率(%)"),
                Map.of("type", "value", "name", "内容数量")));

        List<Map<String, Object>> series = new ArrayList<>();
        series.add(seriesMap("平均互动率(%)", "bar", rates, 0));
        series.add(seriesMap("内容数量", "bar", counts, 1));
        chart.put("series", series);

        return writeJson(chart);
    }

    // ════════════════ 聚合指标 record ════════════════

    /**
     * 聚合指标结果，包含跨平台汇总的真实数值。
     *
     * @param totalReads        总阅读量
     * @param totalPlays        总播放量
     * @param totalLikes        总点赞数
     * @param totalComments     总评论数
     * @param totalShares       总分享/转发数
     * @param totalCollects     总收藏数
     * @param totalEngagement   总互动量（赞+评+转+收藏）
     * @param avgEngagementRate 平均互动率（小数，如 0.061 表示 6.1%）
     * @param readFinishRate    完读率/完播率（小数，缺失时以收藏率估算）
     * @param netGrowth         粉丝净增长
     * @param growthRate        环比增速（小数，可为负）
     * @param platformCount     检测到的平台数量
     * @param platformsDetected 检测到的平台名称列表
     * @param hasData            是否解析到有效数据
     */
    public record AggregatedMetrics(
            long totalReads,
            long totalPlays,
            long totalLikes,
            long totalComments,
            long totalShares,
            long totalCollects,
            long totalEngagement,
            double avgEngagementRate,
            double readFinishRate,
            long netGrowth,
            double growthRate,
            int platformCount,
            List<String> platformsDetected,
            boolean hasData
    ) {
    }

    /**
     * 内容类型表现结果。
     *
     * @param contentType       内容类型名称（干货教程/个人故事/热点解读/清单盘点/观点输出/其他）
     * @param contentCount      该类型内容数量
     * @param avgEngagementRate 平均互动率（小数）
     * @param avgReadCount      平均触达量
     * @param performanceRank   表现排名（1=最佳，按平均互动率降序）
     * @param summary           量化摘要文本
     */
    public record CategoryPerformance(
            String contentType,
            int contentCount,
            double avgEngagementRate,
            long avgReadCount,
            int performanceRank,
            String summary
    ) {
    }

    /**
     * 时段表现结果。
     *
     * @param timeSlot          时段名称（早高峰/午休/晚高峰/其他时段）
     * @param contentCount      该时段内容数量
     * @param avgEngagementRate 平均互动率（小数）
     * @param avgReadCount      平均触达量
     * @param isGoldenHour      是否为黄金时段
     * @param recommendation    发布建议文本
     */
    public record TimeSlotPerformance(
            String timeSlot,
            int contentCount,
            double avgEngagementRate,
            long avgReadCount,
            boolean isGoldenHour,
            String recommendation
    ) {
    }

    // ════════════════ 私有辅助 ════════════════

    /** 单条内容统计中间结果。 */
    private record ItemStat(long read, long engagement, double rate) {
    }

    /** 内容块：标题 + 该块对应的正文文本。 */
    private record ContentBlock(String title, String text) {
    }

    /**
     * 将原始文本按 "- 标题:" 标记切分为多个内容块。
     * 每个块的文本包含其下方的统计信息（阅读/播放/点赞/评论/时间等）。
     */
    private List<ContentBlock> splitIntoContentBlocks(String rawData) {
        List<ContentBlock> blocks = new ArrayList<>();
        Matcher m = TITLE_PATTERN.matcher(rawData);
        int lastEnd = -1;
        String lastTitle = null;
        while (m.find()) {
            if (lastTitle != null) {
                String blockText = rawData.substring(lastEnd, m.start());
                blocks.add(new ContentBlock(lastTitle, blockText));
            }
            lastTitle = m.group(1).trim();
            lastEnd = m.end();
        }
        if (lastTitle != null) {
            String blockText = rawData.substring(lastEnd);
            blocks.add(new ContentBlock(lastTitle, blockText));
        }
        return blocks;
    }

    /**
     * 根据标题匹配内容类型关键词，返回首个命中的类型，否则返回"其他"。
     */
    private String matchCategory(String title) {
        if (title == null || title.isBlank()) {
            return "其他";
        }
        String lower = title.toLowerCase();
        for (Map.Entry<String, String[]> entry : CATEGORY_KEYWORDS.entrySet()) {
            for (String kw : entry.getValue()) {
                if (lower.contains(kw.toLowerCase())) {
                    return entry.getKey();
                }
            }
        }
        return "其他";
    }

    /**
     * 从块文本中提取发布小时。
     * 优先匹配完整日期时间格式，其次匹配"创建时间/create_time"的 Unix 时间戳。
     *
     * @return 小时（0-23），无法提取时返回 null
     */
    private Integer extractHour(String blockText) {
        if (blockText == null || blockText.isBlank()) {
            return null;
        }

        // 1. 优先匹配完整日期时间（覆盖"发布时间: 2024-01-15 21:30:00"等情况）
        Matcher dt = DATETIME_PATTERN.matcher(blockText);
        if (dt.find()) {
            try {
                return Integer.parseInt(dt.group(4));
            } catch (NumberFormatException ignored) {
                // 继续尝试其他方式
            }
        }

        // 2. 匹配"创建时间/create_time"的值，可能是 Unix 时间戳
        Matcher ct = CREATE_TIME_PATTERN.matcher(blockText);
        if (ct.find()) {
            String val = ct.group(1);
            if (EPOCH_PATTERN.matcher(val).matches()) {
                try {
                    return Instant.ofEpochSecond(Long.parseLong(val))
                            .atZone(CHINA_ZONE)
                            .getHour();
                } catch (NumberFormatException ignored) {
                    // 时间戳解析失败
                }
            } else {
                // 值本身可能是日期时间字符串
                Matcher dt2 = DATETIME_PATTERN.matcher(val);
                if (dt2.find()) {
                    try {
                        return Integer.parseInt(dt2.group(4));
                    } catch (NumberFormatException ignored) {
                        // 忽略
                    }
                }
            }
        }
        return null;
    }

    /**
     * 将小时归类到时段。无法提取时间（hour=null）归入"其他时段"。
     */
    private String classifyHour(Integer hour) {
        if (hour == null) {
            return "其他时段";
        }
        if (hour >= 7 && hour < 9) {
            return "早高峰(07:00-09:00)";
        }
        if (hour == 12) {
            return "午休(12:00-13:00)";
        }
        if (hour >= 20 && hour < 22) {
            return "晚高峰(20:00-22:00)";
        }
        return "其他时段";
    }

    /**
     * 判断时段是否为黄金时段。
     */
    private boolean isGoldenHour(String slot) {
        return slot != null && !slot.equals("其他时段");
    }

    /**
     * 根据时段互动率与整体均值对比，生成发布建议。
     */
    private String buildSlotRecommendation(String slot, double avgRate, double overallRate, boolean goldenHour) {
        if (avgRate <= 0) {
            return "该时段无有效互动数据，建议补充数据后再评估。";
        }
        StringBuilder sb = new StringBuilder();
        if (avgRate >= overallRate && overallRate > 0) {
            sb.append(String.format("互动率%.2f%%高于整体均值%.2f%%，", avgRate * 100, overallRate * 100));
            sb.append(goldenHour ? "为表现优异的黄金时段，建议固定该窗口发文。" : "非黄金时段但表现突出，值得关注。");
        } else {
            sb.append(String.format("互动率%.2f%%低于整体均值%.2f%%，", avgRate * 100, overallRate * 100));
            sb.append(goldenHour ? "黄金时段表现未达预期，建议优化内容质量或调整选题。" : "非黄金时段且表现一般，建议避开该窗口。");
        }
        return sb.toString();
    }

    /**
     * 检测原始数据中包含哪些平台的数据。
     */
    private List<String> detectPlatforms(String rawData) {
        List<String> platforms = new ArrayList<>();
        if (rawData == null) {
            return platforms;
        }
        if (rawData.contains("微信") || rawData.contains("阅读人数")) {
            platforms.add("微信公众号");
        }
        if (rawData.contains("抖音")) {
            platforms.add("抖音");
        }
        if (rawData.contains("小红书")) {
            platforms.add("小红书");
        }
        if (rawData.contains("B站") || rawData.contains("弹幕")) {
            platforms.add("B站");
        }
        if (rawData.contains("快手") || rawData.contains("photo_id")) {
            platforms.add("快手");
        }
        return platforms;
    }

    /**
     * 构造 ECharts series 元素。
     */
    private Map<String, Object> seriesMap(String name, String type, List<Object> data, int yAxisIndex) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", type);
        map.put("data", data);
        map.put("yAxisIndex", yAxisIndex);
        return map;
    }

    /**
     * 生成空数据时的提示 JSON。
     */
    private String buildEmptyChartJson(String chartType, String message) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartType", chartType);
        chart.put("status", "no_data");
        chart.put("message", message);
        return writeJson(chart);
    }

    /**
     * 使用 Jackson 序列化为 JSON，失败时返回降级字符串。
     */
    public String serializeJson(Object obj) {
        return writeJson(obj);
    }

    /**
     * 使用 Jackson 序列化为 JSON，失败时返回降级字符串。
     */
    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("[MetricsCalculator] JSON 序列化失败: {}", e.getMessage());
            return "{\"status\":\"error\",\"message\":\"JSON序列化失败: " + e.getMessage() + "\"}";
        }
    }

}
