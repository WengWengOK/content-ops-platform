package com.contentops.tools.internal;

import com.contentops.common.api.ToolsContracts.*;
import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.trend.TrendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Phase3 Tools Service 对内 API 契约（Worker/Orchestrator 调用，不对外暴露）。
 *
 * <p>第一阶段（渐进拆分）：直接复用本地 KnowledgeBaseService / TrendService 作为真实实现，
 * 保证接口契约先落地；后续再把 Worker / Orchestrator 里的本地 Service 调用替换成 HTTP 调用。
 *
 * <p>契约清单：
 * <ul>
 *   <li>POST /internal/api/tools/rag/search    → RagSearchResponse（向量检索）</li>
 *   <li>GET  /internal/api/tools/trends        → TrendsResponse（趋势热点查询）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/internal/api/tools")
@RequiredArgsConstructor
public class ToolsInternalContractController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final TrendService trendService;

    /** RAG 向量检索接口 */
    @PostMapping("/rag/search")
    public ResponseEntity<RagSearchResponse> ragSearch(@RequestBody RagSearchRequest req) {
        log.debug("[Tools-Internal] RAG 查询: query={}, accountId={}, niche={}",
                req.getQuery(), req.getAccountId(), req.getNiche());
        try {
            // 调用 legacy-server 中 KnowledgeBaseService.searchSimilar(query, topK, minScore)
            // 第一阶段先不过滤 niche/accountId（metadata 过滤留 Phase3 二次迭代）
            List<KnowledgeBaseService.SearchResult> list = knowledgeBaseService.searchSimilar(
                    req.getQuery(), req.getTopK(), req.getMinScore());
            List<Map<String, Object>> results = list.stream()
                    .map(r -> Map.<String, Object>of(
                            "content", r.content() == null ? "" : r.content(),
                            "score", r.score(),
                            "metadata", r.metadata() == null ? Map.of() : r.metadata()
                    ))
                    .toList();
            return ResponseEntity.ok(RagSearchResponse.builder()
                    .success(true)
                    .results(results)
                    .debug(Map.of("topK", req.getTopK(), "minScore", req.getMinScore()))
                    .build());
        } catch (Exception e) {
            log.warn("[Tools-Internal] RAG 查询失败: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(RagSearchResponse.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
        }
    }

    /** 趋势热点查询接口 */
    @GetMapping("/trends")
    public ResponseEntity<TrendsResponse> trends(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String niche,
            @RequestParam(defaultValue = "20") int limit) {
        log.debug("[Tools-Internal] 趋势查询: platform={}, niche={}, limit={}", platform, niche, limit);
        try {
            List<com.contentops.trend.TrendHotspot> hotspots = trendService.listLatest(platform, limit);
            // 把 TrendHotspot POJO 转 Map（避免 common 依赖 trend 包；字段与 TrendHotspot.java 定义对齐）
            java.util.List<Map<String, Object>> list = hotspots.stream()
                    .limit(limit)
                    .<Map<String, Object>>map(h -> {
                        var m = new java.util.HashMap<String, Object>();
                        m.put("id", safe(h.getId()));
                        m.put("title", safe(h.getTitle()));
                        m.put("platform", safe(h.getPlatform()));
                        m.put("url", safe(h.getUrl()));
                        m.put("heat", h.getHeat() == null ? 0L : h.getHeat());
                        m.put("rank", h.getRank() == null ? 0 : h.getRank());
                        m.put("category", safe(h.getCategory()));
                        m.put("summary", safe(h.getSummary()));
                        m.put("capturedAt", h.getCapturedAt() == null ? "" : h.getCapturedAt().toString());
                        m.put("burstLabel", safe(h.getBurstLabel()));
                        m.put("heatDelta", h.getHeatDelta() == null ? 0L : h.getHeatDelta());
                        m.put("burstScore", h.getBurstScore() == null ? 0 : h.getBurstScore());
                        m.put("isNew", Boolean.TRUE.equals(h.getIsNew()));
                        return java.util.Collections.unmodifiableMap(m);
                    })
                    .toList();
            return ResponseEntity.ok(TrendsResponse.builder().success(true).trends(list).build());
        } catch (Exception e) {
            log.warn("[Tools-Internal] 趋势查询失败: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(TrendsResponse.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
