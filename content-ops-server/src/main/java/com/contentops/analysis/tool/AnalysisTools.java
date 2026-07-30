package com.contentops.analysis.tool;

import com.contentops.common.platform.MetricsParser;
import com.contentops.common.platform.MetricsParser.ParsedMetrics;
import com.contentops.common.platform.WechatPlatformService;
import com.contentops.common.platform.DouyinPlatformService;
import com.contentops.common.platform.XiaohongshuPlatformService;
import com.contentops.common.platform.BilibiliPlatformService;
import com.contentops.common.platform.KuaishouPlatformService;
import com.contentops.common.profile.audience.AudienceProfile;
import com.contentops.common.profile.audience.AudienceProfileService;
import com.contentops.common.profile.audience.ProfileEnricher;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p><b>P1 ⑦ Update:</b> The four analysis methods ({@link #calculateMetrics},
 * {@link #analyzeByCategory}, {@link #analyzeByTimeSlot}, {@link #generateChartData})
 * no longer return framework-guidance text for the LLM to self-reason. They now use
 * {@link MetricsParser} to parse real numerical metrics and delegate to
 * {@link MetricsCalculator} for programmatic precise computation (aggregation,
 * keyword-based categorization, time-slot grouping, and real-data ECharts JSON),
 * achieving architectural consistency with {@code OptimizeTools}.
 *
 * <p><b>P1 ⑧ Update:</b> 消除 AnalysisTools 与 MetricsCalculator 之间的重复代码：
 * 标题切分、逐条解析、数值格式化等逻辑统一委托 MetricsCalculator 公共方法。
 * 新增 {@link #buildAudienceProfile} 工具方法，使 Agent 能在分析流程中触发受众画像构建。
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
    /** 平台指标解析器，从原始文本中提取真实数值。 */
    private final MetricsParser metricsParser;
    /** 独立指标计算工具，完成聚合/分类/时段/图表的精确计算。 */
    private final MetricsCalculator metricsCalculator;
    /** 受众画像服务，供 @Tool 方法构建与获取受众画像。 */
    private final AudienceProfileService audienceProfileService;
    /** 画像摘要生成器，将结构化画像转为人类可读文本。 */
    private final ProfileEnricher profileEnricher;

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
     * <p>使用 {@link MetricsParser#parse} 解析真实数值，并由 {@link MetricsCalculator} 完成
     * 跨平台聚合计算：总阅读/播放量、总互动量（赞+评+转+收藏）、平均互动率、完读率
     * （有则用，无则用收藏率估算）、粉丝净增长、环比增速。
     *
     * <p>返回包含具体数值的结构化计算结果文本，而非"请基于以上数据计算"的框架引导。
     * 降级策略：解析不到数值时返回明确的"未检测到数据"提示。
     *
     * @param rawData the combined raw data from platform APIs
     * @return formatted metrics summary with computed numerical values
     */
    @Tool("计算内容的平均表现指标，基于各平台原始数据聚合统计")
    public String calculateMetrics(@P("各平台返回的原始数据（可直接粘贴fetch结果）") String rawData) {
        log.info("[Tool] calculateMetrics invoked, rawData length: {}",
                rawData != null ? rawData.length() : 0);

        MetricsCalculator.AggregatedMetrics agg =
                metricsCalculator.calculateAggregatedMetrics(rawData);

        StringBuilder sb = new StringBuilder();
        sb.append("[聚合指标计算] 基于平台数据程序化计算的真实聚合指标：\n\n");

        // 数据来源分析
        sb.append("=== 数据来源 ===\n");
        List<String> platforms = agg.platformsDetected();
        if (platforms.isEmpty()) {
            sb.append("- 未检测到平台数据。\n");
        } else {
            for (String p : platforms) {
                sb.append("- ").append(p).append(" 数据已包含\n");
            }
        }
        sb.append(String.format("已覆盖 %d 个平台。\n\n", agg.platformCount()));

        if (!agg.hasData()) {
            sb.append("⚠️ 未检测到数据：未能从输入文本中解析出任何有效指标数值。\n");
            sb.append("请先调用 fetch*Analytics 工具获取各平台数据后，再调用本工具聚合计算。");
            return sb.toString();
        }

        // 真实计算结果
        sb.append("=== 真实计算结果 ===\n");
        if (agg.totalReads() > 0) {
            sb.append(String.format("总阅读量: %,d\n", agg.totalReads()));
        }
        if (agg.totalPlays() > 0) {
            sb.append(String.format("总播放量: %,d\n", agg.totalPlays()));
        }
        sb.append(String.format("总点赞: %,d\n", agg.totalLikes()));
        sb.append(String.format("总评论: %,d\n", agg.totalComments()));
        sb.append(String.format("总分享/转发: %,d\n", agg.totalShares()));
        sb.append(String.format("总收藏: %,d\n", agg.totalCollects()));
        sb.append(String.format("总互动量(赞+评+转+收藏): %,d\n", agg.totalEngagement()));

        long base = Math.max(agg.totalReads(), agg.totalPlays());
        sb.append(String.format("平均互动率: %.2f%% (总互动%,d / 触达%,d)\n",
                agg.avgEngagementRate() * 100, agg.totalEngagement(), base));

        if (agg.readFinishRate() > 0) {
            sb.append(String.format("完读率/完播率: %.1f%%\n", agg.readFinishRate() * 100));
        } else {
            sb.append("完读率/完播率: 数据不足，暂无法计算\n");
        }

        if (agg.netGrowth() != 0) {
            sb.append(String.format("粉丝净增长: %+,d\n", agg.netGrowth()));
        }
        if (agg.growthRate() != 0) {
            sb.append(String.format("环比增速: %+.1f%%\n", agg.growthRate() * 100));
        }

        sb.append("\n=== 结论 ===\n");
        if (agg.avgEngagementRate() > 0) {
            if (agg.avgEngagementRate() >= 0.05) {
                sb.append(String.format("平均互动率 %.2f%% 达到/超过行业基准5%%，整体互动表现良好。",
                        agg.avgEngagementRate() * 100));
            } else {
                sb.append(String.format("平均互动率 %.2f%% 低于行业基准5%%，建议优化内容质量与互动引导。",
                        agg.avgEngagementRate() * 100));
            }
        }
        return sb.toString();
    }

    /**
     * Analyze performance by content category.
     *
     * <p>使用正则从文本中提取标题，按关键词分类（干货/故事/热点/清单/观点），
     * 对每类内容计算平均互动率，输出量化对比表。返回包含真实计算数值的文本，
     * 而非框架引导文本。
     *
     * @param rawData 各平台原始数据
     * @return 量化类型对比分析结果
     */
    @Tool("按内容类型分组分析表现，对比不同类型内容的互动效果")
    public String analyzeByCategory(@P("各平台原始数据") String rawData) {
        log.info("[Tool] analyzeByCategory invoked, rawData length: {}",
                rawData != null ? rawData.length() : 0);

        List<MetricsCalculator.CategoryPerformance> categories =
                metricsCalculator.categorizeContent(rawData);

        StringBuilder sb = new StringBuilder();
        sb.append("[类型分析] 基于真实数据的程序化类型对比分析：\n\n");

        if (categories.isEmpty()) {
            sb.append("⚠️ 未检测到数据：未能从输入文本中提取到内容标题与互动指标。\n");
            sb.append("请先调用 fetch*Analytics 工具获取各平台数据（含“- 标题:”与互动数值）后再分析。");
            return sb.toString();
        }

        sb.append("=== 量化对比表 ===\n");
        sb.append(String.format("%-8s %-6s %-12s %-12s %-6s\n",
                "类型", "数量", "平均互动率", "平均触达", "排名"));
        sb.append("----------------------------------------------------\n");
        for (MetricsCalculator.CategoryPerformance c : categories) {
            sb.append(String.format("%-8s %-6d %-12s %-12s %-6d\n",
                    c.contentType(),
                    c.contentCount(),
                    String.format("%.2f%%", c.avgEngagementRate() * 100),
                    String.format("%,d", c.avgReadCount()),
                    c.performanceRank()));
        }

        // 最佳与最弱类型
        MetricsCalculator.CategoryPerformance best = categories.get(0);
        MetricsCalculator.CategoryPerformance worst = categories.get(categories.size() - 1);
        sb.append("\n=== 结论 ===\n");
        sb.append(String.format("表现最优: %s（平均互动率 %.2f%%，排名第1）\n",
                best.contentType(), best.avgEngagementRate() * 100));
        sb.append(String.format("表现最弱: %s（平均互动率 %.2f%%，排名%d）\n",
                worst.contentType(), worst.avgEngagementRate() * 100,
                worst.performanceRank()));

        if (best.contentType().equals("干货教程") || best.contentType().equals("清单盘点")) {
            sb.append("建议：高表现类型偏实用向，可增加该方向选题占比以提升收藏率与长尾流量。\n");
        } else if (best.contentType().equals("个人故事")) {
            sb.append("建议：高表现类型偏情感共鸣，可强化故事化叙事以提升转发率。\n");
        } else if (best.contentType().equals("热点解读")) {
            sb.append("建议：高表现类型偏流量抓取，可加快热点响应速度以提升阅读量。\n");
        } else if (best.contentType().equals("观点输出")) {
            sb.append("建议：高表现类型偏话题引发，可增加争议性观点以提升评论深度。\n");
        }
        if (categories.size() >= 2) {
            double diff = (best.avgEngagementRate() - worst.avgEngagementRate()) * 100;
            sb.append(String.format("类型间互动率差距: %.2f个百分点，建议向最优类型倾斜资源。\n", diff));
        }
        return sb.toString();
    }

    /**
     * Analyze best posting time slots.
     *
     * <p>从文本中提取时间信息（正则匹配"发布时间"、"create_time"、"创建时间"及日期时间格式），
     * 按时段分组（早高峰/午休/晚高峰/其他），计算各时段平均互动率，输出最佳时段排名。
     * 返回包含真实计算数值的文本，而非框架引导文本。
     *
     * @param rawData 各平台原始数据（含发布时间信息）
     * @return 量化时段分析结果与最佳时段排名
     */
    @Tool("按发布时间分析最佳发文时段，找出互动率最高的时间窗口")
    public String analyzeByTimeSlot(@P("各平台原始数据（含发布时间信息）") String rawData) {
        log.info("[Tool] analyzeByTimeSlot invoked, rawData length: {}",
                rawData != null ? rawData.length() : 0);

        List<MetricsCalculator.TimeSlotPerformance> slots =
                metricsCalculator.analyzeTimeSlots(rawData);

        StringBuilder sb = new StringBuilder();
        sb.append("[时段分析] 基于真实数据的程序化时段对比分析：\n\n");

        if (slots.isEmpty()) {
            sb.append("⚠️ 未检测到数据：未能从输入文本中提取到内容标题与时间信息。\n");
            sb.append("请先调用 fetch*Analytics 工具获取含发布时间的数据后再分析。");
            return sb.toString();
        }

        sb.append("=== 各时段平均互动率排名 ===\n");
        sb.append(String.format("%-22s %-6s %-12s %-12s %-8s\n",
                "时段", "数量", "平均互动率", "平均触达", "黄金时段"));
        sb.append("------------------------------------------------------------\n");
        for (MetricsCalculator.TimeSlotPerformance s : slots) {
            sb.append(String.format("%-22s %-6d %-12s %-12s %-8s\n",
                    s.timeSlot(),
                    s.contentCount(),
                    String.format("%.2f%%", s.avgEngagementRate() * 100),
                    String.format("%,d", s.avgReadCount()),
                    s.isGoldenHour() ? "是" : "否"));
        }

        // 最佳时段
        MetricsCalculator.TimeSlotPerformance best = slots.get(0);
        sb.append("\n=== 最佳时段 ===\n");
        sb.append(String.format("最佳发文时段: %s（平均互动率 %.2f%%，共%d篇）\n",
                best.timeSlot(), best.avgEngagementRate() * 100, best.contentCount()));

        sb.append("\n=== 发布建议 ===\n");
        for (MetricsCalculator.TimeSlotPerformance s : slots) {
            sb.append("- ").append(s.timeSlot()).append("：").append(s.recommendation()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Generate chart-ready JSON data for visualization.
     *
     * <p>从 metricsData 中用 {@link MetricsParser} 解析真实数值，生成填好真实数据的
     * ECharts JSON 结构，而非空模板。支持 trend（趋势）/ pie（分布）/ bar（对比）
     * / scatter（散点）四种图表类型。
     *
     * @param metricsData 指标数据
     * @param chartType   图表类型：trend(趋势) / pie(分布) / bar(对比) / scatter(散点)
     * @return 可直接用于 ECharts 渲染的 JSON 字符串
     */
    @Tool("生成可视化图表数据，输出可直接用于ECharts的JSON格式")
    public String generateChartData(
            @P("指标数据") String metricsData,
            @P("图表类型：trend(趋势) / pie(分布) / bar(对比) / scatter(散点)") String chartType) {
        log.info("[Tool] generateChartData invoked, chartType: {}, metricsData length: {}",
                chartType, metricsData != null ? metricsData.length() : 0);

        String type = (chartType == null || chartType.isBlank()) ? "trend" : chartType.trim().toLowerCase();

        // 解析真实数值
        ParsedMetrics parsed = metricsParser.parse(metricsData);
        if (!parsed.hasData()) {
            return "{\"chartType\":\"" + type + "\",\"status\":\"no_data\","
                    + "\"message\":\"未检测到数据：未能从指标数据中解析出任何有效数值。"
                    + "请先调用 fetch*Analytics 或 calculateMetrics 获取真实数据后再生成图表。\"}";
        }

        switch (type) {
            case "pie":
                return generatePieChart(parsed);
            case "bar":
                return metricsCalculator.generateComparisonChart(
                        metricsCalculator.categorizeContent(metricsData));
            case "scatter":
                return generateScatterChart(metricsData);
            case "trend":
            default:
                return generateTrendChartFromData(metricsData);
        }
    }

    // ════════════════ 受众画像 ════════════════

    /**
     * 构建或获取账号的受众画像，包含粉丝量级、性别分布、地域分布、活跃时段等。
     *
     * <p>当已有缓存的受众画像时直接返回；否则从平台 API 拉取数据构建。
     * 返回人类可读的画像摘要文本，供 Agent 在分析时参考受众特征。
     *
     * @param accountId 账号 ID
     * @param platform  平台标识（wechat/douyin/xiaohongshu/bilibili/kuaishou，留空使用默认）
     * @return 受众画像摘要文本
     */
    @Tool("构建或获取账号的受众画像，包含粉丝量级、性别分布、地域分布、活跃时段、内容偏好等")
    public String buildAudienceProfile(
            @P("账号ID") String accountId,
            @P("平台标识：wechat/douyin/xiaohongshu/bilibili/kuaishou，留空使用默认") String platform) {
        log.info("[Tool] buildAudienceProfile invoked, accountId={}, platform={}", accountId, platform);

        AudienceProfile profile = audienceProfileService.getProfile(accountId);
        if (profile == null || !profile.hasData()) {
            // 缓存未命中或无数据，尝试构建
            if (platform != null && !platform.isBlank()) {
                profile = audienceProfileService.buildProfile(accountId, platform);
            } else {
                profile = audienceProfileService.buildProfile(accountId);
            }
        }

        if (profile == null || !profile.hasData()) {
            return "[受众画像] 账号 " + accountId + " 暂无受众画像数据。\n"
                    + "可能原因：平台 API 未配置凭证或无足够粉丝数据。\n"
                    + "建议：配置平台 API 凭证后重新构建画像。";
        }

        return profileEnricher.generateAudienceSummary(profile);
    }

    // ════════════════ 图表生成私有辅助 ════════════════

    /**
     * 生成饼图：互动构成分布（点赞/评论/分享/收藏占比）。
     */
    private String generatePieChart(ParsedMetrics parsed) {
        Map<String, Double> distribution = new LinkedHashMap<>();
        long total = parsed.likes() + parsed.commentCount() + parsed.shareCount() + parsed.collectCount();
        if (total <= 0) {
            // 无互动构成数据时，退化为触达构成
            if (parsed.readCount() > 0) {
                distribution.put("阅读量", (double) parsed.readCount());
            }
            if (parsed.playCount() > 0) {
                distribution.put("播放量", (double) parsed.playCount());
            }
        } else {
            if (parsed.likes() > 0) {
                distribution.put("点赞", (double) parsed.likes());
            }
            if (parsed.commentCount() > 0) {
                distribution.put("评论", (double) parsed.commentCount());
            }
            if (parsed.shareCount() > 0) {
                distribution.put("分享", (double) parsed.shareCount());
            }
            if (parsed.collectCount() > 0) {
                distribution.put("收藏", (double) parsed.collectCount());
            }
        }
        return metricsCalculator.generateDistributionChart(distribution, "互动构成分布");
    }

    /**
     * 生成趋势图：委托 MetricsCalculator 逐条解析并生成趋势图。
     */
    private String generateTrendChartFromData(String metricsData) {
        List<ParsedMetrics> itemMetrics = metricsCalculator.parsePerItemMetrics(metricsData);
        if (itemMetrics.isEmpty()) {
            // 无内容条目时，整体作为单期数据点
            itemMetrics = List.of(metricsParser.parse(metricsData));
        }
        return metricsCalculator.generateTrendChart(itemMetrics);
    }

    /**
     * 生成散点图：每条内容的（触达量, 互动率）散点。
     */
    private String generateScatterChart(String metricsData) {
        List<ParsedMetrics> itemMetrics = metricsCalculator.parsePerItemMetrics(metricsData);
        List<List<Object>> scatterData = new ArrayList<>();
        for (ParsedMetrics m : itemMetrics) {
            long reach = Math.max(m.readCount(), m.playCount());
            double rate = metricsParser.computeEngagementRate(m) * 100;
            if (reach > 0) {
                scatterData.add(List.of(reach, MetricsCalculator.round2(rate)));
            }
        }
        if (scatterData.isEmpty()) {
            // 无内容条目时，整体作为单个散点
            ParsedMetrics overall = metricsParser.parse(metricsData);
            long reach = Math.max(overall.readCount(), overall.playCount());
            double rate = metricsParser.computeEngagementRate(overall) * 100;
            if (reach > 0) {
                scatterData.add(List.of(reach, MetricsCalculator.round2(rate)));
            }
        }

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartType", "scatter");
        chart.put("title", Map.of("text", "触达量-互动率散点图"));
        chart.put("tooltip", Map.of("trigger", "item", "formatter", "触达: {c0}, 互动率: {c1}%"));
        chart.put("xAxis", Map.of("type", "value", "name", "触达量"));
        chart.put("yAxis", Map.of("type", "value", "name", "互动率(%)"));
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", "内容");
        series.put("type", "scatter");
        series.put("data", scatterData);
        chart.put("series", List.of(series));
        return metricsCalculator.serializeJson(chart);
    }
}
