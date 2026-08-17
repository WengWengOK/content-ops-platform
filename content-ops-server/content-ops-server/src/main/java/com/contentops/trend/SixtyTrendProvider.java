package com.contentops.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 60s 聚合 API 热点数据源（真实热榜追踪，参考 GitHub vikiboss/60s-skills hot-topics）。
 *
 * <p>覆盖微博/知乎/抖音/B站/百度/今日头条，返回真实榜单标题、热度与原文链接；
 * 通过 {@code contentops.trend.provider=sixty}（默认）启用。
 *
 * <p>安全性：每条链接按平台域名白名单校验（TrendRadar 思路），非白名单链接丢弃，
 * 防止聚合接口被劫持导致前端打开恶意地址。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
public class SixtyTrendProvider implements TrendProvider {

    /** 平台 code → 60s API 路径 */
    private static final Map<String, String> ENDPOINTS = new LinkedHashMap<>();
    /** 平台 code → 域名白名单（子串匹配，防链接劫持） */
    private static final Map<String, List<String>> DOMAIN_WHITELIST = new LinkedHashMap<>();

    static {
        ENDPOINTS.put("weibo", "/v2/weibo");
        ENDPOINTS.put("zhihu", "/v2/zhihu");
        ENDPOINTS.put("douyin", "/v2/douyin");
        ENDPOINTS.put("bilibili", "/v2/bili");
        ENDPOINTS.put("baidu", "/v2/baidu/hot");
        ENDPOINTS.put("toutiao", "/v2/toutiao");

        DOMAIN_WHITELIST.put("weibo", List.of("weibo.com"));
        DOMAIN_WHITELIST.put("zhihu", List.of("zhihu.com"));
        DOMAIN_WHITELIST.put("douyin", List.of("douyin.com"));
        DOMAIN_WHITELIST.put("bilibili", List.of("bilibili.com"));
        DOMAIN_WHITELIST.put("baidu", List.of("baidu.com"));
        DOMAIN_WHITELIST.put("toutiao", List.of("toutiao.com"));
    }

    private final TrendProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String name() {
        return "sixty";
    }

    @Override
    public List<String> supportedPlatforms() {
        return new ArrayList<>(ENDPOINTS.keySet());
    }

    @Override
    public List<TrendHotspot> fetchHotspots(String platform, int limit) {
        List<TrendHotspot> result = new ArrayList<>();
        for (String p : ENDPOINTS.keySet()) {
            if (platform != null && !platform.isBlank() && !p.equals(platform)) {
                continue;
            }
            result.addAll(fetchPlatform(p, limit));
        }
        return result;
    }

    private List<TrendHotspot> fetchPlatform(String platform, int limit) {
        String apiBase = properties.getSixty().getApiBase();
        if (apiBase == null || apiBase.isBlank()) {
            log.warn("[Trend] sixty api-base 未配置，跳过平台: {}", platform);
            return List.of();
        }
        String url = apiBase.replaceAll("/+$", "") + ENDPOINTS.get(platform);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getSixty().getTimeoutMs()))
                    .header("User-Agent", "ContentOpsTrendMonitor/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[Trend] sixty 返回 {}: platform={}", response.statusCode(), platform);
                return fetchFallback(platform, limit);
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<TrendHotspot> result = parseItems(root, platform, limit);
            if (result.isEmpty()) {
                return fetchFallback(platform, limit);
            }
            return result;
        } catch (Exception e) {
            log.warn("[Trend] sixty 拉取失败: platform={}, err={}", platform, e.getMessage());
            return fetchFallback(platform, limit);
        }
    }

    /**
     * 60s 聚合源不可用时按平台兜底：
     * B站走官方公开排行榜 API（/x/web-interface/ranking，无鉴权、无需 Key）。
     */
    private List<TrendHotspot> fetchFallback(String platform, int limit) {
        if ("bilibili".equals(platform)) {
            return fetchBilibiliOfficial(limit);
        }
        return List.of();
    }

    private List<TrendHotspot> fetchBilibiliOfficial(int limit) {
        String url = "https://api.bilibili.com/x/web-interface/ranking?rid=0&type=all";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getSixty().getTimeoutMs()))
                    .header("User-Agent", "ContentOpsTrendMonitor/1.0")
                    .header("Referer", "https://www.bilibili.com/")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[Trend] bilibili 官方榜返回 {}: platform=bilibili", response.statusCode());
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) {
                log.warn("[Trend] bilibili 官方榜失败: code={}", root.path("code").asInt(-1));
                return List.of();
            }
            JsonNode items = root.path("data").path("list");
            List<TrendHotspot> result = new ArrayList<>();
            int rank = 0;
            for (JsonNode item : items) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String title = firstText(item, "title");
                String bvid = firstText(item, "bvid");
                if (title == null || title.isBlank() || bvid == null || bvid.isBlank()) {
                    continue;
                }
                rank++;
                result.add(TrendHotspot.builder()
                        .id(UUID.randomUUID().toString())
                        .platform("bilibili")
                        .title(title)
                        .url("https://www.bilibili.com/video/" + bvid)
                        .heat(parseHeat(item))
                        .rank(rank)
                        .category("全站榜")
                        .summary(firstText(item, "author"))
                        .capturedAt(LocalDateTime.now())
                        .build());
                if (result.size() >= limit) {
                    break;
                }
            }
            log.info("[Trend] bilibili 官方榜拉取完成: items={}", result.size());
            return result;
        } catch (Exception e) {
            log.warn("[Trend] bilibili 官方榜拉取失败: err={}", e.getMessage());
            return List.of();
        }
    }

    private List<TrendHotspot> parseItems(JsonNode root, String platform, int limit) {
        List<TrendHotspot> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        // 兼容 data 数组 / data.data 数组
        JsonNode items = root.path("data");
        if (!items.isArray()) {
            items = root;
        }
        if (!items.isArray()) {
            log.warn("[Trend] sixty 返回结构无法解析: platform={}", platform);
            return result;
        }
        int rank = 0;
        for (JsonNode item : items) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String title = firstText(item, "title", "hotword", "word");
            if (title == null || title.isBlank()) {
                continue;
            }
            String itemUrl = firstText(item, "link", "url", "mobileUrl", "urls");
            if (itemUrl != null && !itemUrl.isBlank() && !isDomainAllowed(itemUrl, platform)) {
                log.warn("[Trend] 丢弃非白名单域名链接: platform={}, url={}", platform, itemUrl);
                itemUrl = null;
            }
            rank++;
            result.add(TrendHotspot.builder()
                    .id(UUID.randomUUID().toString())
                    .platform(platform)
                    .title(title)
                    .url(itemUrl)
                    .heat(parseHeat(item))
                    .rank(rank)
                    .category(firstText(item, "type_desc", "category", "tag"))
                    .summary(truncate(firstText(item, "detail", "desc", "summary"), 1000))
                    .capturedAt(LocalDateTime.now())
                    .build());
            if (result.size() >= limit) {
                break;
            }
        }
        log.info("[Trend] sixty 拉取完成: platform={}, items={}", platform, result.size());
        return result;
    }

    private boolean isDomainAllowed(String url, String platform) {
        List<String> allowed = DOMAIN_WHITELIST.get(platform);
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        String lower = url.toLowerCase();
        return allowed.stream().anyMatch(lower::contains);
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }

    private Long parseHeat(JsonNode item) {
        for (String field : new String[]{"hot_value", "热度", "hot", "heat", "score", "play", "num"}) {
            JsonNode value = item.get(field);
            if (value == null) {
                continue;
            }
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText().replaceAll("[^0-9]", ""));
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return null;
    }
}
