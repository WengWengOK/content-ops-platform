package com.contentops.trend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.contentops.common.security.AuthContext;
import com.contentops.common.knowledge.TavilySearchService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 热点监控业务：数据源拉取 → 快照入库 → 查询最新热点。
 *
 * <p>选题模块（TopicPlanningAgent 工具 / 前端热点选题）统一从这里取数，
 * 拉取失败时回退到最近一次入库快照，保证可用性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendService {

    private final TrendProvider trendProvider;
    private final TrendRepository repository;
    private final TrendProperties properties;
    private final TrendSubscriptionRepository subscriptionRepository;
    private final TrendKeywordHitRepository keywordHitRepository;
    private final TrendAnalysisService analysisService;
    private final TrendBurstEventRepository burstEventRepository;
    private final TrendNotificationService notificationService;
    private final TavilySearchService tavilySearchService;

    /**
     * 拉取并入库最新热点快照。
     *
     * @return 本次入库条数
     */
    public int refreshHotspots() {
        if (!properties.isEnabled()) {
            log.debug("[Trend] 热点监控已关闭，跳过刷新");
            return 0;
        }
        try {
            List<TrendHotspot> hotspots = trendProvider.fetchHotspots(null, 50);
            repository.saveAll(hotspots);
            attachBurst(hotspots);
            List<TrendBurstEvent> burstEvents = persistBurstEvents(hotspots);
            int hits = recordKeywordHits(hotspots);
            log.info("[Trend] 热点快照刷新完成: provider={}, items={}, bursts={}, hits={}",
                    trendProvider.name(), hotspots.size(), burstEvents.size(), hits);
            if (hits > 0) {
                log.info("[Trend] 关键词命中记录: hits={}", hits);
            }
            notificationService.notifyBursts(burstEvents);
            return hotspots.size();
        } catch (Exception e) {
            log.warn("[Trend] 热点刷新失败，保留最近快照: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 查询最新热点（优先内存快照路径：直接返回 provider 数据并同步入库，
     * 入库失败不影响返回）。
     */
    public List<TrendHotspot> listLatest(String platform, Integer limit) {
        return listLatest(platform, limit, false);
    }

    public List<TrendHotspot> listLatest(String platform, Integer limit, boolean watchOnly) {
        return listLatest(platform, limit, watchOnly, false);
    }

    /**
     * 查询最新热点；watchOnly=true 时仅返回与用户自定义监控方向匹配的热点；
     * burstOnly=true 时仅返回突发热点（新上榜/飙升/上升）。
     */
    public List<TrendHotspot> listLatest(String platform, Integer limit, boolean watchOnly, boolean burstOnly) {
        return listLatest(platform, limit, watchOnly, burstOnly, "latest");
    }

    /**
     * 查询热点；timeRange 支持 latest（当前榜）/ 1h / 24h / 7d（历史窗口，含已下榜主题）。
     */
    public List<TrendHotspot> listLatest(
            String platform, Integer limit, boolean watchOnly, boolean burstOnly, String timeRange) {
        int safeLimit = limit == null || limit <= 0 ? properties.getDefaultLimit()
                : Math.min(limit, 100);
        int hours = parseTimeRangeHours(timeRange);
        if (hours > 0) {
            java.sql.Timestamp since =
                    java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(hours));
            List<TrendHotspot> window =
                    repository.findLatestInWindow(platform, since, Math.max(safeLimit, 100));
            attachBurst(window);
            if (watchOnly) {
                window = filterBySubscriptions(window, safeLimit);
            }
            return burstOnly
                    ? window.stream().filter(h -> h.getBurstLabel() != null).limit(safeLimit).toList()
                    : window;
        }
        // watchOnly/burstOnly 时先取更大的候选池再过滤，避免小样本漏匹配
        int baseLimit = (watchOnly || burstOnly) ? Math.max(safeLimit, 100) : safeLimit;
        // 优先返回已入库快照（有历史轨迹），空库时实时拉取兜底
        List<TrendHotspot> stored = platform == null || platform.isBlank()
                ? repository.findLatestAll(baseLimit)
                : repository.findLatest(platform, baseLimit);
        if (!stored.isEmpty()) {
            List<TrendHotspot> result = watchOnly ? filterBySubscriptions(stored, safeLimit) : stored;
            attachBurst(result);
            return burstOnly
                    ? result.stream().filter(h -> h.getBurstLabel() != null).limit(safeLimit).toList()
                    : result;
        }
        List<TrendHotspot> fetched = trendProvider.fetchHotspots(platform, baseLimit);
        repository.saveAll(fetched);
        List<TrendHotspot> result = watchOnly ? filterBySubscriptions(fetched, safeLimit) : fetched;
        attachBurst(result);
        return burstOnly
                ? result.stream().filter(h -> h.getBurstLabel() != null).limit(safeLimit).toList()
                : result;
    }

    // ───────────────────────────── 监控方向订阅 ─────────────────────────────

    public List<TrendSubscription> listSubscriptions() {
        return subscriptionRepository.listByOwner(currentOwner());
    }

    public TrendSubscription addSubscription(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("监控方向不能为空");
        }
        String ownerId = currentOwner();
        String subscriptionId = UUID.randomUUID().toString();
        if (!subscriptionRepository.create(subscriptionId, ownerId, keyword.trim())) {
            // 已存在：返回已有订阅
            return subscriptionRepository.listByOwner(ownerId).stream()
                    .filter(s -> s.getKeyword().equalsIgnoreCase(keyword.trim()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("监控方向已存在"));
        }
        log.info("[Trend] 新增监控方向: owner={}, keyword={}", ownerId, keyword);
        return subscriptionRepository.findById(subscriptionId, ownerId)
                .orElseThrow(() -> new IllegalStateException("监控方向创建失败"));
    }

    public void removeSubscription(String subscriptionId) {
        subscriptionRepository.delete(subscriptionId, currentOwner());
    }

    /**
     * 启用/暂停监控方向（鱼皮式关键词启停）。
     *
     * @return 订阅是否存在
     */
    public boolean setSubscriptionEnabled(String subscriptionId, boolean enabled) {
        return subscriptionRepository.updateEnabled(subscriptionId, currentOwner(), enabled);
    }

    /**
     * 关键词驱动抓取：在最新快照（必要时实时拉取）中按关键词搜索，
     * 按热度排序返回，支撑前端「关键词搜索」入口。
     */
    public List<TrendHotspot> search(String query, String platform, Integer limit) {
        int safeLimit = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        List<TrendHotspot> pool = platform == null || platform.isBlank()
                ? repository.findLatestAll(100)
                : repository.findLatest(platform, 100);
        if (pool.isEmpty()) {
            pool = trendProvider.fetchHotspots(platform, 100);
            repository.saveAll(pool);
        }
        if (query == null || query.isBlank()) {
            return pool.stream().limit(safeLimit).toList();
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<TrendHotspot> result = pool.stream()
                .filter(h -> matches(h, q))
                .sorted(java.util.Comparator
                        .comparing(TrendHotspot::getHeat,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(h -> h.getRank() == null ? Integer.MAX_VALUE : h.getRank()))
                .limit(safeLimit)
                .toList();
        attachBurst(result);
        attachAnalysis(result, query);
        return result;
    }

    private int parseTimeRangeHours(String timeRange) {
        if (timeRange == null || timeRange.isBlank() || "latest".equalsIgnoreCase(timeRange)) {
            return 0;
        }
        return switch (timeRange.trim().toLowerCase(Locale.ROOT)) {
            case "1h" -> 1;
            case "24h" -> 24;
            case "7d" -> 168;
            default -> {
                try {
                    yield Integer.parseInt(timeRange.trim());
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
        };
    }

    /**
     * 全网搜索：平台热榜内搜索（必返）+ Tavily 全网/新闻聚合（配置 Key 后启用）。
     */
    public Map<String, Object> webSearch(String query, String platform, Integer limit) {
        List<TrendHotspot> local = search(query, platform, limit);
        boolean webAvailable = tavilySearchService.isAvailable();
        List<WebSearchHit> web = new java.util.ArrayList<>();
        if (webAvailable) {
            try {
                tavilySearchService.searchStructured(query, 5)
                        .forEach(r -> web.add(toHit("tavily-web", r)));
                tavilySearchService.searchNewsStructured(query, 5, "week")
                        .forEach(r -> web.add(toHit("tavily-news", r)));
            } catch (Exception e) {
                log.warn("[Trend] 全网搜索失败（降级为热榜内搜索）: {}", e.getMessage());
            }
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("query", query);
        data.put("total", local.size());
        data.put("hotspots", local);
        data.put("webAvailable", webAvailable);
        data.put("web", web);
        return data;
    }

    private WebSearchHit toHit(String source, TavilySearchService.TavilyResult r) {
        return WebSearchHit.builder()
                .source(source)
                .title(r.getTitle())
                .url(r.getUrl())
                .content(r.getContent() != null && r.getContent().length() > 200
                        ? r.getContent().substring(0, 200) + "…" : r.getContent())
                .score(r.getScore())
                .build();
    }

    /**
     * 主题趋势：单平台热度/排名时间序列 + 跨平台对比 + 上榜时长。
     */
    public Map<String, Object> trendHistory(String title, String platform, Integer hours) {
        int h = hours == null || hours <= 0 ? 24 : Math.min(hours, 168);
        java.sql.Timestamp since = java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(h));
        List<Map<String, Object>> points = new java.util.ArrayList<>();
        if (platform != null && !platform.isBlank()) {
            repository.findHistory(platform, title, since).forEach(p -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("capturedAt", p.getCapturedAt());
                m.put("heat", p.getHeat());
                m.put("rank", p.getRank());
                points.add(m);
            });
        }
        List<Map<String, Object>> platforms = new java.util.ArrayList<>();
        repository.findPlatformHeat(title, since).forEach(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("platform", p.getPlatform());
            m.put("heat", p.getHeat());
            m.put("rank", p.getRank());
            m.put("url", p.getUrl());
            platforms.add(m);
        });
        LocalDateTime firstSeen = repository.findFirstSeenAny(title)
                .map(TrendHotspot::getCapturedAt)
                .orElse(LocalDateTime.now());
        double uptimeHours = Math.max(0, Duration.between(firstSeen, LocalDateTime.now()).toMinutes() / 60.0);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("title", title);
        data.put("platform", platform);
        data.put("points", points);
        data.put("platforms", platforms);
        data.put("firstSeenAt", firstSeen);
        data.put("uptimeHours", Math.round(uptimeHours * 10.0) / 10.0);
        return data;
    }

    /** 最近的关键词命中记录（keyword 为空时返回全部） */
    public List<TrendKeywordHit> recentKeywordHits(String keyword, Integer limit) {
        return recentKeywordHits(keyword, limit, "latest");
    }

    public List<TrendKeywordHit> recentKeywordHits(String keyword, Integer limit, String timeRange) {
        int safeLimit = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        int hours = parseTimeRangeHours(timeRange);
        if (hours > 0) {
            java.sql.Timestamp since =
                    java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(hours));
            return keywordHitRepository.findRecentSince(currentOwner(), keyword, since, safeLimit);
        }
        return keywordHitRepository.findRecent(currentOwner(), keyword, safeLimit);
    }

    /** 最近的突发热点事件（platform 为空时返回全部） */
    public List<TrendBurstEvent> recentBurstEvents(String platform, Integer limit) {
        return recentBurstEvents(platform, limit, "latest");
    }

    public List<TrendBurstEvent> recentBurstEvents(String platform, Integer limit, String timeRange) {
        int safeLimit = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        int hours = parseTimeRangeHours(timeRange);
        if (hours > 0) {
            java.sql.Timestamp since =
                    java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(hours));
            return burstEventRepository.findRecentSince(platform, since, safeLimit);
        }
        return burstEventRepository.findRecent(platform, safeLimit);
    }

    private boolean matches(TrendHotspot h, String q) {
        String title = h.getTitle() == null ? "" : h.getTitle().toLowerCase(Locale.ROOT);
        String category = h.getCategory() == null ? "" : h.getCategory().toLowerCase(Locale.ROOT);
        String summary = h.getSummary() == null ? "" : h.getSummary().toLowerCase(Locale.ROOT);
        return title.contains(q) || category.contains(q) || summary.contains(q);
    }

    /**
     * 关键词驱动监控核心：把「已启用监控方向」与本次快照热点做匹配并落库，
     * 形成命中轨迹（供 P1 突发热点检测 / 通知回溯）。
     */
    private int recordKeywordHits(List<TrendHotspot> hotspots) {
        if (hotspots.isEmpty()) {
            return 0;
        }
        List<TrendSubscription> subscriptions = subscriptionRepository.listAllEnabled();
        if (subscriptions.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int total = 0;
        for (TrendSubscription sub : subscriptions) {
            String keyword = sub.getKeyword() == null ? "" : sub.getKeyword().trim().toLowerCase(Locale.ROOT);
            if (keyword.isEmpty()) {
                continue;
            }
            List<TrendKeywordHit> hits = hotspots.stream()
                    .filter(h -> containsKeyword(h, keyword))
                    .map(h -> TrendKeywordHit.builder()
                            .hitId(UUID.randomUUID().toString())
                            .ownerId(sub.getOwnerId())
                            .keyword(sub.getKeyword())
                            .platform(h.getPlatform())
                            .title(h.getTitle())
                            .url(h.getUrl())
                            .heat(h.getHeat())
                            .rank(h.getRank())
                            .category(h.getCategory())
                            .summary(h.getSummary())
                            .capturedAt(now)
                            .build())
                    .toList();
            if (!hits.isEmpty()) {
                keywordHitRepository.saveAll(hits);
                total += hits.size();
            }
        }
        return total;
    }

    /**
     * 把本轮检测到的突发热点持久化为事件，供历史回溯与通知使用。
     */
    private List<TrendBurstEvent> persistBurstEvents(List<TrendHotspot> hotspots) {
        List<TrendBurstEvent> events = hotspots.stream()
                .filter(h -> h.getBurstLabel() != null)
                .map(h -> TrendBurstEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .platform(h.getPlatform())
                        .title(h.getTitle())
                        .url(h.getUrl())
                        .heat(h.getHeat())
                        .prevHeat(h.getPrevHeat())
                        .rank(h.getRank())
                        .prevRank(h.getPrevRank())
                        .heatDelta(h.getHeatDelta())
                        .rankDelta(h.getRankDelta())
                        .burstLabel(h.getBurstLabel())
                        .burstScore(h.getBurstScore())
                        .capturedAt(h.getCapturedAt())
                        .build())
                .toList();
        if (!events.isEmpty()) {
            burstEventRepository.saveAll(events);
        }
        return events;
    }

    private boolean containsKeyword(TrendHotspot h, String keyword) {
        String title = h.getTitle() == null ? "" : h.getTitle().toLowerCase(Locale.ROOT);
        String category = h.getCategory() == null ? "" : h.getCategory().toLowerCase(Locale.ROOT);
        String summary = h.getSummary() == null ? "" : h.getSummary().toLowerCase(Locale.ROOT);
        return title.contains(keyword) || category.contains(keyword) || summary.contains(keyword);
    }

    private List<TrendHotspot> filterBySubscriptions(List<TrendHotspot> hotspots, int limit) {
        List<TrendSubscription> subscriptions = subscriptionRepository.listEnabledByOwner(currentOwner());
        if (subscriptions.isEmpty()) {
            return List.of();
        }
        List<String> keywords = subscriptions.stream()
                .map(TrendSubscription::getKeyword)
                .map(k -> k.toLowerCase(Locale.ROOT))
                .toList();
        List<TrendHotspot> result = hotspots.stream()
                .filter(h -> {
                    String title = h.getTitle() == null ? "" : h.getTitle().toLowerCase(Locale.ROOT);
                    String category = h.getCategory() == null ? "" : h.getCategory().toLowerCase(Locale.ROOT);
                    String haystack = title + " " + category;
                    return keywords.stream().anyMatch(haystack::contains);
                })
                .limit(limit)
                .toList();
        attachAnalysis(result, String.join("、", keywords));
        return result;
    }

    /**
     * 给热点附加 AI 分析（相关性/真假/摘要）。
     * 分析失败自动降级：不附加，接口照常返回。
     */
    private void attachAnalysis(List<TrendHotspot> hotspots, String context) {
        if (hotspots.isEmpty() || !properties.getAnalysis().isEnabled()) {
            return;
        }
        try {
            java.util.Map<String, TrendAnalysis> analysisMap =
                    analysisService.analyze(hotspots, context);
            if (!analysisMap.isEmpty()) {
                hotspots.forEach(h -> h.setAnalysis(analysisMap.get(h.getId())));
            }
        } catch (Exception e) {
            log.warn("[Trend] 热点分析附加失败（降级）: {}", e.getMessage());
        }
    }

    /**
     * 突发热点检测：与上一快照的同主题条目对比热度涨幅/排名上升，
     * 无历史记录视为「新上榜」；热度环比 ≥ 阈值 → 「飙升」；涨幅或排名跃升 → 「上升」。
     */
    private void attachBurst(List<TrendHotspot> hotspots) {
        if (hotspots == null || hotspots.isEmpty()) {
            return;
        }
        TrendProperties.Burst cfg = properties.getBurst();
        for (TrendHotspot h : hotspots) {
            if (h.getCapturedAt() == null || h.getTitle() == null || h.getTitle().isBlank()) {
                continue;
            }
            java.sql.Timestamp before = java.sql.Timestamp.valueOf(h.getCapturedAt());
            java.util.Optional<TrendHotspot> prevOpt =
                    repository.findPrevious(h.getPlatform(), h.getTitle(), before);
            java.util.Optional<TrendHotspot> firstOpt =
                    repository.findFirstSeen(h.getPlatform(), h.getTitle());
            h.setFirstSeenAt(firstOpt.map(TrendHotspot::getCapturedAt).orElse(h.getCapturedAt()));
            if (prevOpt.isEmpty()) {
                h.setIsNew(true);
                h.setBurstLabel("新上榜");
                h.setBurstScore(100);
                continue;
            }
            TrendHotspot prev = prevOpt.get();
            h.setPrevHeat(prev.getHeat());
            h.setPrevRank(prev.getRank());
            Long heatDelta = null;
            Double heatRatio = null;
            if (h.getHeat() != null && prev.getHeat() != null && prev.getHeat() != 0) {
                heatDelta = h.getHeat() - prev.getHeat();
                heatRatio = (double) heatDelta / prev.getHeat();
            }
            Integer rankDelta = null;
            if (h.getRank() != null && prev.getRank() != null) {
                rankDelta = prev.getRank() - h.getRank();
            }
            boolean spike = heatRatio != null && heatRatio > 0
                    && heatRatio >= cfg.getHeatRatioThreshold();
            boolean rise = (heatRatio != null && heatRatio > 0
                    && heatRatio >= cfg.getHeatRiseThreshold())
                    || (rankDelta != null && rankDelta >= cfg.getRankRiseThreshold());
            String label = spike ? "飙升" : (rise ? "上升" : null);
            int score = (heatRatio != null && heatRatio > 0 ? (int) Math.round(heatRatio * 100) : 0)
                    + (rankDelta != null && rankDelta > 0 ? rankDelta * 2 : 0);
            h.setHeatDelta(heatDelta);
            h.setRankDelta(rankDelta);
            h.setIsNew(false);
            h.setBurstLabel(label);
            h.setBurstScore(label != null ? score : null);
        }
    }

    private String currentOwner() {
        String userId = AuthContext.currentUserId();
        return userId == null ? "anonymous" : userId;
    }

    public List<String> supportedPlatforms() {
        return trendProvider.supportedPlatforms();
    }
}
