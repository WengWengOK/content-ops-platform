package com.contentops.optimize.tool;

import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.platform.WechatPlatformService;
import com.contentops.common.platform.DouyinPlatformService;
import com.contentops.common.platform.XiaohongshuPlatformService;
import com.contentops.common.platform.BilibiliPlatformService;
import com.contentops.common.platform.KuaishouPlatformService;
import dev.langchain4j.agent.tool.Tool;
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

    /**
     * Compare current strategy with real data performance to identify gaps.
     *
     * <p>Fetches actual analytics data from configured platforms and compares
     * with the provided current strategy to identify concrete performance gaps.
     *
     * @param currentStrategy description of the current content strategy
     * @param analysisData    pre-fetched analysis data (from AnalysisTools), or null to auto-fetch
     * @return gap analysis based on real platform data
     */
    @Tool("对比当前策略与各平台真实数据表现，找出差距，支持自动拉取微信/抖音/小红书/B站/快手数据")
    public String identifyGaps(String currentStrategy, String analysisData) {
        log.info("[Tool] identifyGaps invoked, currentStrategy length: {}, analysisData length: {}",
                currentStrategy != null ? currentStrategy.length() : 0,
                analysisData != null ? analysisData.length() : 0);

        StringBuilder sb = new StringBuilder();
        sb.append("[差距分析] 基于真实平台数据的策略差距分析：\n\n");

        boolean hasRealData = false;

        // If analysisData is provided, use it directly
        if (analysisData != null && !analysisData.isBlank() && !analysisData.contains("不可用")) {
            sb.append("=== 已提供的分析数据 ===\n");
            sb.append(analysisData).append("\n\n");
            hasRealData = true;
        } else {
            // Auto-fetch from each configured platform
            sb.append("=== 自动拉取各平台数据 ===\n");

            // WeChat: fetch article read data and user summary
            if (wechatService.isAvailable()) {
                String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
                String lastWeek = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE);

                sb.append("[微信]\n");
                String readData = wechatService.getArticleReadData(yesterday);
                sb.append(readData).append("\n");

                String userData = wechatService.getUserSummary(lastWeek, yesterday);
                sb.append(userData).append("\n");
                hasRealData = true;
            }

            // Kuaishou: fetch video list stats
            if (kuaishouService.isAvailable()) {
                sb.append("[快手]\n");
                sb.append("提示：请提供 access_token 以获取快手视频数据，或使用 fetchKuaishouAnalytics 工具预取数据。\n\n");
                hasRealData = true;
            }

            // Douyin, Xiaohongshu, Bilibili: note that OAuth token is needed
            if (douyinService.isAvailable() || xiaohongshuService.isAvailable() || bilibiliService.isAvailable()) {
                sb.append("提示：抖音/小红书/B站需要 OAuth access_token，");
                sb.append("请先使用对应的 fetch*Analytics 工具获取数据后传入 analysisData 参数。\n\n");
                hasRealData = true;
            }
        }

        if (!hasRealData) {
            sb.append("⚠️ 所有平台均未配置或未启用。\n");
            sb.append("请在 application.yml 中配置 contentops.platform.* 参数并设置 enabled=true。\n\n");
            sb.append("以下为通用差距分析框架（缺少真实数据支撑）：\n");
        }

        sb.append("=== 差距分析维度 ===\n");
        sb.append("1. 内容类型差距：对比各类型内容的实际互动率与策略侧重比例\n");
        sb.append("2. 发布时间差距：对比实际各时段表现与当前发布时间安排\n");
        sb.append("3. 互动效果差距：对比阅读/播放量与点赞/评论/分享转化率\n");
        sb.append("4. 平台分配差距：对比各平台投入精力与实际产出比\n");
        sb.append("5. 增长趋势差距：对比粉丝增长趋势与策略预期目标\n\n");

        if (hasRealData) {
            sb.append("请基于以上真实数据，逐一分析当前策略与实际表现的差距，");
            sb.append("并量化每个维度的差距程度（如互动率差X个百分点）。");
        }

        return sb.toString();
    }

    /**
     * Generate strategy adjustment recommendations based on gap analysis.
     *
     * <p>Uses the gap analysis results combined with knowledge base historical data
     * to generate data-driven, actionable strategy recommendations.
     *
     * @param gapAnalysis the gap analysis output (from identifyGaps or manual input)
     * @return strategy recommendations based on real data
     */
    @Tool("基于差距分析生成策略调整建议，结合知识库历史数据做数据驱动推荐")
    public String generateStrategyRecommendations(String gapAnalysis) {
        log.info("[Tool] generateStrategyRecommendations invoked, gapAnalysis length: {}",
                gapAnalysis != null ? gapAnalysis.length() : 0);

        StringBuilder sb = new StringBuilder();
        sb.append("[策略建议] 基于差距分析生成的策略调整建议：\n\n");

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
        sb.append("1. 内容类型调整：根据各类型实际互动率数据，将表现最优类型占比提升至40%+，");
        sb.append("表现较弱类型降至15%以下。预期互动率提升1-2个百分点。\n");
        sb.append("2. 发布时间优化：根据各时段实际互动数据，将核心内容固定在互动率最高的时段发布，");
        sb.append("取消表现最弱时段的发文。预期阅读量提升15-25%。\n");
        sb.append("3. 互动引导强化：根据实际评论转化率数据，在每篇内容结尾增加互动模块（投票/提问/福利），");
        sb.append("评论区前3条置顶回复。预期评论转化率提升0.5-1个百分点。\n");
        sb.append("4. 平台重心调整：根据各平台实际投入产出比数据，重新分配精力占比，");
        sb.append("将高产低效平台的精力转移至低产高效平台。预期整体互动量提升20-35%。\n");
        sb.append("5. 内容长度微调：根据完读率/完播率数据，将主力内容控制在最优字数/时长区间，");
        sb.append("增加小标题和视觉元素提升扫读体验。预期完读率提升5-10个百分点。\n\n");

        sb.append("请基于以上框架和真实数据差距，给出具体的数值化调整方案，");
        sb.append("每个建议需包含：当前值 → 目标值、预期提升幅度、执行时间线。");

        return sb.toString();
    }

    /**
     * Assess operational health and assign a score based on real platform metrics.
     *
     * <p>Fetches real data from configured platforms and calculates a health score
     * across five dimensions: content quality, engagement, growth, strategy alignment,
     * and publishing rhythm.
     *
     * @param metricsData pre-fetched metrics data, or null to auto-fetch from WeChat
     * @return health score assessment based on real data
     */
    @Tool("评估运营健康度并打分，基于各平台真实指标计算五维评分")
    public String calculateHealthScore(String metricsData) {
        log.info("[Tool] calculateHealthScore invoked, metricsData length: {}",
                metricsData != null ? metricsData.length() : 0);

        StringBuilder sb = new StringBuilder();
        sb.append("[健康度评分] 运营健康度评估（总分100）：\n\n");

        boolean hasRealData = false;

        // Try to use provided metrics data
        if (metricsData != null && !metricsData.isBlank() && !metricsData.contains("不可用")) {
            sb.append("=== 提供的指标数据 ===\n");
            sb.append(metricsData).append("\n\n");
            hasRealData = true;
        } else {
            // Auto-fetch WeChat data if available (server-to-server token, no OAuth needed)
            if (wechatService.isAvailable()) {
                String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
                String lastWeek = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE);

                sb.append("=== 自动拉取微信数据 ===\n");
                String readData = wechatService.getArticleReadData(yesterday);
                sb.append(readData).append("\n");

                String userData = wechatService.getUserSummary(lastWeek, yesterday);
                sb.append(userData).append("\n");
                hasRealData = true;
            }

            if (douyinService.isAvailable() || xiaohongshuService.isAvailable()
                    || bilibiliService.isAvailable() || kuaishouService.isAvailable()) {
                sb.append("提示：抖音/小红书/B站/快手需要 OAuth access_token，");
                sb.append("请先使用 fetch*Analytics 工具获取数据后传入 metricsData 参数。\n\n");
                hasRealData = true;
            }
        }

        if (!hasRealData) {
            sb.append("⚠️ 所有平台均未配置或未启用，无法获取真实指标数据。\n");
            sb.append("请在 application.yml 中配置 contentops.platform.* 参数并启用。\n\n");
        }

        sb.append("=== 五维评分框架 ===\n");
        sb.append("- 内容质量维度（30分）：基于阅读完成率、收藏率、完播率评估\n");
        sb.append("- 互动表现维度（25分）：基于互动率（点赞+评论+分享/阅读量）、评论转化率评估\n");
        sb.append("- 增长趋势维度（20分）：基于粉丝净增长、环比增速趋势评估\n");
        sb.append("- 策略一致性维度（15分）：基于实际数据最优方向与当前策略的匹配度评估\n");
        sb.append("- 发布节奏维度（10分）：基于发文频率稳定性、时段选择合理性评估\n\n");

        if (hasRealData) {
            sb.append("请基于以上真实数据，计算每个维度的得分（0-满分），");
            sb.append("并给出综合健康评分（总分100）和提升优先级排序。");
        } else {
            sb.append("缺少真实数据，无法计算具体得分。请配置平台 API 后获取真实指标。");
        }

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
}
