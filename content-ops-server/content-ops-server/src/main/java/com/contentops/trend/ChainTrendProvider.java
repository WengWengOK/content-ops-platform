package com.contentops.trend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据源多路降级链（数据源独立化）：
 *
 * <ul>
 *   <li>{@code provider=chain}（默认）：按平台逐个尝试 sixty → newsnow，哪个源有数据用哪个；</li>
 *   <li>{@code provider=mock|newsnow|sixty}：仅使用指定单一数据源（兼容旧配置）。</li>
 * </ul>
 *
 * <p>小红书（xiaohongshu）由 newsnow 源承载（60s 不支持）；当 newsnow 不可达时
 * 该平台返回空并在前端展示「暂无热点数据」，不静默降级为演示数据。
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class ChainTrendProvider implements TrendProvider {

    private final List<TrendProvider> providers;
    private final TrendProperties properties;

    private Map<String, TrendProvider> providerMap() {
        Map<String, TrendProvider> map = new LinkedHashMap<>();
        for (TrendProvider p : providers) {
            map.put(p.name(), p);
        }
        return map;
    }

    private List<String> activeNames() {
        String mode = properties.getProvider() == null ? "chain" : properties.getProvider().trim();
        return switch (mode) {
            case "mock" -> List.of("mock");
            case "newsnow" -> List.of("newsnow");
            case "sixty" -> List.of("sixty");
            default -> List.of("sixty", "newsnow"); // chain 默认：真实源优先，mock 不参与
        };
    }

    @Override
    public String name() {
        return properties.getProvider() == null || properties.getProvider().isBlank()
                ? "chain" : properties.getProvider().trim();
    }

    @Override
    public List<String> supportedPlatforms() {
        Set<String> platforms = new LinkedHashSet<>();
        Map<String, TrendProvider> map = providerMap();
        for (String name : activeNames()) {
            TrendProvider p = map.get(name);
            if (p != null) {
                platforms.addAll(p.supportedPlatforms());
            }
        }
        return new ArrayList<>(platforms);
    }

    @Override
    public List<TrendHotspot> fetchHotspots(String platform, int limit) {
        Map<String, TrendProvider> map = providerMap();
        List<String> active = activeNames();
        List<TrendHotspot> result = new ArrayList<>();
        if (platform != null && !platform.isBlank()) {
            // 指定平台：按优先级取第一个有数据的源
            result.addAll(fetchWithChain(map, active, platform, limit));
            return result;
        }
        // 全平台：逐平台降级，避免某平台源挂了拖垮整体
        for (String p : supportedPlatforms()) {
            result.addAll(fetchWithChain(map, active, p, limit));
        }
        return result;
    }

    private List<TrendHotspot> fetchWithChain(
            Map<String, TrendProvider> map, List<String> active, String platform, int limit) {
        for (String name : active) {
            TrendProvider provider = map.get(name);
            if (provider == null) {
                continue;
            }
            try {
                List<TrendHotspot> fetched = provider.fetchHotspots(platform, limit);
                if (!fetched.isEmpty()) {
                    log.debug("[Trend] 数据源命中: platform={}, provider={}, items={}",
                            platform, name, fetched.size());
                    return fetched;
                }
            } catch (Exception e) {
                log.warn("[Trend] 数据源异常: platform={}, provider={}, err={}",
                        platform, name, e.getMessage());
            }
        }
        log.warn("[Trend] 所有数据源均无数据: platform={}", platform);
        return List.of();
    }
}
