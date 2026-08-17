package com.contentops.trend;

import com.contentops.common.dto.AgentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.contentops.common.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 热点监控接口（独立模块）：供选题模块/前端直接取热点生成作品。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/trends")
@RequiredArgsConstructor
@Tag(name = "热点监控")
public class TrendController {

    private final TrendService trendService;
    private final TrendNotificationService notificationService;
    private final AuditService auditService;

    @GetMapping
    @Operation(summary = "获取最新热点列表（可按平台/数量筛选）")
    public AgentResponse<Map<String, Object>> list(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean watch,
            @RequestParam(required = false, defaultValue = "false") boolean burst,
            @RequestParam(required = false) String timeRange) {
        List<TrendHotspot> hotspots = trendService.listLatest(platform, limit, watch, burst, timeRange);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("total", hotspots.size());
        data.put("hotspots", hotspots);
        return AgentResponse.success("trend", data);
    }

    @PostMapping("/refresh")
    @Operation(summary = "立即刷新热点快照")
    public AgentResponse<Map<String, Object>> refresh() {
        int count = trendService.refreshHotspots();
        return AgentResponse.success("trend", Map.of(
                "refreshed", true,
                "captured", count));
    }

    @GetMapping("/platforms")
    @Operation(summary = "支持的热榜平台列表")
    public AgentResponse<List<String>> platforms() {
        return AgentResponse.success("trend", trendService.supportedPlatforms());
    }

    @GetMapping("/subscriptions")
    @Operation(summary = "我的热点监控方向列表")
    public AgentResponse<List<TrendSubscription>> subscriptions() {
        return AgentResponse.success("trend", trendService.listSubscriptions());
    }

    @PostMapping("/subscriptions")
    @Operation(summary = "新增监控方向（行业/关键词）")
    public AgentResponse<TrendSubscription> addSubscription(@RequestBody AddSubscriptionRequest request) {
        TrendSubscription subscription = trendService.addSubscription(request.getKeyword());
        auditService.record("TREND_SUBSCRIPTION_ADD", "trend-subscription",
                subscription.getSubscriptionId(), "添加监控方向：" + request.getKeyword());
        return AgentResponse.success("trend", subscription);
    }

    @DeleteMapping("/subscriptions/{subscriptionId}")
    @Operation(summary = "删除监控方向")
    public AgentResponse<Map<String, Object>> removeSubscription(@PathVariable String subscriptionId) {
        trendService.removeSubscription(subscriptionId);
        auditService.record("TREND_SUBSCRIPTION_REMOVE", "trend-subscription",
                subscriptionId, "删除监控方向");
        return AgentResponse.success("trend", Map.of("removed", true));
    }

    @PutMapping("/subscriptions/{subscriptionId}/enabled")
    @Operation(summary = "启用/暂停监控方向（关键词启停）")
    public AgentResponse<Map<String, Object>> setSubscriptionEnabled(
            @PathVariable String subscriptionId,
            @RequestBody SetSubscriptionEnabledRequest request) {
        boolean updated = trendService.setSubscriptionEnabled(subscriptionId, request.isEnabled());
        if (!updated) {
            return AgentResponse.failure("trend", "监控方向不存在或无权限");
        }
        auditService.record("TREND_SUBSCRIPTION_TOGGLE", "trend-subscription",
                subscriptionId, request.isEnabled() ? "启用监控" : "暂停监控");
        return AgentResponse.success("trend", Map.of(
                "subscriptionId", subscriptionId,
                "enabled", request.isEnabled()));
    }

    @GetMapping("/search")
    @Operation(summary = "关键词驱动抓取：跨平台搜索热点（按热度排序）")
    public AgentResponse<Map<String, Object>> search(
            @RequestParam String q,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) Integer limit) {
        List<TrendHotspot> hotspots = trendService.search(q, platform, limit);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("total", hotspots.size());
        data.put("query", q);
        data.put("hotspots", hotspots);
        return AgentResponse.success("trend", data);
    }

    @GetMapping("/web-search")
    @Operation(summary = "全网搜索：热榜内搜索 + Tavily 全网/新闻聚合（配置 Key 后启用）")
    public AgentResponse<Map<String, Object>> webSearch(
            @RequestParam String q,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) Integer limit) {
        return AgentResponse.success("trend", trendService.webSearch(q, platform, limit));
    }

    @GetMapping("/history")
    @Operation(summary = "主题趋势：热度曲线 + 跨平台对比 + 上榜时长")
    public AgentResponse<Map<String, Object>> history(
            @RequestParam String title,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false, defaultValue = "24") Integer hours) {
        return AgentResponse.success("trend", trendService.trendHistory(title, platform, hours));
    }

    @GetMapping("/keyword-hits")
    @Operation(summary = "关键词命中记录（已启用监控方向的最近匹配）")
    public AgentResponse<Map<String, Object>> keywordHits(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timeRange) {
        List<TrendKeywordHit> hits = trendService.recentKeywordHits(keyword, limit, timeRange);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("total", hits.size());
        data.put("hits", hits);
        return AgentResponse.success("trend", data);
    }

    @GetMapping("/bursts")
    @Operation(summary = "突发热点事件记录（新上榜/飙升/上升，最近优先）")
    public AgentResponse<Map<String, Object>> bursts(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timeRange) {
        List<TrendBurstEvent> events = trendService.recentBurstEvents(platform, limit, timeRange);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("total", events.size());
        data.put("bursts", events);
        return AgentResponse.success("trend", data);
    }

    @GetMapping("/notifications/status")
    @Operation(summary = "实时通知状态（WebSocket 在线数 / 邮件是否已配置）")
    public AgentResponse<Map<String, Object>> notificationStatus() {
        return AgentResponse.success("trend", notificationService.status());
    }

    @lombok.Data
    public static class AddSubscriptionRequest {
        private String keyword;
    }

    @lombok.Data
    public static class SetSubscriptionEnabledRequest {
        private boolean enabled = true;
    }
}
