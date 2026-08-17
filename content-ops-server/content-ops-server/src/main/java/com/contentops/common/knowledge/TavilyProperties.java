package com.contentops.common.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Tavily AI Search API.
 *
 * <p>Bindable via application.yml under {@code contentops.tavily}:
 * <pre>
 * contentops:
 *   tavily:
 *     api-key: tvly-xxxxxxxxxxxxx
 *     base-url: https://api.tavily.com
 *     max-results: 5
 *     search-depth: basic
 *     timeout-seconds: 15
 * </pre>
 *
 * <p>Get your API key at https://tavily.com — free tier includes 1,000 searches/month.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.tavily")
public class TavilyProperties {

    /** Tavily API key (required for real web search; if empty, tools gracefully degrade) */
    private String apiKey = "";

    /** Tavily API base URL */
    private String baseUrl = "https://api.tavily.com";

    /** Default maximum number of search results */
    private int maxResults = 5;

    /** Search depth: "basic" or "advanced" (advanced uses more credits but returns richer content) */
    private String searchDepth = "basic";

    /** HTTP timeout in seconds */
    private int timeoutSeconds = 15;
}
