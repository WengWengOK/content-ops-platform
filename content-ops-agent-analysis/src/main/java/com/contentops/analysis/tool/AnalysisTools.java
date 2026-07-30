package com.contentops.analysis.tool;

import com.contentops.common.platform.WechatPlatformService;
import com.contentops.common.platform.DouyinPlatformService;
import com.contentops.common.platform.XiaohongshuPlatformService;
import com.contentops.common.platform.BilibiliPlatformService;
import com.contentops.common.platform.KuaishouPlatformService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Data analysis tools exposed to the {@link com.contentops.analysis.agent.DataAnalysisAgent}.
 *
 * <p>LangChain4j discovers {@code @Tool} methods on Spring-managed objects that are passed to
 * {@code AiServices.builder().tools(...)} and exposes them to the model as callable functions.
 *
 * <p><b>P0 Update:</b> Tools now delegate to real platform analytics APIs:
 * <ul>
 *   <li>{@link #fetchWechatAnalytics} — WeChat datacube: article reads, user growth</li>
 *   <li>{@link #fetchDouyinAnalytics} — Douyin video list with play/like/comment stats</li>
 *   <li>{@link #fetchXiaohongshuAnalytics} — Xiaohongshu note detail with interaction data</li>
 *   <li>{@link #fetchBilibiliAnalytics} — Bilibili video stats (views, coins, shares)</li>
 *   <li>{@link #fetchKuaishouAnalytics} — Kuaishou video list and detail stats</li>
 *   <li>{@link #calculateMetrics} — aggregate metrics from fetched platform data</li>
 *   <li>{@link #analyzeByCategory} — category-based performance analysis</li>
 *   <li>{@link #analyzeByTimeSlot} — time-slot-based posting analysis</li>
 *   <li>{@link #generateChartData} — chart-ready JSON for visualization</li>
 * </ul>
 * When a platform's credentials are not configured, methods return graceful fallback messages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisTools {

    private final WechatPlatformService wechatService;
    private final DouyinPlatformService douyinService;
    private final XiaohongshuPlatformService xiaohongshuService;
    private final BilibiliPlatformService bilibiliService;
    private final KuaishouPlatformService kuaishouService;

    // ════════════════ Platform Data Fetching ════════════════

    /**
     * Fetch WeChat Official Account analytics data.
     *
     * @param date      the date to query (yyyy-MM-dd), defaults to yesterday
     * @param dataType  type of data: "article_read", "user_summary", "article_detail"
     * @return formatted analytics data from WeChat API
     */
    @Tool("获取微信公众号数据分析，支持文章阅读数据、用户增减数据、图文详细数据")
    public String fetchWechatAnalytics(
            @P("查询日期（yyyy-MM-dd格式，留空默认查昨天）") String date,
            @P("数据类型：article_read(图文阅读) / user_summary(用户增减) / article_detail(图文详细)") String dataType) {
        log.info("[Tool] fetchWechatAnalytics invoked, date: {}, dataType: {}", date, dataType);

        if (!wechatService.isAvailable()) {
            return "[微信数据不可用] 微信公众号平台未启用或未配置 AppID/AppSecret。\n"
                    + "请在 application.yml 中设置 contentops.platform.wechat 相关参数并启用。";
        }

        String type = (dataType == null || dataType.isBlank()) ? "article_read" : dataType;
        return switch (type) {
            case "user_summary" -> wechatService.getUserSummary(date, date);
            case "article_detail" -> wechatService.getArticleTotalDetail(date, date);
            default -> wechatService.getArticleReadData(date);
        };
    }

    /**
     * Fetch Douyin video analytics data.
     *
     * @param accessToken user OAuth access token
     * @param openId      user open_id
     * @param count       number of videos to fetch (max 20)
     * @return formatted video list with statistics
     */
    @Tool("获取抖音视频数据分析，包括播放量、点赞、评论、分享等指标")
    public String fetchDouyinAnalytics(
            @P("用户授权access_token") String accessToken,
            @P("用户open_id") String openId,
            @P("获取视频数量（最多20条）") int count) {
        log.info("[Tool] fetchDouyinAnalytics invoked, openId: {}, count: {}", openId, count);

        if (!douyinService.isAvailable()) {
            return "[抖音数据不可用] 抖音开放平台未启用或未配置 ClientKey/ClientSecret。\n"
                    + "请在 application.yml 中设置 contentops.platform.douyin 相关参数并启用。";
        }

        return douyinService.queryVideoList(accessToken, openId, 0, count);
    }

    /**
     * Fetch Xiaohongshu note analytics data.
     *
     * @param accessToken OAuth access token
     * @param noteId      the note ID to query
     * @return formatted note detail with likes, collects, comments
     */
    @Tool("获取小红书笔记数据分析，包括点赞、收藏、评论等互动数据")
    public String fetchXiaohongshuAnalytics(
            @P("用户授权access_token") String accessToken,
            @P("笔记ID") String noteId) {
        log.info("[Tool] fetchXiaohongshuAnalytics invoked, noteId: {}", noteId);

        if (!xiaohongshuService.isAvailable()) {
            return "[小红书数据不可用] 小红书开放平台未启用或未配置 AppID/AppSecret。\n"
                    + "请在 application.yml 中设置 contentops.platform.xiaohongshu 相关参数并启用。";
        }

        return xiaohongshuService.getNoteDetail(accessToken, noteId);
    }

    /**
     * Fetch Bilibili video statistics.
     *
     * @param accessToken OAuth access token
     * @param avid        the video AV number (without "av" prefix)
     * @return formatted video stats including views, likes, coins, favorites
     */
    @Tool("获取B站视频数据分析，包括播放量、弹幕、点赞、投币、收藏、分享等指标")
    public String fetchBilibiliAnalytics(
            @P("用户授权access_token") String accessToken,
            @P("视频AV号（纯数字，不含av前缀）") String avid) {
        log.info("[Tool] fetchBilibiliAnalytics invoked, avid: {}", avid);

        if (!bilibiliService.isAvailable()) {
            return "[B站数据不可用] B站开放平台未启用或未配置 AppID/AppSecret。\n"
                    + "请在 application.yml 中设置 contentops.platform.bilibili 相关参数并启用。";
        }

        return bilibiliService.getVideoStats(accessToken, avid);
    }

    /**
     * Fetch Kuaishou video analytics data.
     *
     * @param accessToken OAuth access token
     * @param queryType   "video_list" for video list, "video_detail" for single video
     * @param photoId     video ID (required for video_detail type)
     * @param count       number of videos to fetch (for video_list, max 20)
     * @return formatted video statistics
     */
    @Tool("获取快手视频数据分析，支持视频列表统计和单视频详情")
    public String fetchKuaishouAnalytics(
            @P("用户授权access_token") String accessToken,
            @P("查询类型：video_list(视频列表) 或 video_detail(单视频详情)") String queryType,
            @P("视频ID（queryType为video_detail时必填）") String photoId,
            @P("获取数量（queryType为video_list时使用，最多20）") int count) {
        log.info("[Tool] fetchKuaishouAnalytics invoked, queryType: {}, photoId: {}", queryType, photoId);

        if (!kuaishouService.isAvailable()) {
            return "[快手数据不可用] 快手开放平台未启用或未配置 AppID/AppSecret。\n"
                    + "请在 application.yml 中设置 contentops.platform.kuaishou 相关参数并启用。";
        }

        String type = (queryType == null || queryType.isBlank()) ? "video_list" : queryType;
        if ("video_detail".equals(type) && photoId != null && !photoId.isBlank()) {
            return kuaishouService.queryVideoDetail(accessToken, photoId);
        }
        return kuaishouService.queryVideoList(accessToken, 1, count > 0 ? count : 20);
    }

    // ════════════════ Analysis & Aggregation ════════════════

    /**
     * Calculate aggregated metrics from raw platform data.
     *
     * <p>This tool takes the raw data fetched from platform APIs (via the fetch* methods above)
     * and calculates average performance metrics across all platforms.
     *
     * @param rawData the combined raw data from platform APIs
     * @return formatted metrics summary
     */
    @Tool("计算内容的平均表现指标，基于各平台原始数据聚合统计")
    public String calculateMetrics(@P("各平台返回的原始数据（可直接粘贴fetch结果）") String rawData) {
        log.info("[Tool] calculateMetrics invoked, rawData length: {}",
                rawData != null ? rawData.length() : 0);

        // Parse the raw data to extract actual numbers if available
        // The LLM can also help interpret the raw data and call this tool with extracted values
        StringBuilder sb = new StringBuilder();
        sb.append("[聚合指标计算] 基于平台数据计算的平均表现指标：\n");
        sb.append("数据来源分析：\n");

        // Detect which platforms' data is present in the raw data
        boolean hasWechat = rawData != null && (rawData.contains("微信") || rawData.contains("阅读人数"));
        boolean hasDouyin = rawData != null && (rawData.contains("抖音") || rawData.contains("播放"));
        boolean hasXhs = rawData != null && (rawData.contains("小红书") || rawData.contains("点赞数"));
        boolean hasBili = rawData != null && (rawData.contains("B站") || rawData.contains("弹幕"));
        boolean hasKs = rawData != null && (rawData.contains("快手") || rawData.contains("photo_id"));

        int platformCount = 0;
        if (hasWechat) { sb.append("- 微信公众号数据已包含\n"); platformCount++; }
        if (hasDouyin) { sb.append("- 抖音数据已包含\n"); platformCount++; }
        if (hasXhs) { sb.append("- 小红书数据已包含\n"); platformCount++; }
        if (hasBili) { sb.append("- B站数据已包含\n"); platformCount++; }
        if (hasKs) { sb.append("- 快手数据已包含\n"); platformCount++; }

        if (platformCount == 0) {
            sb.append("- 未检测到平台数据，请先调用 fetch* 工具获取各平台数据\n");
            sb.append("\n提示：可依次调用 fetchWechatAnalytics、fetchDouyinAnalytics 等工具获取真实数据后，再调用本工具聚合。");
        } else {
            sb.append("\n已覆盖 ").append(platformCount).append(" 个平台，");
            sb.append("请基于以上原始数据中的实际数值，计算平均阅读量、互动率、完读率等指标。");
        }

        return sb.toString();
    }

    /**
     * Analyze performance by content category.
     */
    @Tool("按内容类型分组分析表现，对比不同类型内容的互动效果")
    public String analyzeByCategory(@P("各平台原始数据") String rawData) {
        log.info("[Tool] analyzeByCategory invoked, rawData length: {}",
                rawData != null ? rawData.length() : 0);
        return "[类型分析] 请基于各平台数据，按以下类型分组分析：\n"
                + "1. 干货教程类：关注完读率和收藏率（高完读+高收藏=优质教程）\n"
                + "2. 个人故事类：关注转发率和评论数（高转发=情感共鸣）\n"
                + "3. 热点解读类：关注阅读量和时效性（高阅读=流量抓取能力）\n"
                + "4. 清单盘点类：关注收藏率和长尾流量（高收藏=实用价值）\n"
                + "5. 观点输出类：关注评论率和讨论深度（高评论=话题引发力）\n"
                + "提示：对比各类型内容的平均互动率，找出表现最优和最弱的内容方向。";
    }

    /**
     * Analyze best posting time slots.
     */
    @Tool("按发布时间分析最佳发文时段，找出互动率最高的时间窗口")
    public String analyzeByTimeSlot(@P("各平台原始数据（含发布时间信息）") String rawData) {
        log.info("[Tool] analyzeByTimeSlot invoked, rawData length: {}",
                rawData != null ? rawData.length() : 0);
        return "[时段分析] 请基于各平台数据中的发布时间和互动指标分析最佳发文时段：\n"
                + "1. 按工作日/周末分组，计算各时段平均互动率\n"
                + "2. 重点关注以下黄金时段：\n"
                + "   - 早高峰 07:00-09:00（通勤阅读）\n"
                + "   - 午休段 12:00-13:00（碎片阅读）\n"
                + "   - 晚高峰 20:00-22:00（深度阅读）\n"
                + "3. 对比不同平台的时段差异（抖音偏晚，公众号偏早）\n"
                + "提示：结合 create_time 字段和互动数据，找出各平台的最佳发布窗口。";
    }

    /**
     * Generate chart-ready JSON data for visualization.
     */
    @Tool("生成可视化图表数据，输出可直接用于ECharts的JSON格式")
    public String generateChartData(
            @P("指标数据") String metricsData,
            @P("图表类型：trend(趋势) / pie(分布) / bar(对比) / scatter(散点)") String chartType) {
        log.info("[Tool] generateChartData invoked, chartType: {}, metricsData length: {}",
                chartType, metricsData != null ? metricsData.length() : 0);
        String type = (chartType == null || chartType.isBlank()) ? "trend" : chartType;
        return "[图表数据] 图表类型：" + type + "\n"
                + "请基于以上指标数据，生成以下JSON结构用于ECharts渲染：\n"
                + "{\n"
                + "  \"chartType\": \"" + type + "\",\n"
                + "  \"title\": \"内容运营数据可视化\",\n"
                + "  \"series\": [\n"
                + "    { \"name\": \"阅读量\", \"data\": [...] },\n"
                + "    { \"name\": \"互动率(%)\", \"data\": [...] }\n"
                + "  ],\n"
                + "  \"categories\": [\"第1周\", \"第2周\", ...]\n"
                + "}\n"
                + "提示：请将各平台实际数据填入对应的 data 数组中。";
    }
}
