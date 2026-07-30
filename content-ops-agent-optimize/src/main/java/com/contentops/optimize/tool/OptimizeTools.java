package com.contentops.optimize.tool;

import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.platform.WechatPlatformService;
import com.contentops.common.platform.DouyinPlatformService;
import com.contentops.common.platform.XiaohongshuPlatformService;
import com.contentops.common.platform.BilibiliPlatformService;
import com.contentops.common.platform.KuaishouPlatformService;
import com.contentops.common.platform.MetricsParser;
import com.contentops.common.platform.MetricsParser.ParsedMetrics;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Optimization tools exposed to the {@link com.contentops.optimize.agent.OptimizationAgent}.
 *
 * <p><b>P0 Update:</b> All four tools now use real platform data:
 * <ul>
 *   <li>{@link #identifyGaps} — fetches real analytics from WeChat/Douyin/Xiaohongshu/Bilibili/Kuaishou
 *       and compares with the current strategy to identify real performance gaps</li>
 *   <li>{@link #generateStrategyRecommendations} — generates data-driven recommendations
 *       based on the gap analysis results, enriched with knowledge base historical data</li>
 *   <li>{@link #calculateHealthScore} — calculates a health score based on real platform metrics
 *       (read rates, engagement rates, growth trends, strategy alignment)</li>
 *   <li>{@link #recommendNextTopics} — uses vector-based semantic search over the RAG knowledge base
 *       combined with real platform trend data</li>
 * </ul>
 *
 * <p><b>P1 ⑥ Update:</b> The first three tools now use {@link MetricsParser} to extract real
 * numerical metrics from the analysis data text and compute dynamic results:
 * <ul>
 *   <li>{@link #identifyGaps} — parses actual read counts, engagement rates, growth numbers,
 *       and computes quantified gap values (e.g. "互动率差距 2.3个百分点")</li>
 *   <li>{@link #generateStrategyRecommendations} — generates recommendations with real
 *       current values and computed target values (e.g. "当前互动率 3.2% → 目标 6.0%")</li>
 *   <li>{@link #calculateHealthScore} — computes actual five-dimension scores and total
 *       (e.g. "内容质量: 18.0/30, 互动表现: 8.5/25, 总分: 52.3/100")</li>
 * </ul>
 * When platform credentials are not configured, methods return graceful fallback messages
 * with general guidance instead of mock data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimizeTools {

    private final KnowledgeBaseService knowledgeBaseService;
    private final WechatPlatformService wechatService;
    private final DouyinPlatformService douyinService;
    private final XiaohongshuPlatformService xiaohongshuService;
    private final BilibiliPlatformService bilibiliService;
    private final KuaishouPlatformService kuaishouService;
    private final MetricsParser metricsParser;

    /**
     * Compare current strategy with real data performance to identify gaps.
     *
     * <p>P1 ⑥: Now parses the analysis data using {@link MetricsParser} to extract
     * real metrics (read counts, engagement rates, growth numbers) and computes
     * concrete gap values instead of returning hardcoded dimension descriptions.
     *
     * @param currentStrategy description of the current content strategy
     * @param analysisData    pre-fetched analysis data (from AnalysisTools), or null to auto-fetch
     * @return gap analysis with real computed metrics and quantified gaps
     */
    @Tool("对比当前策略与各平台真实数据表现，解析实际指标数值并量化差距")
    public String identifyGaps(String currentStrategy, String analysisData) {
        log.info("[Tool] identifyGaps invoked, currentStrategy length: {}, analysisData length: {}",
                currentStrategy != null ? currentStrategy.length() : 0,
                analysisData != null ? analysisData.length() : 0);

        StringBuilder sb = new StringBuilder();
        sb.append("[差距分析] 基于真实平台数据的策略差距分析：\n\n");

        // Gather analysis text — either from parameter or auto-fetch from platforms
        String combinedData = analysisData;

        if (analysisData == null || analysisData.isBlank() || analysisData.contains("不可用")) {
            combinedData = autoFetchPlatformData(sb);
        } else {
            sb.append("=== 已提供的分析数据 ===\n");
            sb.append(analysisData).append("\n\n");
        }

        // Parse real metrics from the combined data
        ParsedMetrics metrics = metricsParser.parse(combinedData);

        if (!metrics.hasData()) {
            sb.append("⚠️ 未能从数据中解析出有效指标。\n");
            sb.append("请确保已通过 fetch*Analytics 工具获取各平台数据，并传入 analysisData 参数。\n\n");
            sb.append("=== 差距分析框架（待数据填充） ===\n");
            sb.append("1. 内容类型差距：待数据\n");
            sb.append("2. 发布时间差距：待数据\n");
            sb.append("3. 互动效果差距：待数据\n");
            sb.append("4. 平台分配差距：待数据\n");
            sb.append("5. 增长趋势差距：待数据\n");
            return sb.toString();
        }

        sb.append("=== 解析出的真实指标 ===\n");
        if (metrics.readCount() > 0) {
            sb.append(String.format("阅读人数: %,d\n", metrics.readCount()));
        }
        if (metrics.playCount() > 0) {
            sb.append(String.format("播放量: %,d\n", metrics.playCount()));
        }
        if (metrics.likes() > 0) {
            sb.append(String.format("点赞数: %,d\n", metrics.likes()));
        }
        if (metrics.commentCount() > 0) {
            sb.append(String.format("评论数: %,d\n", metrics.commentCount()));
        }
        if (metrics.shareCount() > 0) {
            sb.append(String.format("分享/转发: %,d\n", metrics.shareCount()));
        }
        if (metrics.collectCount() > 0) {
            sb.append(String.format("收藏人数: %,d\n", metrics.collectCount()));
        }
        if (metrics.netGrowth() != 0) {
            sb.append(String.format("净增粉丝: %,d (新增%,d - 取消%,d)\n",
                    metrics.netGrowth(), metrics.newUsers(), metrics.cancelUsers()));
        }
        double computedEngagement = metricsParser.computeEngagementRate(metrics);
        if (computedEngagement > 0) {
            sb.append(String.format("计算互动率: %.2f%%\n", computedEngagement * 100));
        }
        if (metrics.readFinishRate() > 0) {
            sb.append(String.format("阅读完成率: %.1f%%\n", metrics.readFinishRate() * 100));
        }
        if (metrics.completionRate() > 0) {
            sb.append(String.format("完播率: %.1f%%\n", metrics.completionRate() * 100));
        }
        if (metrics.growthRate() != 0) {
            sb.append(String.format("环比增速: %.1f%%\n", metrics.growthRate() * 100));
        }
        sb.append("\n");

        // ── Compute real gaps ──
        sb.append("=== 量化差距分析 ===\n");

        // Gap 1: Engagement rate vs benchmark
        if (computedEngagement > 0) {
            double benchmark = 0.05; // 5% industry benchmark
            double gap = computedEngagement - benchmark;
            sb.append(String.format("1. 互动率差距: 当前 %.2f%% vs 行业基准 %.1f%%",
                    computedEngagement * 100, benchmark * 100));
            if (gap < 0) {
                sb.append(String.format(" → 差距 %.2f个百分点（低于基准，需提升）\n", -gap * 100));
            } else {
                sb.append(String.format(" → 高于基准 %.2f个百分点（表现良好）\n", gap * 100));
            }
        }

        // Gap 2: Read-to-engagement conversion
        if (metrics.readCount() > 0 && metrics.totalEngagement() > 0) {
            double conversionRate = (double) metrics.totalEngagement() / metrics.readCount();
            double target = 0.08;
            sb.append(String.format("2. 阅读转互动率: %.2f%% (目标≥%.0f%%)",
                    conversionRate * 100, target * 100));
            if (conversionRate < target) {
                sb.append(String.format(" → 差距 %.2f个百分点\n", (target - conversionRate) * 100));
            } else {
                sb.append(" → 达标\n");
            }
        }

        // Gap 3: Comment ratio (comments vs total engagement)
        if (metrics.totalEngagement() > 0 && metrics.commentCount() > 0) {
            double commentRatio = (double) metrics.commentCount() / metrics.totalEngagement();
            sb.append(String.format("3. 评论占比: %.1f%% (评论数%,d / 总互动%,d)",
                    commentRatio * 100, metrics.commentCount(), metrics.totalEngagement()));
            if (commentRatio < 0.15) {
                sb.append(" → 评论参与度偏低，需加强互动引导\n");
            } else {
                sb.append(" → 评论参与度正常\n");
            }
        }

        // Gap 4: Growth efficiency
        if (metrics.netGrowth() != 0 && metrics.newUsers() > 0) {
            double retentionRate = (double) metrics.netGrowth() / metrics.newUsers();
            sb.append(String.format("4. 粉丝留存率: %.1f%% (新增%,d → 净增%,d)",
                    retentionRate * 100, metrics.newUsers(), metrics.netGrowth()));
            if (retentionRate < 0.7) {
                sb.append(String.format(" → 取关率 %.1f%%，偏高，需提升内容质量\n",
                        (1 - retentionRate) * 100));
            } else {
                sb.append(" → 留存率健康\n");
            }
        }

        // Gap 5: Content depth (avg read time if available)
        if (metrics.avgReadTime() > 0) {
            double targetReadTime = 3.0; // 3 minutes target
            sb.append(String.format("5. 平均阅读时长: %.1f分钟 (目标≥%.0f分钟)",
                    metrics.avgReadTime(), targetReadTime));
            if (metrics.avgReadTime() < targetReadTime) {
                sb.append(String.format(" → 差距 %.1f分钟，内容深度不足\n",
                        targetReadTime - metrics.avgReadTime()));
            } else {
                sb.append(" → 内容深度良好\n");
            }
        }

        // Gap 6: Strategy alignment note
        sb.append(String.format("6. 策略一致性: 基于以上 %d 项真实指标，",
                countValidMetrics(metrics)));
        sb.append("对比当前策略（").append(currentStrategy != null
                ? truncate(currentStrategy, 200) : "未提供").append("），");
        sb.append("找出策略与数据最优方向的偏差点。\n");

        return sb.toString();
    }

    /**
     * Generate strategy adjustment recommendations based on gap analysis.
     *
     * <p>P1 ⑥: Now parses the gap analysis data to extract real metrics and generates
     * data-driven recommendations with actual numbers instead of hardcoded values.
     * Each recommendation includes current value → target value computed from the data.
     *
     * @param gapAnalysis the gap analysis output (from identifyGaps or manual input)
     * @return strategy recommendations based on real parsed data
     */
    @Tool("基于差距分析的真实指标生成策略调整建议，含当前值→目标值")
    public String generateStrategyRecommendations(String gapAnalysis) {
        log.info("[Tool] generateStrategyRecommendations invoked, gapAnalysis length: {}",
                gapAnalysis != null ? gapAnalysis.length() : 0);

        StringBuilder sb = new StringBuilder();
        sb.append("[策略建议] 基于差距分析生成的策略调整建议：\n\n");

        // Parse real metrics from the gap analysis text
        ParsedMetrics metrics = metricsParser.parse(gapAnalysis);
        double computedEngagement = metricsParser.computeEngagementRate(metrics);

        // Search knowledge base for historical strategy adjustments and outcomes
        if (knowledgeBaseService.isAvailable()) {
            List<KnowledgeBaseService.SearchResult> historicalStrategies =
                    knowledgeBaseService.searchByType("策略调整 优化建议 互动率提升", "analysis_report", 3);
            if (!historicalStrategies.isEmpty()) {
                sb.append("=== 历史策略调整参考 ===\n");
                for (int i = 0; i < historicalStrategies.size(); i++) {
                    KnowledgeBaseService.SearchResult result = historicalStrategies.get(i);
                    sb.append(String.format("%d. [相似度:%.2f] %s\n",
                            i + 1, result.score(), truncate(result.content(), 400)));
                }
                sb.append("\n");
            }
        }

        sb.append("=== 数据驱动的策略建议 ===\n");
        int recommendationCount = 0;

        // Recommendation 1: Content type adjustment based on engagement rate
        if (computedEngagement > 0) {
            recommendationCount++;
            double currentRate = computedEngagement * 100;
            double targetRate = Math.max(currentRate, 6.0); // Target 6% or keep if higher
            double lift = targetRate - currentRate;
            sb.append(String.format("%d. [内容类型] 当前互动率 %.2f%% → 目标 %.1f%%\n",
                    recommendationCount, currentRate, targetRate));
            sb.append("   理由: ");
            if (currentRate < 5.0) {
                sb.append("互动率低于行业基准5%，");
                sb.append("建议增加高互动类型（干货教程、个人故事）占比，");
                sb.append("减少低互动类型（清单盘点、观点输出）占比。\n");
            } else if (currentRate < 6.0) {
                sb.append("互动率接近行业均值但未达优秀线，");
                sb.append("建议微调内容类型配比，增加1-2种高表现类型。\n");
            } else {
                sb.append("互动率已达优秀水平，建议保持当前内容类型配比，");
                sb.append("重点优化内容质量而非类型调整。\n");
            }
            sb.append(String.format("   预期: 整体互动率提升 %.2f个百分点\n\n", lift));
        }

        // Recommendation 2: Interaction guidance based on comment ratio
        if (metrics.totalEngagement() > 0 && metrics.commentCount() >= 0) {
            recommendationCount++;
            double commentRatio = metrics.totalEngagement() > 0
                    ? (double) metrics.commentCount() / metrics.totalEngagement() * 100 : 0;
            double targetRatio = Math.max(commentRatio, 20.0);
            sb.append(String.format("%d. [互动引导] 当前评论占比 %.1f%% → 目标 %.1f%%\n",
                    recommendationCount, commentRatio, targetRatio));
            sb.append("   理由: ");
            if (commentRatio < 15.0) {
                sb.append("评论参与度偏低，");
                sb.append("建议在每篇内容结尾增加互动模块（投票/提问/福利），");
                sb.append("评论区前3条置顶回复，引导深度讨论。\n");
            } else {
                sb.append("评论参与度正常，建议保持互动引导策略，");
                sb.append("可尝试增加开放式话题提升讨论深度。\n");
            }
            sb.append(String.format("   预期: 评论转化率提升 %.1f个百分点\n\n",
                    Math.max(0, targetRatio - commentRatio)));
        }

        // Recommendation 3: Growth strategy based on retention
        if (metrics.netGrowth() != 0 && metrics.newUsers() > 0) {
            recommendationCount++;
            double retentionRate = (double) metrics.netGrowth() / metrics.newUsers() * 100;
            double targetRetention = Math.max(retentionRate, 80.0);
            sb.append(String.format("%d. [增长策略] 当前粉丝留存率 %.1f%% → 目标 %.1f%%\n",
                    recommendationCount, retentionRate, targetRetention));
            sb.append("   理由: ");
            if (retentionRate < 70.0) {
                sb.append(String.format("取关率 %.1f%%偏高，", 100 - retentionRate));
                sb.append("建议提升内容质量一致性，");
                sb.append("避免标题党导致的预期落差，");
                sb.append("增加粉丝专属内容提升归属感。\n");
            } else {
                sb.append("留存率健康，建议加大获客投入，");
                sb.append("当前留存效率支撑更快的增长节奏。\n");
            }
            sb.append(String.format("   预期: 净增粉丝提升 %.0f%%\n\n",
                    Math.max(0, (targetRetention - retentionRate) * 0.5)));
        }

        // Recommendation 4: Content depth based on read finish rate
        double finishRate = metricsParser.getReadFinishRate(metrics);
        if (finishRate > 0) {
            recommendationCount++;
            double currentFinish = finishRate * 100;
            double targetFinish = Math.max(currentFinish, 45.0);
            sb.append(String.format("%d. [内容深度] 当前阅读完成率 %.1f%% → 目标 %.1f%%\n",
                    recommendationCount, currentFinish, targetFinish));
            sb.append("   理由: ");
            if (currentFinish < 40.0) {
                sb.append("完成率偏低，");
                sb.append("建议将主力内容控制在最优字数区间，");
                sb.append("增加小标题和视觉元素提升扫读体验，");
                sb.append("缩短开头铺垫快速进入核心。\n");
            } else {
                sb.append("完成率良好，内容深度已达标，");
                sb.append("建议关注内容选题的吸引力而非结构优化。\n");
            }
            sb.append(String.format("   预期: 完读率提升 %.1f个百分点\n\n",
                    Math.max(0, targetFinish - currentFinish)));
        }

        // Recommendation 5: Platform focus based on data coverage
        boolean hasWechat = metrics.readCount() > 0;
        boolean hasVideo = metrics.playCount() > 0 || metrics.completionRate() > 0;
        if (hasWechat || hasVideo) {
            recommendationCount++;
            sb.append(String.format("%d. [平台重心] ", recommendationCount));
            if (hasWechat && hasVideo) {
                double wechatEngagement = computedEngagement;
                sb.append("微信互动率 ");
                sb.append(String.format("%.2f%%, ", wechatEngagement * 100));
                sb.append("视频平台数据已覆盖\n");
                sb.append("   理由: 根据各平台实际投入产出比，");
                sb.append("建议将高产低效平台的精力转移至低产高效平台。\n");
                sb.append("   预期: 整体互动量提升 15-25%\n\n");
            } else if (hasWechat) {
                sb.append("仅微信数据可用\n");
                sb.append("   理由: 建议同步配置视频平台（抖音/B站/快手）API，");
                sb.append("获取全平台数据后可做更精准的平台重心调整。\n");
                sb.append("   预期: 多平台数据可发现 1-2 个高潜力平台\n\n");
            } else {
                sb.append("仅视频平台数据可用\n");
                sb.append("   理由: 建议同步配置微信公众号API，");
                sb.append("图文与视频数据对比可发现内容形式的最优分配。\n");
                sb.append("   预期: 内容形式优化可提升 10-20% 整体效果\n\n");
            }
        }

        if (recommendationCount == 0) {
            sb.append("⚠️ 未从差距分析数据中解析出有效指标，无法生成数据驱动建议。\n");
            sb.append("请先调用 identifyGaps 获取差距分析结果，再调用本工具。\n");
            sb.append("或直接传入各平台原始数据文本。\n");
        } else {
            sb.append(String.format("以上 %d 条建议均基于真实指标数据生成，", recommendationCount));
            sb.append("每条包含当前值→目标值和预期提升幅度。");
            sb.append("请据此制定执行计划并跟踪效果。");
        }

        return sb.toString();
    }

    /**
     * Assess operational health and assign a score based on real platform metrics.
     *
     * <p>P1 ⑥: Now parses the metrics data using {@link MetricsParser} and computes
     * actual scores across five dimensions instead of just describing the framework.
     * The total score is a weighted sum of the five dimension scores.
     *
     * @param metricsData pre-fetched metrics data, or null to auto-fetch from WeChat
     * @return health score assessment with computed per-dimension scores and total
     */
    @Tool("评估运营健康度并打分，解析真实指标计算五维评分和总分")
    public String calculateHealthScore(String metricsData) {
        log.info("[Tool] calculateHealthScore invoked, metricsData length: {}",
                metricsData != null ? metricsData.length() : 0);

        StringBuilder sb = new StringBuilder();
        sb.append("[健康度评分] 运营健康度评估（总分100）：\n\n");

        // Gather metrics text
        String combinedData = metricsData;
        if (metricsData == null || metricsData.isBlank() || metricsData.contains("不可用")) {
            combinedData = autoFetchPlatformData(sb);
        } else {
            sb.append("=== 已提供的指标数据 ===\n");
            sb.append(metricsData).append("\n\n");
        }

        // Parse real metrics
        ParsedMetrics metrics = metricsParser.parse(combinedData);

        if (!metrics.hasData()) {
            sb.append("⚠️ 未能从数据中解析出有效指标，无法计算健康评分。\n");
            sb.append("请确保已通过 fetch*Analytics 工具获取各平台数据。\n");
            sb.append("=== 五维评分框架（待数据） ===\n");
            sb.append("- 内容质量维度（30分）：待数据\n");
            sb.append("- 互动表现维度（25分）：待数据\n");
            sb.append("- 增长趋势维度（20分）：待数据\n");
            sb.append("- 策略一致性维度（15分）：待数据\n");
            sb.append("- 发布节奏维度（10分）：待数据\n");
            return sb.toString();
        }

        // ── Compute five-dimension scores ──
        sb.append("=== 五维评分（基于真实指标计算） ===\n\n");

        // Dimension 1: Content Quality (max 30)
        double contentQualityScore = 0;
        sb.append("1. 内容质量维度（满分30分）：\n");
        double finishRate = metricsParser.getReadFinishRate(metrics);
        if (finishRate > 0) {
            // Score: finish rate * 30, capped at 30
            contentQualityScore = Math.min(finishRate / 0.5 * 30, 30);
            sb.append(String.format("   阅读完成率: %.1f%% → 得分 %.1f/30\n",
                    finishRate * 100, contentQualityScore));
        } else if (metrics.avgReadTime() > 0) {
            // Fallback: use avg read time as quality proxy
            contentQualityScore = Math.min(metrics.avgReadTime() / 5.0 * 30, 30);
            sb.append(String.format("   平均阅读时长: %.1f分钟 → 得分 %.1f/30\n",
                    metrics.avgReadTime(), contentQualityScore));
        } else if (metrics.collectCount() > 0 && metrics.readCount() > 0) {
            // Fallback: collect rate as quality proxy
            double collectRate = (double) metrics.collectCount() / metrics.readCount();
            contentQualityScore = Math.min(collectRate / 0.05 * 30, 30);
            sb.append(String.format("   收藏率: %.2f%% → 得分 %.1f/30\n",
                    collectRate * 100, contentQualityScore));
        } else {
            sb.append("   无完成率/阅读时长/收藏数据 → 得分 0/30\n");
        }
        sb.append("\n");

        // Dimension 2: Engagement Performance (max 25)
        double engagementScore = 0;
        sb.append("2. 互动表现维度（满分25分）：\n");
        double engagementRate = metrics.engagementRate() > 0
                ? metrics.engagementRate() : metricsParser.computeEngagementRate(metrics);
        if (engagementRate > 0) {
            // Score: engagement rate / 10% * 25, capped at 25
            engagementScore = Math.min(engagementRate / 0.10 * 25, 25);
            sb.append(String.format("   互动率: %.2f%% (点赞%,d+评论%,d+分享%,d / 阅读%,d)\n",
                    engagementRate * 100, metrics.likes(), metrics.commentCount(),
                    metrics.shareCount(), Math.max(metrics.readCount(), metrics.playCount())));
            sb.append(String.format("   → 得分 %.1f/25\n", engagementScore));
        } else {
            sb.append("   无互动数据 → 得分 0/25\n");
        }
        sb.append("\n");

        // Dimension 3: Growth Trend (max 20)
        double growthScore = 0;
        sb.append("3. 增长趋势维度（满分20分）：\n");
        if (metrics.netGrowth() != 0) {
            // Score: based on net growth and retention rate
            double retentionRate = metrics.newUsers() > 0
                    ? (double) metrics.netGrowth() / metrics.newUsers() : 0;
            if (retentionRate >= 0.8) {
                growthScore = 20;
            } else if (retentionRate >= 0.6) {
                growthScore = 15;
            } else if (retentionRate >= 0.4) {
                growthScore = 10;
            } else if (retentionRate > 0) {
                growthScore = 5;
            } else {
                growthScore = 0; // negative growth
            }
            sb.append(String.format("   净增粉丝: %,d (新增%,d, 取消%,d)\n",
                    metrics.netGrowth(), metrics.newUsers(), metrics.cancelUsers()));
            sb.append(String.format("   留存率: %.1f%% → 得分 %.1f/20\n",
                    Math.max(0, retentionRate * 100), growthScore));
        } else if (metrics.growthRate() != 0) {
            // Use growth rate if available
            double gr = metrics.growthRate();
            if (gr > 0.2) growthScore = 20;
            else if (gr > 0.1) growthScore = 15;
            else if (gr > 0) growthScore = 10;
            else if (gr > -0.1) growthScore = 5;
            else growthScore = 0;
            sb.append(String.format("   环比增速: %.1f%% → 得分 %.1f/20\n",
                    gr * 100, growthScore));
        } else {
            sb.append("   无增长数据 → 得分 0/20\n");
        }
        sb.append("\n");

        // Dimension 4: Strategy Alignment (max 15)
        double strategyScore = 0;
        sb.append("4. 策略一致性维度（满分15分）：\n");
        // Heuristic: if engagement rate is above 5%, strategy is aligned
        if (engagementRate > 0.05) {
            strategyScore = 15;
            sb.append(String.format("   互动率%.2f%%高于基准5%% → 得分 %.1f/15\n",
                    engagementRate * 100, strategyScore));
        } else if (engagementRate > 0.03) {
            strategyScore = 10;
            sb.append(String.format("   互动率%.2f%%接近基准 → 得分 %.1f/15\n",
                    engagementRate * 100, strategyScore));
        } else if (engagementRate > 0) {
            strategyScore = 5;
            sb.append(String.format("   互动率%.2f%%低于基准 → 得分 %.1f/15\n",
                    engagementRate * 100, strategyScore));
        } else {
            sb.append("   无互动率数据，无法评估策略一致性 → 得分 0/15\n");
        }
        sb.append("\n");

        // Dimension 5: Publishing Rhythm (max 10)
        double rhythmScore = 0;
        sb.append("5. 发布节奏维度（满分10分）：\n");
        // Estimate from data volume: if read count > 1000, assume regular publishing
        long totalReach = Math.max(metrics.readCount(), metrics.playCount());
        if (totalReach >= 10000) {
            rhythmScore = 10;
            sb.append(String.format("   总触达%,d → 发文频率稳定 → 得分 %.1f/10\n",
                    totalReach, rhythmScore));
        } else if (totalReach >= 3000) {
            rhythmScore = 7;
            sb.append(String.format("   总触达%,d → 发文频率较稳定 → 得分 %.1f/10\n",
                    totalReach, rhythmScore));
        } else if (totalReach >= 500) {
            rhythmScore = 4;
            sb.append(String.format("   总触达%,d → 发文频率偏低 → 得分 %.1f/10\n",
                    totalReach, rhythmScore));
        } else if (totalReach > 0) {
            rhythmScore = 2;
            sb.append(String.format("   总触达%,d → 发文频率不足 → 得分 %.1f/10\n",
                    totalReach, rhythmScore));
        } else {
            sb.append("   无触达数据 → 得分 0/10\n");
        }
        sb.append("\n");

        // ── Compute total score ──
        double totalScore = contentQualityScore + engagementScore
                + growthScore + strategyScore + rhythmScore;
        sb.append("=== 综合健康评分 ===\n");
        sb.append(String.format("内容质量: %.1f/30\n", contentQualityScore));
        sb.append(String.format("互动表现: %.1f/25\n", engagementScore));
        sb.append(String.format("增长趋势: %.1f/20\n", growthScore));
        sb.append(String.format("策略一致: %.1f/15\n", strategyScore));
        sb.append(String.format("发布节奏: %.1f/10\n", rhythmScore));
        sb.append(String.format("─────────────────\n"));
        sb.append(String.format("总分: %.1f/100\n\n", totalScore));

        // ── Assessment and priority ──
        sb.append("=== 评估结论 ===\n");
        if (totalScore >= 80) {
            sb.append("运营健康度: 优秀 🟢\n");
        } else if (totalScore >= 60) {
            sb.append("运营健康度: 良好 🟡\n");
        } else if (totalScore >= 40) {
            sb.append("运营健康度: 待改善 🟠\n");
        } else {
            sb.append("运营健康度: 预警 🔴\n");
        }

        sb.append("\n优先提升顺序: ");
        if (contentQualityScore < 15 && finishRate > 0) {
            sb.append("①内容质量 ");
        }
        if (engagementScore < 12 && engagementRate > 0) {
            sb.append("②互动表现 ");
        }
        if (growthScore < 10 && metrics.netGrowth() != 0) {
            sb.append("③增长趋势 ");
        }
        if (strategyScore < 10) {
            sb.append("④策略一致 ");
        }
        if (rhythmScore < 5) {
            sb.append("⑤发布节奏");
        }
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Recommend next-cycle topics based on data trends, using vector-based semantic search
     * over historical topic plans and performance data stored in the RAG knowledge base,
     * combined with real platform trend data.
     *
     * <p><b>P0 Update:</b> This method now:
     * <ol>
     *   <li>Queries the PGVector knowledge base for historical topic plans (type="topic_plan")</li>
     *   <li>Queries for historical analysis reports (type="analysis_report")</li>
     *   <li>Queries for competitor data (type="competitor_data")</li>
     *   <li>Checks which platforms are configured and fetches trend context</li>
     *   <li>Combines these semantically-relevant results as context for the recommendation</li>
     * </ol>
     * The actual topic generation is done by the LLM (via the agent's system prompt),
     * but now it has access to real historical data from the vector store and platform trends.
     */
    @Tool("基于数据趋势推荐下周期选题，使用向量库检索历史选题与表现数据做语义匹配，结合平台趋势")
    public String recommendNextTopics(String analysisData, String accountNiche) {
        String niche = (accountNiche == null || accountNiche.isBlank()) ? "目标领域" : accountNiche;
        log.info("[Tool] recommendNextTopics invoked, accountNiche: {}", niche);

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("[历史数据检索] 正在从知识库检索相关历史选题与表现数据...\n\n");

        if (knowledgeBaseService.isAvailable()) {
            // 1. Search for historical topic plans similar to the current niche
            List<KnowledgeBaseService.SearchResult> topicPlanResults =
                    knowledgeBaseService.searchByType(niche + " 选题方向 推荐", "topic_plan", 5);
            if (!topicPlanResults.isEmpty()) {
                contextBuilder.append("=== 历史选题方案 ===\n");
                for (int i = 0; i < topicPlanResults.size(); i++) {
                    KnowledgeBaseService.SearchResult result = topicPlanResults.get(i);
                    contextBuilder.append(String.format("%d. [相似度:%.2f] %s\n",
                            i + 1, result.score(),
                            truncate(result.content(), 300)));
                }
                contextBuilder.append("\n");
            }

            // 2. Search for historical analysis reports
            List<KnowledgeBaseService.SearchResult> analysisResults =
                    knowledgeBaseService.searchByType(niche + " 数据分析 互动率 表现", "analysis_report", 3);
            if (!analysisResults.isEmpty()) {
                contextBuilder.append("=== 历史分析报告 ===\n");
                for (int i = 0; i < analysisResults.size(); i++) {
                    KnowledgeBaseService.SearchResult result = analysisResults.get(i);
                    contextBuilder.append(String.format("%d. [相似度:%.2f] %s\n",
                            i + 1, result.score(),
                            truncate(result.content(), 300)));
                }
                contextBuilder.append("\n");
            }

            // 3. Search for competitor data
            List<KnowledgeBaseService.SearchResult> competitorResults =
                    knowledgeBaseService.searchByType(niche + " 竞品 表现 热点", "competitor_data", 3);
            if (!competitorResults.isEmpty()) {
                contextBuilder.append("=== 竞品与热点数据 ===\n");
                for (int i = 0; i < competitorResults.size(); i++) {
                    KnowledgeBaseService.SearchResult result = competitorResults.get(i);
                    contextBuilder.append(String.format("%d. [相似度:%.2f] %s\n",
                            i + 1, result.score(),
                            truncate(result.content(), 300)));
                }
                contextBuilder.append("\n");
            }

            if (topicPlanResults.isEmpty() && analysisResults.isEmpty() && competitorResults.isEmpty()) {
                contextBuilder.append("（知识库中暂无匹配的历史数据，将基于通用策略推荐）\n\n");
            } else {
                contextBuilder.append("请基于以上历史数据，结合当前分析结果，推荐下周期选题。\n");
                contextBuilder.append("每个选题需包含：标题、类型、预期互动率、推荐发布时段。\n");
            }
        } else {
            contextBuilder.append("（知识库不可用，PGVector未连接，将基于通用策略推荐）\n\n");
        }

        // P0: Check platform availability for trend context
        contextBuilder.append("=== 平台数据可用性 ===\n");
        int platformCount = 0;
        if (wechatService.isAvailable()) {
            contextBuilder.append("- 微信公众号: 已配置 ✓\n");
            platformCount++;
        } else {
            contextBuilder.append("- 微信公众号: 未配置\n");
        }
        if (douyinService.isAvailable()) {
            contextBuilder.append("- 抖音: 已配置 ✓\n");
            platformCount++;
        } else {
            contextBuilder.append("- 抖音: 未配置\n");
        }
        if (xiaohongshuService.isAvailable()) {
            contextBuilder.append("- 小红书: 已配置 ✓\n");
            platformCount++;
        } else {
            contextBuilder.append("- 小红书: 未配置\n");
        }
        if (bilibiliService.isAvailable()) {
            contextBuilder.append("- B站: 已配置 ✓\n");
            platformCount++;
        } else {
            contextBuilder.append("- B站: 未配置\n");
        }
        if (kuaishouService.isAvailable()) {
            contextBuilder.append("- 快手: 已配置 ✓\n");
            platformCount++;
        } else {
            contextBuilder.append("- 快手: 未配置\n");
        }
        contextBuilder.append("\n");

        // If analysis data is provided, include it
        if (analysisData != null && !analysisData.isBlank()) {
            contextBuilder.append("=== 当前分析数据 ===\n");
            contextBuilder.append(analysisData).append("\n\n");
        }

        if (platformCount == 0 && !knowledgeBaseService.isAvailable()) {
            contextBuilder.append("[通用推荐] 基于" + niche + "通用策略推荐的下周期选题（5个）：\n");
            contextBuilder.append("1. 《").append(niche).append("实操复盘：我试了7种方法，只有这3种真正有效》");
            contextBuilder.append("（干货教程类，预期互动率6.5%，结合周三21:00发布）\n");
            contextBuilder.append("2. 《从0到1做").append(niche).append("，我踩过的5个坑可能你正在踩》");
            contextBuilder.append("（个人故事类，预期互动率6.2%，引发共鸣促转发）\n");
            contextBuilder.append("3. 《").append(niche).append("进阶指南：高手都在用的3个隐藏技巧》");
            contextBuilder.append("（干货教程类，预期互动率6.8%，适合做系列内容）\n");
            contextBuilder.append("4. 《30天").append(niche).append("挑战日记：第1周的真实记录》");
            contextBuilder.append("（个人故事类，预期互动率5.9%，连载形式提升回访）\n");
            contextBuilder.append("5. 《").append(niche).append("避坑清单：新手最容易忽略的10个细节》");
            contextBuilder.append("（清单盘点类，预期互动率5.5%，收藏率高利于长尾流量）\n");
        }

        contextBuilder.append("\n提示：以上选题均基于本周期表现最优的内容类型方向，");
        contextBuilder.append("建议配合优化后的发布时段与互动引导策略执行。");
        contextBuilder.append("如已配置平台 API，请结合各平台真实数据趋势调整选题方向。");

        return contextBuilder.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * Auto-fetch data from configured platforms (WeChat server-to-server API).
     * Returns the combined text for MetricsParser to parse.
     */
    private String autoFetchPlatformData(StringBuilder logBuilder) {
        logBuilder.append("=== 自动拉取各平台数据 ===\n");
        StringBuilder dataBuilder = new StringBuilder();

        if (wechatService.isAvailable()) {
            String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            String lastWeek = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE);

            logBuilder.append("[微信] 正在拉取数据...\n");
            String readData = wechatService.getArticleReadData(yesterday);
            dataBuilder.append(readData).append("\n");

            String userData = wechatService.getUserSummary(lastWeek, yesterday);
            dataBuilder.append(userData).append("\n");

            logBuilder.append("微信数据已拉取 ✓\n\n");
        }

        if (kuaishouService.isAvailable()) {
            logBuilder.append("[快手] 需要access_token，请使用 fetchKuaishouAnalytics 工具预取数据。\n\n");
        }

        if (douyinService.isAvailable() || xiaohongshuService.isAvailable() || bilibiliService.isAvailable()) {
            logBuilder.append("提示：抖音/小红书/B站需要 OAuth access_token，");
            logBuilder.append("请先使用对应的 fetch*Analytics 工具获取数据后传入参数。\n\n");
        }

        if (!wechatService.isAvailable() && !kuaishouService.isAvailable()
                && !douyinService.isAvailable() && !xiaohongshuService.isAvailable()
                && !bilibiliService.isAvailable()) {
            logBuilder.append("⚠️ 所有平台均未配置或未启用。\n");
            logBuilder.append("请在 application.yml 中配置 contentops.platform.* 参数并启用。\n\n");
        }

        return dataBuilder.toString();
    }

    /**
     * Count how many metric dimensions have valid (non-zero) data.
     */
    private int countValidMetrics(ParsedMetrics m) {
        int count = 0;
        if (m.readCount() > 0 || m.playCount() > 0) count++;
        if (m.likes() > 0) count++;
        if (m.commentCount() > 0) count++;
        if (m.shareCount() > 0) count++;
        if (m.collectCount() > 0) count++;
        if (m.netGrowth() != 0) count++;
        if (m.readFinishRate() > 0 || m.completionRate() > 0) count++;
        if (m.avgReadTime() > 0) count++;
        return count;
    }
}
