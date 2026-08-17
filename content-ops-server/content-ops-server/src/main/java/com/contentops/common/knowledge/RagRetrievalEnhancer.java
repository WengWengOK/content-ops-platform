package com.contentops.common.knowledge;

import com.contentops.common.knowledge.KnowledgeBaseService.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RAG 检索增强器（v1.2.0 RAG 知识库 P0 遗留项）。
 *
 * <p>在 TopicAgent 与 OptimizeAgent 调用前，通过 RAG 检索历史相似内容并注入上下文，
 * 让 Agent 决策具备「历史记忆」——选题时参考历史选题与表现，优化时参考历史模式与教训，
 * 避免每次从零开始、重复踩坑。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #retrieveHistoricalContext(String, String, int)} — 检索历史相似内容，
 *       格式化为可注入 Agent prompt 的上下文字符串</li>
 *   <li>{@link #retrievePerformancePatterns(String, String)} — 检索历史表现数据，
 *       为 OptimizeAgent 提供历史表现匹配与可复用模式</li>
 * </ul>
 *
 * <p>依赖已有的 {@link KnowledgeBaseService}（PGVector + BGE 嵌入）完成底层向量检索，
 * 本类仅负责「检索编排 + 结果格式化 + 上下文注入」。
 *
 * <p><b>降级策略：</b>当 RAG 功能关闭或知识库不可用时，返回空字符串，Agent 退化为无记忆模式，
 * 不阻断主流程。
 */
@Slf4j
@Component
public class RagRetrievalEnhancer {

    private static final String TYPE_ARTICLE = "article";
    private static final String TYPE_TOPIC_PLAN = "topic_plan";
    private static final String TYPE_ANALYSIS_REPORT = "analysis_report";

    private final KnowledgeBaseService knowledgeBaseService;
    private final RagProperties properties;

    public RagRetrievalEnhancer(KnowledgeBaseService knowledgeBaseService, RagProperties properties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
        log.info("RagRetrievalEnhancer initialized: enabled={}, maxResults={}, minScore={}, "
                        + "contextInjection[topic={}, optimization={}]",
                properties.isEnabled(), properties.getMaxResults(), properties.getMinScore(),
                properties.getContextInjection().isTopicPlanning(),
                properties.getContextInjection().isOptimization());
    }

    /**
     * 检索历史相似内容并格式化为可注入 Agent prompt 的上下文字符串。
     *
     * <p>检索流程：
     * <ol>
     *   <li>功能关闭或知识库不可用时返回空字符串</li>
     *   <li>按 {@code query} 做语义检索，使用 {@link RagProperties#getMinScore()} 作为阈值</li>
     *   <li>若提供 {@code niche}，进一步过滤同领域结果</li>
     *   <li>将结果格式化为带编号的上下文块，标注来源类型与相似度</li>
     * </ol>
     *
     * <p>典型用于 TopicAgent：检索与当前选题意图相似的历史选题/文章，供 Agent 参考。
     *
     * @param query      检索查询（选题意图、关键词等）
     * @param niche      账号领域/赛道（用于过滤，可为空表示不限领域）
     * @param maxResults 最大返回数（0 或负数使用配置默认值）
     * @return 可直接拼接到 Agent prompt 的上下文字符串；无结果时返回空字符串
     */
    public String retrieveHistoricalContext(String query, String niche, int maxResults) {
        if (!properties.isEnabled()) {
            log.debug("RAG disabled, return empty context for query='{}'", query);
            return "";
        }
        if (!knowledgeBaseService.isAvailable()) {
            log.warn("Knowledge base unavailable, return empty context for query='{}'", query);
            return "";
        }
        if (query == null || query.isBlank()) {
            log.debug("Empty query, skip RAG retrieval");
            return "";
        }

        int limit = maxResults > 0 ? maxResults : properties.getMaxResults();
        List<SearchResult> results = knowledgeBaseService.searchSimilar(
                query, limit, properties.getMinScore());

        List<SearchResult> filtered = filterByNiche(results, niche);
        if (filtered.isEmpty()) {
            log.info("RAG retrieval for query='{}' niche='{}' returned 0 results after filtering", query, niche);
            return "";
        }

        log.info("RAG retrieval for query='{}' niche='{}' returned {} results", query, niche, filtered.size());
        return formatHistoricalContext(query, niche, filtered);
    }

    /**
     * 检索历史表现数据，为 OptimizeAgent 提供历史匹配与可复用模式。
     *
     * <p>检索流程：
     * <ol>
     *   <li>功能关闭或知识库不可用时返回空字符串</li>
     *   <li>优先检索 {@code analysis_report} 类型内容（含历史趋势与策略调整）</li>
     *   <li>补充检索 {@code article} 类型内容（含历史表现 metrics 元数据）</li>
     *   <li>按 {@code niche} 过滤，按 {@code timeRange} 做轻量时间标注</li>
     *   <li>格式化为「历史表现模式」上下文，标注表现好坏与可复用结论</li>
     * </ol>
     *
     * <p>典型用于 OptimizeAgent：提供历史哪些策略有效/无效，避免重复试错。
     *
     * @param niche     账号领域/赛道
     * @param timeRange 时间范围描述（如 "近30天"、"last_30d"），仅用于上下文标注与提示，
     *                  不做硬性时间过滤（依赖入库时写入的 timestamp 元数据）
     * @return 可注入 OptimizeAgent prompt 的历史表现模式上下文；无结果时返回空字符串
     */
    public String retrievePerformancePatterns(String niche, String timeRange) {
        if (!properties.isEnabled()) {
            log.debug("RAG disabled, return empty performance patterns");
            return "";
        }
        if (!knowledgeBaseService.isAvailable()) {
            log.warn("Knowledge base unavailable, return empty performance patterns");
            return "";
        }

        String query = buildPerformanceQuery(niche);
        int limit = properties.getMaxResults();

        // 优先检索历史分析报告（含策略调整与趋势结论）
        List<SearchResult> reports = knowledgeBaseService.searchByType(query, TYPE_ANALYSIS_REPORT, limit);
        // 补充检索历史文章（含 metrics 元数据，可提取表现高低）
        List<SearchResult> articles = knowledgeBaseService.searchByType(query, TYPE_ARTICLE, limit);

        List<SearchResult> nicheReports = filterByNiche(reports, niche);
        List<SearchResult> nicheArticles = filterByNiche(articles, niche);

        if (nicheReports.isEmpty() && nicheArticles.isEmpty()) {
            log.info("Performance patterns retrieval for niche='{}' returned 0 results", niche);
            return "";
        }

        log.info("Performance patterns retrieval for niche='{}' timeRange='{}' returned {} reports, {} articles",
                niche, timeRange, nicheReports.size(), nicheArticles.size());
        return formatPerformancePatterns(niche, timeRange, nicheReports, nicheArticles);
    }

    /**
     * 判断是否应为指定阶段注入 RAG 上下文。
     * <p>供编排层在调用 Agent 前快速判断是否需要触发检索。
     *
     * @param stageCode Agent 阶段代码（对应 AgentStage.getCode()）
     * @return 该阶段是否启用上下文注入
     */
    public boolean shouldInjectContext(String stageCode) {
        if (!properties.isEnabled() || stageCode == null) {
            return false;
        }
        return switch (stageCode) {
            case "topic-planning" -> properties.getContextInjection().isTopicPlanning();
            case "optimization" -> properties.getContextInjection().isOptimization();
            default -> false;
        };
    }

    // ──────────────────── 内部工具方法 ────────────────────

    /**
     * 按 niche 过滤检索结果（niche 为空时不过滤）。
     */
    private List<SearchResult> filterByNiche(List<SearchResult> results, String niche) {
        List<SearchResult> filtered = new ArrayList<>();
        if (niche == null || niche.isBlank()) {
            return results != null ? results : filtered;
        }
        if (results == null) {
            return filtered;
        }
        for (SearchResult r : results) {
            String resultNiche = r.metadata() != null ? r.metadata().get("niche") : null;
            if (niche.equalsIgnoreCase(resultNiche)) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    /**
     * 格式化历史相似内容为可注入的上下文。
     */
    private String formatHistoricalContext(String query, String niche, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 历史相似内容参考（RAG 检索）\n\n");
        sb.append("> 检索意图: ").append(query).append("\n");
        if (niche != null && !niche.isBlank()) {
            sb.append("> 领域: ").append(niche).append("\n");
        }
        sb.append("> 命中条数: ").append(results.size()).append("\n\n");

        int idx = 1;
        for (SearchResult r : results) {
            sb.append("### ").append(idx++).append(". ");
            sb.append(formatTitle(r)).append("\n");
            sb.append("- 相似度: ").append(String.format(Locale.ROOT, "%.2f", r.score())).append("\n");
            appendMetaLine(sb, r);
            sb.append("- 内容摘要: ").append(truncate(r.content(), 500)).append("\n\n");
        }

        sb.append("请基于以上历史内容进行参考而非照搬，避免重复选题与已验证低效方向。\n");
        return sb.toString();
    }

    /**
     * 格式化历史表现模式为可注入的上下文。
     */
    private String formatPerformancePatterns(String niche, String timeRange,
                                             List<SearchResult> reports, List<SearchResult> articles) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 历史表现模式参考（RAG 检索）\n\n");
        if (niche != null && !niche.isBlank()) {
            sb.append("> 领域: ").append(niche).append("\n");
        }
        if (timeRange != null && !timeRange.isBlank()) {
            sb.append("> 时间范围: ").append(timeRange).append("\n");
        }
        sb.append("\n");

        if (!reports.isEmpty()) {
            sb.append("### 历史分析报告与策略结论\n\n");
            int idx = 1;
            for (SearchResult r : reports) {
                sb.append(idx++).append(". ");
                sb.append(formatTitle(r)).append("\n");
                sb.append("   - 相似度: ").append(String.format(Locale.ROOT, "%.2f", r.score())).append("\n");
                appendMetaLine(sb, r);
                sb.append("   - 结论摘要: ").append(truncate(r.content(), 400)).append("\n\n");
            }
        }

        if (!articles.isEmpty()) {
            sb.append("### 历史高/低表现内容样本\n\n");
            int idx = 1;
            for (SearchResult r : articles) {
                String metrics = extractMetric(r);
                sb.append(idx++).append(". ");
                sb.append(formatTitle(r)).append("\n");
                sb.append("   - 相似度: ").append(String.format(Locale.ROOT, "%.2f", r.score())).append("\n");
                if (metrics != null) {
                    sb.append("   - 历史表现: ").append(metrics).append("\n");
                }
                sb.append("   - 内容摘要: ").append(truncate(r.content(), 300)).append("\n\n");
            }
        }

        sb.append("请基于以上历史表现模式识别可复用的高效策略与应规避的低效方向，"
                + "避免重复试错，结合最新数据校准策略。\n");
        return sb.toString();
    }

    private void appendMetaLine(StringBuilder sb, SearchResult r) {
        if (r.metadata() == null) {
            return;
        }
        String type = r.metadata().get("type");
        String agent = r.metadata().get("agent");
        String ts = r.metadata().get("timestamp");
        List<String> parts = new ArrayList<>();
        if (type != null) parts.add("类型=" + type);
        if (agent != null) parts.add("来源=" + agent);
        if (ts != null) parts.add("时间=" + ts);
        if (!parts.isEmpty()) {
            sb.append("- 元数据: ").append(String.join(", ", parts)).append("\n");
        }
    }

    private String formatTitle(SearchResult r) {
        if (r.metadata() != null) {
            String title = r.metadata().get("title");
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return truncate(r.content(), 40);
    }

    private String extractMetric(SearchResult r) {
        if (r.metadata() == null) {
            return null;
        }
        String metrics = r.metadata().get("metrics");
        if (metrics != null && !metrics.isBlank()) {
            return metrics;
        }
        String platform = r.metadata().get("platform");
        return platform != null ? "平台=" + platform : null;
    }

    private String buildPerformanceQuery(String niche) {
        return "历史表现 分析 策略 表现" + (niche != null ? " " + niche : "");
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max) + "...";
    }
}
