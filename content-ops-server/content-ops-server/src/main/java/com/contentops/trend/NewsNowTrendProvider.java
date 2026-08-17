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
import java.util.List;
import java.util.UUID;

/**
 * newsnow 聚合 API 热点数据源（参考 TrendRadar 的数据源与域名安全校验）。
 *
 * <p>通过 {@code contentops.trend.provider=newsnow} 启用；
 * api-url 支持 {@code {platform}} 占位符，如
 * {@code https://newsnow.busiyi.world/api/s/{platform}}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(2)
public class NewsNowTrendProvider implements TrendProvider {

    private static final List<String> DEFAULT_PLATFORMS = List.of(
            "xiaohongshu", "weibo", "douyin", "bilibili", "zhihu");

    private final TrendProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String name() {
        return "newsnow";
    }

    @Override
    public List<String> supportedPlatforms() {
        return DEFAULT_PLATFORMS;
    }

    @Override
    public List<TrendHotspot> fetchHotspots(String platform, int limit) {
        List<TrendHotspot> result = new ArrayList<>();
        for (String p : DEFAULT_PLATFORMS) {
            if (platform != null && !platform.isBlank() && !p.equals(platform)) {
                continue;
            }
            result.addAll(fetchPlatform(p, limit));
        }
        return result;
    }

    private List<TrendHotspot> fetchPlatform(String platform, int limit) {
        String apiUrl = properties.getNewsnow().getApiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            log.warn("[Trend] newsnow api-url 未配置，跳过平台: {}", platform);
            return List.of();
        }
        String url = apiUrl.replace("{platform}", platform);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[Trend] newsnow 返回 {}: platform={}", response.statusCode(), platform);
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            return parseItems(root, platform, limit);
        } catch (Exception e) {
            log.warn("[Trend] newsnow 拉取失败: platform={}, err={}", platform, e.getMessage());
            return List.of();
        }
    }

    private List<TrendHotspot> parseItems(JsonNode root, String platform, int limit) {
        List<TrendHotspot> result = new ArrayList<>();
        JsonNode items = root.isArray() ? root : root.path("data").isArray() ? root.path("data") : root;
        if (items == null || !items.isArray()) {
            log.warn("[Trend] newsnow 返回结构无法解析: platform={}", platform);
            return result;
        }
        int rank = 0;
        for (JsonNode item : items) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String title = firstText(item, "title", "word", "hotword");
            if (title == null || title.isBlank()) {
                continue;
            }
            String itemUrl = firstText(item, "url", "link");
            // 域名安全校验（TrendRadar 思路）：不匹配则丢弃，防链接劫持
            String expectedDomain = properties.getNewsnow().getExpectedDomain();
            if (itemUrl != null && !itemUrl.isBlank() && !expectedDomain.isBlank()
                    && !itemUrl.contains(expectedDomain)) {
                log.warn("[Trend] 丢弃非白名单域名链接: {}", itemUrl);
                continue;
            }
            rank++;
            result.add(TrendHotspot.builder()
                    .id(UUID.randomUUID().toString())
                    .platform(platform)
                    .title(title)
                    .url(itemUrl)
                    .heat(parseHeat(item))
                    .rank(rank)
                    .category(firstText(item, "category", "tag"))
                    .summary(truncate(firstText(item, "summary", "desc"), 1000))
                    .capturedAt(LocalDateTime.now())
                    .build());
            if (result.size() >= limit) {
                break;
            }
        }
        log.info("[Trend] newsnow 拉取完成: platform={}, items={}", platform, result.size());
        return result;
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
        for (String field : new String[]{"heat", "hot", "heatScore", "num"}) {
            JsonNode value = item.get(field);
            if (value != null && value.isNumber()) {
                return value.asLong();
            }
            if (value != null && value.isTextual()) {
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
