package com.contentops.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.contentops.common.observability.LlmTraceContext;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI 热点分析层（鱼皮式 AI 内容审核）：
 * 对热点条目做「与监控关键词/账号定位的相关性评分 + 真假识别（可信度）+ 一句话摘要」。
 *
 * <p>成本控制：
 * <ul>
 *   <li>默认 10 条/次模型调用（可配 batch-size）；</li>
 *   <li>按「关键词+平台+标题」缓存 24h，重复查询不重复调用；</li>
 *   <li>模型调用失败时静默降级（返回空分析，接口仍正常返回热点）。</li>
 * </ul>
 */
@Slf4j
@Service
public class TrendAnalysisService {

    private static final String SYSTEM_PROMPT =
            "你是资深的内容运营热点分析师。对每条热点给出："
                    + "1) relevance：与给定监控方向/账号定位的相关性评分（0-100 整数）；"
                    + "2) credibility：可信度评分（0-100 整数），越低越疑似谣言、标题党或营销炒作；"
                    + "3) summary：一句话摘要（不超过 50 字，突出信息增量）；"
                    + "4) riskFlag：是否疑似谣言/夸大（true/false）。"
                    + "只输出严格 JSON 数组，格式：[{\"id\":\"原始id\",\"relevance\":80,\"credibility\":70,\"summary\":\"…\",\"riskFlag\":false}]，不要输出任何其他文字。";

    private final @Qualifier("formattingChatModel") ChatModel chatModel;
    private final TrendProperties properties;
    private final ObjectMapper objectMapper;

    private final Cache<String, TrendAnalysis> cache;

    public TrendAnalysisService(
            @Qualifier("formattingChatModel") ChatModel chatModel,
            TrendProperties properties,
            ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
        long ttlMinutes = properties.getAnalysis().getCacheTtlMinutes() > 0
                ? properties.getAnalysis().getCacheTtlMinutes()
                : 1_440;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .maximumSize(2_000)
                .build();
        log.info("TrendAnalysisService initialized: enabled={}, batchSize={}, cacheTtlMinutes={}",
                properties.getAnalysis().isEnabled(),
                properties.getAnalysis().getBatchSize(),
                ttlMinutes);
    }

    /**
     * 批量分析热点，返回 hotspotId → 分析结果；失败条目不返回（前端不展示）。
     *
     * @param context 监控关键词/账号定位，可为空
     */
    public Map<String, TrendAnalysis> analyze(List<TrendHotspot> hotspots, String context) {
        Map<String, TrendAnalysis> result = new HashMap<>();
        if (!properties.getAnalysis().isEnabled() || hotspots == null || hotspots.isEmpty()) {
            return result;
        }
        String ctx = context == null ? "" : context.trim();
        int batchSize = Math.max(1, properties.getAnalysis().getBatchSize());
        for (int i = 0; i < hotspots.size(); i += batchSize) {
            List<TrendHotspot> batch = hotspots.subList(
                    i, Math.min(i + batchSize, hotspots.size()));
            List<TrendHotspot> uncached = new ArrayList<>();
            Map<String, TrendAnalysis> batchResult = new HashMap<>();
            for (TrendHotspot h : batch) {
                TrendAnalysis cached = cache.getIfPresent(cacheKey(ctx, h));
                if (cached != null) {
                    batchResult.put(h.getId(), cached);
                } else {
                    uncached.add(h);
                }
            }
            if (!uncached.isEmpty()) {
                batchResult.putAll(callModel(uncached, ctx));
            }
            result.putAll(batchResult);
        }
        return result;
    }

    private Map<String, TrendAnalysis> callModel(List<TrendHotspot> batch, String context) {
        Map<String, TrendAnalysis> result = new HashMap<>();
        try {
            String userPrompt = "监控方向/定位：" + (context.isBlank() ? "（无，按内容创作价值评估）" : context)
                    + "\n热点列表：\n" + buildInputJson(batch)
                    + "\n请输出 JSON 数组。";
            LlmTraceContext.set("trend-analysis", "trend-analysis");
            String output;
            try {
                output = chatModel.chat(SYSTEM_PROMPT + "\n\n" + userPrompt);
            } finally {
                LlmTraceContext.clear();
            }
            JsonNode root = parseJsonArray(output);
            if (root == null || !root.isArray()) {
                log.warn("[Trend] AI 分析返回无法解析: len={}", output == null ? 0 : output.length());
                return result;
            }
            for (JsonNode node : root) {
                String id = node.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                TrendAnalysis analysis = TrendAnalysis.builder()
                        .relevance(node.path("relevance").isIntegralNumber()
                                ? node.path("relevance").asInt() : null)
                        .credibility(node.path("credibility").isIntegralNumber()
                                ? node.path("credibility").asInt() : null)
                        .summary(node.path("summary").isTextual()
                                ? node.path("summary").asText() : null)
                        .riskFlag(node.path("riskFlag").isBoolean()
                                ? node.path("riskFlag").asBoolean() : null)
                        .build();
                result.put(id, analysis);
            }
            // 写入缓存（按 batch 中对应 hotspot 的缓存键）
            for (TrendHotspot h : batch) {
                TrendAnalysis a = result.get(h.getId());
                if (a != null) {
                    cache.put(cacheKey(context, h), a);
                }
            }
            log.info("[Trend] AI 分析完成: batch={}, parsed={}", batch.size(), result.size());
        } catch (Exception e) {
            log.warn("[Trend] AI 分析失败（降级，不影响热点返回）: {}", e.getMessage());
        }
        return result;
    }

    private String buildInputJson(List<TrendHotspot> batch) {
        List<Map<String, String>> items = batch.stream().map(h -> {
            Map<String, String> m = new HashMap<>();
            m.put("id", h.getId());
            m.put("platform", h.getPlatform());
            m.put("title", h.getTitle());
            if (h.getCategory() != null) m.put("category", h.getCategory());
            if (h.getSummary() != null && h.getSummary().length() <= 200) m.put("desc", h.getSummary());
            return m;
        }).toList();
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            return batch.toString();
        }
    }

    private JsonNode parseJsonArray(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            // 模型可能夹杂 markdown 代码块，尝试提取 [ ... ] 片段
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readTree(text.substring(start, end + 1));
                } catch (Exception ignored2) {
                    return null;
                }
            }
            return null;
        }
    }

    private String cacheKey(String context, TrendHotspot h) {
        return (context == null ? "" : context)
                + "|" + h.getPlatform()
                + "|" + h.getTitle();
    }
}
