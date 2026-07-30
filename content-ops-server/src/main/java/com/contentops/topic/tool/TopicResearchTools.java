package com.contentops.topic.tool;

import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.knowledge.TavilySearchService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Research tools exposed to the {@link com.contentops.topic.agent.TopicPlanningAgent}.
 *
 * <p><b>P1 Update:</b> All tools now use real internet search (Tavily API) and
 * persist results to the RAG knowledge base (PGVector) for future semantic retrieval.
 *
 * <ul>
 *   <li>{@link #searchTrendingTopics} — Tavily web search + KB ingestion</li>
 *   <li>{@link #analyzeCompetitors} — Tavily web search + KB ingestion</li>
 *   <li>{@link #getHotSearchRanking} — Tavily news search</li>
 * </ul>
 *
 * If the Tavily API key is not configured, tools gracefully degrade to returning
 * a placeholder message. If PGVector is unavailable, KB ingestion is silently skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicResearchTools {

    private final TavilySearchService tavilySearchService;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * Search for trending topics and keywords in the given niche using real web search.
     * Results are also ingested into the knowledge base for future reference.
     */
    @Tool("搜索指定领域近期的热点话题和关键词")
    public String searchTrendingTopics(String niche) {
        log.info("[Tool] searchTrendingTopics invoked for niche: {}", niche);

        // Perform real web search via Tavily
        String query = niche + " 热点话题 趋势 最新";
        String searchResults = tavilySearchService.search(query, 5);
        log.info("[Tool] searchTrendingTopics Tavily search completed for niche: {}", niche);

        // Ingest the search results into the knowledge base for future retrieval
        if (knowledgeBaseService.isAvailable()) {
            knowledgeBaseService.ingestCompetitorData(searchResults, niche);
            log.info("[Tool] searchTrendingTopics results ingested into knowledge base");
        }

        return searchResults;
    }

    /**
     * Analyze competitor accounts' content direction and performance using web search.
     * Results are stored in the knowledge base for cross-agent reference.
     */
    @Tool("分析竞品账号的内容方向和表现")
    public String analyzeCompetitors(String niche) {
        log.info("[Tool] analyzeCompetitors invoked for niche: {}", niche);

        // Perform real web search via Tavily
        String query = niche + " 自媒体账号 竞品分析 内容方向 互动率";
        String searchResults = tavilySearchService.search(query, 5);
        log.info("[Tool] analyzeCompetitors Tavily search completed for niche: {}", niche);

        // Ingest competitor data into the knowledge base
        if (knowledgeBaseService.isAvailable()) {
            knowledgeBaseService.ingestCompetitorData(searchResults, niche);
            log.info("[Tool] analyzeCompetitors results ingested into knowledge base");
        }

        return searchResults;
    }

    /**
     * Get the current hot search ranking for a specific platform using real news search.
     */
    @Tool("获取社交媒体平台的热搜榜单")
    public String getHotSearchRanking(String platform) {
        log.info("[Tool] getHotSearchRanking invoked for platform: {}", platform);

        // Perform real news search via Tavily (time_range=week for recent hot topics)
        String query = platform + " 热搜榜 今日 热门话题";
        String searchResults = tavilySearchService.searchNews(query, 10, "week");
        log.info("[Tool] getHotSearchRanking Tavily news search completed for platform: {}", platform);

        return searchResults;
    }
}
