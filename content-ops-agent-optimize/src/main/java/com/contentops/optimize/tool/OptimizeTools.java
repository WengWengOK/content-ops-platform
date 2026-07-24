package com.contentops.optimize.tool;

import com.contentops.common.knowledge.KnowledgeBaseService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Optimization tools exposed to the {@link com.contentops.optimize.agent.OptimizationAgent}.
 *
 * <p><b>P1 Update:</b> {@link #recommendNextTopics} now uses vector-based semantic search
 * over the RAG knowledge base (PGVector) to find historically similar topic plans and
 * performance data, instead of returning hardcoded mock recommendations.
 *
 * <p>The other three tools still use simulated analysis logic but are designed to be
 * enhanced with real data sources in future iterations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimizeTools {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * Compare current strategy with data performance to identify gaps.
     * Uses simulated analysis logic (to be enhanced with real data in future iterations).
     */
    @Tool("对比当前策略与数据表现，找出差距")
    public String identifyGaps(String currentStrategy, String analysisData) {
        log.info("[Tool] identifyGaps invoked, currentStrategy length: {}, analysisData length: {}",
                currentStrategy != null ? currentStrategy.length() : 0,
                analysisData != null ? analysisData.length() : 0);
        return "[差距分析] 当前策略与数据表现的对比：\n"
                + "1. 内容类型差距：当前策略侧重「热点解读」(占比35%)，但数据表明「干货教程」(互动率6.1%)"
                + "和「个人故事」(互动率5.8%)表现更优，热点解读互动率仅4.2%。\n"
                + "2. 发布时间差距：当前固定在周日22:00发文，但数据显示该时段表现最弱(互动率3.4%)，"
                + "最佳时段为周三21:00-22:00(互动率6.7%)。\n"
                + "3. 内容长度差距：当前偏好长文(2500字+)，但完读率仅62.3%，"
                + "数据表明1500-2000字区间完读率最高(约71%)。\n"
                + "4. 互动引导差距：当前缺少结尾互动设计，评论区转化率偏低(0.5%)，"
                + "行业标杆账号可达1.2%。\n"
                + "5. 平台分配差距：公众号投入70%精力，但小红书端互动率是公众号的1.8倍，存在重心错配。";
    }

    /**
     * Generate strategy adjustment recommendations.
     * Uses simulated logic (to be enhanced with real data in future iterations).
     */
    @Tool("生成策略调整建议")
    public String generateStrategyRecommendations(String gapAnalysis) {
        log.info("[Tool] generateStrategyRecommendations invoked, gapAnalysis length: {}",
                gapAnalysis != null ? gapAnalysis.length() : 0);
        return "[策略建议] 基于差距分析生成的策略调整建议：\n"
                + "1. 内容类型调整：将「干货教程」占比从25%提升至40%，「个人故事」从20%提升至30%，"
                + "「热点解读」从35%降至15%。预期互动率提升1.5个百分点。\n"
                + "2. 发布时间优化：核心干货内容固定在周三21:00和周四20:00发布，"
                + "轻量内容安排在周一07:00和周六10:00，取消周日深夜发文。预期阅读量提升22%。\n"
                + "3. 内容长度微调：将主力内容控制在1800-2200字，增加小标题和金句加粗，"
                + "提升扫读体验。预期完读率提升8-10个百分点。\n"
                + "4. 互动引导强化：每篇结尾增加「投票/提问/福利」三选一互动模块，"
                + "评论区前3条置顶回复。预期评论转化率提升至1.0%+。\n"
                + "5. 平台重心调整：公众号精力降至50%，小红书提升至40%，头条维持10%。"
                + "预期整体互动量提升35%。";
    }

    /**
     * Assess operational health and assign a score.
     * Uses simulated scoring logic (to be enhanced with real data in future iterations).
     */
    @Tool("评估运营健康度并打分")
    public String calculateHealthScore(String metricsData) {
        log.info("[Tool] calculateHealthScore invoked, metricsData length: {}",
                metricsData != null ? metricsData.length() : 0);
        return "[健康度评分] 运营健康度评估（总分100）：\n"
                + "- 内容质量维度（30分）：得分24分。干货与故事类内容质量高，但观点类偏弱。\n"
                + "- 互动表现维度（25分）：得分18分。互动率4.6%高于行业均值(3.5%)，但评论转化偏低。\n"
                + "- 增长趋势维度（20分）：得分16分。粉丝月增12480，趋势向上，但增速环比放缓8%。\n"
                + "- 策略一致性维度（15分）：得分9分。当前策略与数据最优方向存在错配，需调整。\n"
                + "- 发布节奏维度（10分）：得分7分。发文频率稳定(周均6篇)，但时段选择有待优化。\n"
                + "综合健康评分：74/100（良好，但存在明显优化空间）。\n"
                + "提升优先级：策略一致性 > 发布时段 > 互动引导 > 内容长度 > 平台分配。";
    }

    /**
     * Recommend next-cycle topics based on data trends, using vector-based semantic search
     * over historical topic plans and performance data stored in the RAG knowledge base.
     *
     * <p><b>P1 Update:</b> Instead of returning hardcoded mock topics, this method now:
     * <ol>
     *   <li>Queries the PGVector knowledge base for historical topic plans (type="topic_plan")</li>
     *   <li>Queries for historical analysis reports (type="analysis_report")</li>
     *   <li>Queries for competitor data (type="competitor_data")</li>
     *   <li>Combines these semantically-relevant results as context for the recommendation</li>
     * </ol>
     * The actual topic generation is still done by the LLM (via the agent's system prompt),
     * but now it has access to real historical data from the vector store.
     */
    @Tool("基于数据趋势推荐下周期选题，使用向量库检索历史选题与表现数据做语义匹配")
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
            contextBuilder.append("[通用推荐] 基于" + niche + "数据趋势推荐的下周期选题（5个）：\n");
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

        return contextBuilder.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
