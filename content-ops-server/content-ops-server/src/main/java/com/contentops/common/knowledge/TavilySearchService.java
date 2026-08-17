package com.contentops.common.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily AI Search service — provides real-time web search for AI agents.
 *
 * <p>Tavily is designed specifically for LLM/AI agents: it returns clean,
 * LLM-friendly content snippets instead of raw HTML. This replaces the mock
 * search tools with real internet access.
 *
 * <p>Key methods:
 * <ul>
 *   <li>{@link #search} — general web search returning ranked snippets</li>
 *   <li>{@link #searchNews} — news-focused search with time_range support</li>
 *   <li>{@link #getAnswer} — search + AI-generated answer summary</li>
 * </ul>
 *
 * <p>If no API key is configured, methods return a graceful fallback message
 * instead of throwing — agents continue working with reduced capability.
 */
@Slf4j
@Component
public class TavilySearchService {

    private final TavilyProperties properties;
    private final RestClient restClient;

    public TavilySearchService(TavilyProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("Tavily API key not configured — web search tools will return fallback messages. " +
                    "Set contentops.tavily.api-key to enable real web search.");
        } else {
            log.info("TavilySearchService initialized: maxResults={}, depth={}",
                    properties.getMaxResults(), properties.getSearchDepth());
        }
    }

    /**
     * Check whether the Tavily API key is configured.
     */
    public boolean isAvailable() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    /**
     * Perform a general web search via Tavily.
     *
     * @param query      the search query
     * @param maxResults maximum number of results (0 or negative uses default)
     * @return formatted search results string
     */
    public String search(String query, int maxResults) {
        if (!isAvailable()) {
            return "[联网搜索不可用] Tavily API Key 未配置。请在 application.yml 中设置 contentops.tavily.api-key。" +
                    "查询内容: " + query;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("max_results", maxResults > 0 ? maxResults : properties.getMaxResults());
            body.put("search_depth", properties.getSearchDepth());
            body.put("include_answer", true);
            body.put("include_raw_content", false);

            TavilyResponse response = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TavilyResponse.class);

            return formatResponse(response, query);
        } catch (Exception e) {
            log.error("Tavily search failed for query: {}", query, e);
            return "[联网搜索失败] 查询: " + query + "，错误: " + e.getMessage();
        }
    }

    /**
     * Perform a news-focused search (uses topic=news and time_range).
     *
     * @param query      the search query
     * @param maxResults maximum number of results
     * @param timeRange  time range filter: "day", "week", "month", "year" (null = no filter)
     * @return formatted search results string
     */
    public String searchNews(String query, int maxResults, String timeRange) {
        if (!isAvailable()) {
            return "[联网搜索不可用] Tavily API Key 未配置。查询内容: " + query;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("max_results", maxResults > 0 ? maxResults : properties.getMaxResults());
            body.put("search_depth", properties.getSearchDepth());
            body.put("topic", "news");
            body.put("include_answer", true);
            body.put("include_raw_content", false);
            if (timeRange != null && !timeRange.isBlank()) {
                body.put("time_range", timeRange);
            }

            TavilyResponse response = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TavilyResponse.class);

            return formatResponse(response, query);
        } catch (Exception e) {
            log.error("Tavily news search failed for query: {}", query, e);
            return "[联网搜索失败] 查询: " + query + "，错误: " + e.getMessage();
        }
    }

    /**
     * 结构化全网搜索（供聚合端点直接消费），未配置 Key 时返回空列表。
     */
    public List<TavilyResult> searchStructured(String query, int maxResults) {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("max_results", maxResults > 0 ? maxResults : properties.getMaxResults());
            body.put("search_depth", properties.getSearchDepth());
            body.put("include_answer", false);
            body.put("include_raw_content", false);
            TavilyResponse response = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TavilyResponse.class);
            return response == null ? List.of() : response.getResults();
        } catch (Exception e) {
            log.warn("Tavily structured search failed: query={}, err={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 结构化新闻搜索（topic=news + time_range），未配置 Key 时返回空列表。
     */
    public List<TavilyResult> searchNewsStructured(String query, int maxResults, String timeRange) {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("max_results", maxResults > 0 ? maxResults : properties.getMaxResults());
            body.put("search_depth", properties.getSearchDepth());
            body.put("topic", "news");
            body.put("include_answer", false);
            body.put("include_raw_content", false);
            if (timeRange != null && !timeRange.isBlank()) {
                body.put("time_range", timeRange);
            }
            TavilyResponse response = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TavilyResponse.class);
            return response == null ? List.of() : response.getResults();
        } catch (Exception e) {
            log.warn("Tavily structured news search failed: query={}, err={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * Search and return the AI-generated answer summary.
     *
     * @param query the search query
     * @return the answer string, or empty if not available
     */
    public String getAnswer(String query) {
        if (!isAvailable()) {
            return "[联网搜索不可用] Tavily API Key 未配置。";
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("max_results", 3);
            body.put("search_depth", "advanced");
            body.put("include_answer", true);
            body.put("include_raw_content", false);

            TavilyResponse response = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TavilyResponse.class);

            if (response != null && response.getAnswer() != null && !response.getAnswer().isBlank()) {
                return response.getAnswer();
            }
            return "[Tavily未返回答案摘要] 请查看搜索结果片段。";
        } catch (Exception e) {
            log.error("Tavily getAnswer failed for query: {}", query, e);
            return "[获取答案摘要失败] " + e.getMessage();
        }
    }

    /**
     * Format the Tavily response into a readable string for the LLM.
     */
    private String formatResponse(TavilyResponse response, String query) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return "[无搜索结果] 查询: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[联网搜索结果] 查询: ").append(query).append("\n");

        if (response.getAnswer() != null && !response.getAnswer().isBlank()) {
            sb.append("\n摘要: ").append(response.getAnswer()).append("\n");
        }

        sb.append("\n相关网页片段:\n");
        int i = 1;
        for (TavilyResult result : response.getResults()) {
            sb.append(i++).append(". 【").append(result.getTitle() != null ? result.getTitle() : "无标题").append("】\n");
            sb.append("   URL: ").append(result.getUrl() != null ? result.getUrl() : "").append("\n");
            if (result.getContent() != null && !result.getContent().isBlank()) {
                // Truncate content to keep LLM context manageable
                String content = result.getContent();
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                sb.append("   内容: ").append(content).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ──────────────────── Tavily API Response DTOs ────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TavilyResponse {
        @JsonProperty("answer")
        private String answer;

        @JsonProperty("results")
        private List<TavilyResult> results = new ArrayList<>();

        @JsonProperty("response_time")
        private double responseTime;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TavilyResult {
        @JsonProperty("title")
        private String title;

        @JsonProperty("url")
        private String url;

        @JsonProperty("content")
        private String content;

        @JsonProperty("score")
        private double score;
    }
}
