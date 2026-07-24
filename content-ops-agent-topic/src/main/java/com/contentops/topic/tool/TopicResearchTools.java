package com.contentops.topic.tool;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Research tools exposed to the {@link com.contentops.topic.agent.TopicPlanningAgent}.
 *
 * <p>LangChain4j discovers {@code @Tool} methods on Spring-managed objects that are passed to
 * {@code AiServices.builder().tools(...)} and exposes them to the model as callable functions.
 * The implementations here return simulated data; in production they would delegate to real
 * hot-search / competitor-analysis APIs.
 */
@Slf4j
@Component
public class TopicResearchTools {

    @Tool("搜索指定领域近期的热点话题和关键词")
    public String searchTrendingTopics(String niche) {
        log.info("[Tool] searchTrendingTopics invoked for niche: {}", niche);
        return "[模拟数据] " + niche + " 领域近7天热点话题：\n"
                + "1. " + niche + "入门指南：从零到一的实战路径（热度:9800, 趋势:上升）\n"
                + "2. 为什么大家都在聊" + niche + "？深度解读背后逻辑（热度:8500, 趋势:爆发）\n"
                + "3. " + niche + "避坑清单：新手最容易踩的5个误区（热度:7600, 趋势:上升）\n"
                + "4. 一文看懂" + niche + "行业最新政策与机会（热度:6400, 趋势:平稳）\n"
                + "热门关键词: " + niche + "干货、" + niche + "教程、" + niche + "案例、"
                + niche + "趋势、" + niche + "变现";
    }

    @Tool("分析竞品账号的内容方向和表现")
    public String analyzeCompetitors(String niche) {
        log.info("[Tool] analyzeCompetitors invoked for niche: {}", niche);
        return "[模拟数据] " + niche + " 领域竞品分析：\n"
                + "- 竞品A：粉丝12万，近30天发布18篇，平均阅读2.3万，主打「干货教程」方向，互动率约4.1%。\n"
                + "- 竞品B：粉丝8万，近30天发布22篇，平均阅读1.5万，主打「个人故事+方法论」方向，互动率约5.6%。\n"
                + "- 竞品C：粉丝5万，近30天发布9篇，平均阅读3.1万，主打「热点解读」方向，互动率约6.8%。\n"
                + "共性结论：结构化干货+真实案例的组合互动率最高；纯观点输出易遇冷。"
                + "机会缺口：「" + niche + "实操复盘」与「反共识观点」选题尚有空间。";
    }

    @Tool("获取社交媒体平台的热搜榜单")
    public String getHotSearchRanking(String platform) {
        log.info("[Tool] getHotSearchRanking invoked for platform: {}", platform);
        return "[模拟数据] " + platform + " 平台当前热搜榜单：\n"
                + "1. #年轻人为什么开始反向消费# （在榜6小时，热度920万）\n"
                + "2. #AI改变打工人的每一天# （在榜3小时，热度780万）\n"
                + "3. #副业搞钱实录# （在榜2小时，热度650万）\n"
                + "4. #情绪价值才是顶配# （在榜5小时，热度520万）\n"
                + "5. #30岁前必须明白的5件事# （在榜1小时，热度430万）\n"
                + "提示：可结合账号领域筛选可蹭热点。";
    }
}
