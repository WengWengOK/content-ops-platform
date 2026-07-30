package com.contentops.common.rag;

import com.contentops.common.rag.DocumentChunker.Chunk;
import com.contentops.common.rag.HybridSearchService.HybridSearchResult;
import com.contentops.common.rag.RerankService.RerankCandidate;
import com.contentops.common.rag.RerankService.RerankResult;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 高级 RAG 服务（RAG 全链路升级）。
 *
 * <p>整合 {@link DocumentChunker}、{@link HybridSearchService}、{@link RerankService}，
 * 提供端到端的「检索 + 重排序」能力，并支持查询重写（query rewriting）。
 *
 * <p>核心方法 {@link #retrieveAndRerank(String, Map, int)} 流程：
 * <ol>
 *   <li><b>查询构建</b>：依据 {@link RagProperties.QueryRewrite} 配置进行查询重写
 *       （HyDE / Multi-query）；未启用重写时，对超长查询使用 {@link DocumentChunker}
 *       切分为多个子查询以扩大召回。</li>
 *   <li><b>混合检索</b>：对每个（重写后的）查询调用 {@link HybridSearchService#search}，
 *       跨查询按 chunkId 去重合并，取最大融合得分。</li>
 *   <li><b>重排序</b>：将合并后的候选交给 {@link RerankService#rerank} 二次排序。</li>
 *   <li><b>返回 topK</b>：取重排序后的前 topK 条结果。</li>
 * </ol>
 *
 * <p><b>查询重写策略：</b>
 * <ul>
 *   <li>{@link RagProperties.QueryRewriteStrategy#NONE} —— 不重写（超长查询仍会切分）。</li>
 *   <li>{@link RagProperties.QueryRewriteStrategy#HYDE} —— HyDE：用 LLM 生成假设性答案文档，
 *       以该文档作为检索查询，提升语义匹配召回。</li>
 *   <li>{@link RagProperties.QueryRewriteStrategy#MULTI_QUERY} —— Multi-query：用 LLM 生成
 *       多个查询变体，分别检索后融合结果。</li>
 * </ul>
 *
 * <p><b>降级策略：</b>查询重写需要 {@link ChatModel}，模型不可用或生成异常时回退到原始查询；
 * 重排序失败时由 {@link RerankService} 内部降级；任一阶段异常不阻断，最终尽力返回结果。
 *
 * @see RagProperties.QueryRewrite
 */
@Slf4j
@Component
public class AdvancedRagService {

    private final RagProperties properties;
    private final DocumentChunker chunker;
    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    /** 可选的聊天模型，用于 HyDE / Multi-query 查询重写 */
    private final ObjectProvider<ChatModel> chatModelProvider;

    /** 触发超长查询切分的阈值倍数（相对 chunkSize） */
    private static final int LONG_QUERY_FACTOR = 2;

    /**
     * 构造高级 RAG 服务。
     *
     * @param properties          RAG 配置
     * @param chunker             分块器（用于超长查询切分）
     * @param hybridSearchService 混合检索服务
     * @param rerankService       重排序服务
     * @param chatModelProvider   聊天模型提供者（可选，查询重写使用）
     */
    public AdvancedRagService(RagProperties properties, DocumentChunker chunker,
                              HybridSearchService hybridSearchService, RerankService rerankService,
                              ObjectProvider<ChatModel> chatModelProvider) {
        this.properties = properties;
        this.chunker = chunker;
        this.hybridSearchService = hybridSearchService;
        this.rerankService = rerankService;
        this.chatModelProvider = chatModelProvider;
        RagProperties.QueryRewrite qw = properties.getQueryRewrite();
        log.info("AdvancedRagService initialized: enabled={}, queryRewrite[enabled={}, strategy={}, multiQueryCount={}], chatModel={}",
                properties.isEnabled(), qw.isEnabled(), qw.getStrategy(), qw.getMultiQueryCount(),
                chatModelProvider.getIfAvailable() != null ? "available" : "absent");
    }

    /**
     * 高级 RAG 检索结果。
     *
     * @param chunkId   分块标识
     * @param content   分块文本
     * @param score     重排序后得分
     * @param fusedScore 重排序前混合检索融合得分
     * @param metadata  元数据
     * @param source    结果来源描述
     */
    public record RetrievalResult(String chunkId, String content, double score, double fusedScore,
                                  Map<String, String> metadata, String source) {
    }

    /**
     * 检索并重排序：查询重写 → 混合检索 → 重排序 → 返回 topK。
     *
     * @param query   原始查询
     * @param filters 元数据过滤条件，可为 null
     * @param topK    返回数量（<=0 使用配置默认值）
     * @return 重排序后的检索结果列表
     */
    public List<RetrievalResult> retrieveAndRerank(String query, Map<String, String> filters, int topK) {
        if (!properties.isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        int limit = topK > 0 ? topK : properties.getSearch().getTopK();
        int pool = Math.max(limit, limit * Math.max(1, properties.getSearch().getCandidatePoolFactor()));

        // 1. 查询构建（重写 / 超长查询切分）
        List<String> queries = buildRetrievalQueries(query);
        log.debug("retrieveAndRerank: original='{}', derivedQueries={}", query, queries.size());

        // 2. 混合检索并跨查询合并
        Map<String, MergedCandidate> merged = new HashMap<>();
        for (String q : queries) {
            List<HybridSearchResult> results = hybridSearchService.search(q, pool, filters);
            for (HybridSearchResult r : results) {
                merged.merge(r.chunkId(), new MergedCandidate(r), MergedCandidate::mergeByMaxScore);
            }
        }
        if (merged.isEmpty()) {
            log.info("retrieveAndRerank: no results for query='{}'", query);
            return List.of();
        }

        // 3. 排序并构造重排序候选
        List<MergedCandidate> sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.comparingDouble((MergedCandidate c) -> c.fusedScore).reversed());
        List<RerankCandidate> candidates = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            MergedCandidate c = sorted.get(i);
            candidates.add(new RerankCandidate(c.chunkId, c.content, c.fusedScore, i, c.metadata));
        }

        // 4. 重排序
        List<RerankResult> reranked = rerankService.rerank(query, candidates, limit);
        List<RetrievalResult> output = new ArrayList<>(reranked.size());
        for (RerankResult r : reranked) {
            output.add(new RetrievalResult(r.chunkId(), r.content(), r.rerankScore(),
                    r.originalScore(), r.metadata(), "advanced-rag"));
        }
        log.info("retrieveAndRerank: query='{}', candidates={}, returned={}", query, candidates.size(), output.size());
        return output;
    }

    /**
     * 仅混合检索（不重排序），返回融合排序后的结果。便于在不需要二次排序时直接使用。
     *
     * @param query   查询
     * @param filters 元数据过滤条件
     * @param topK    返回数量
     * @return 检索结果列表
     */
    public List<RetrievalResult> retrieve(String query, Map<String, String> filters, int topK) {
        if (!properties.isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        int limit = topK > 0 ? topK : properties.getSearch().getTopK();
        List<HybridSearchResult> results = hybridSearchService.search(query, limit, filters);
        List<RetrievalResult> output = new ArrayList<>(results.size());
        for (HybridSearchResult r : results) {
            output.add(new RetrievalResult(r.chunkId(), r.content(), r.fusedScore(), r.fusedScore(),
                    r.metadata(), "hybrid"));
        }
        return output;
    }

    /**
     * 查询重写：依据配置策略生成一个或多个检索查询。
     *
     * <p>HyDE 返回假设性答案文档；Multi-query 返回原始查询加多个变体；
     * NONE 返回原始查询（超长查询仍会被切分为子查询）。
     *
     * @param query 原始查询
     * @return 检索查询列表（至少包含一个元素）
     */
    public List<String> rewriteQuery(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        RagProperties.QueryRewrite qw = properties.getQueryRewrite();
        if (!qw.isEnabled() || qw.getStrategy() == RagProperties.QueryRewriteStrategy.NONE) {
            return segmentIfLong(query);
        }
        try {
            return switch (qw.getStrategy()) {
                case NONE -> segmentIfLong(query);
                case HYDE -> {
                    String hyde = generateHyde(query);
                    yield hyde != null && !hyde.isBlank() ? List.of(hyde) : List.of(query);
                }
                case MULTI_QUERY -> {
                    List<String> variants = generateMultiQuery(query);
                    List<String> all = new ArrayList<>();
                    all.add(query);
                    all.addAll(variants);
                    yield all;
                }
            };
        } catch (Exception e) {
            log.warn("Query rewriting failed, falling back to original query", e);
            return List.of(query);
        }
    }

    // ──────────────────── 查询构建 ────────────────────

    /** 构建检索查询列表（重写或超长切分）。 */
    private List<String> buildRetrievalQueries(String query) {
        RagProperties.QueryRewrite qw = properties.getQueryRewrite();
        if (qw.isEnabled() && qw.getStrategy() != RagProperties.QueryRewriteStrategy.NONE) {
            return rewriteQuery(query);
        }
        return segmentIfLong(query);
    }

    /** 超长查询切分：当查询超过阈值时用分块器切成子查询，否则返回原查询。 */
    private List<String> segmentIfLong(String query) {
        int threshold = properties.getChunking().getChunkSize() * LONG_QUERY_FACTOR;
        if (query.length() <= threshold) {
            return List.of(query);
        }
        List<Chunk> segments = chunker.chunk(query, Map.of());
        if (segments.size() <= 1) {
            return List.of(query);
        }
        List<String> queries = new ArrayList<>(segments.size());
        for (Chunk s : segments) {
            if (s.content() != null && !s.content().isBlank()) {
                queries.add(s.content());
            }
        }
        log.debug("Segmented long query ({} chars) into {} sub-queries", query.length(), queries.size());
        return queries.isEmpty() ? List.of(query) : queries;
    }

    /** HyDE：生成假设性答案文档。 */
    private String generateHyde(String query) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            log.debug("HyDE requested but ChatModel unavailable, skipping");
            return null;
        }
        String prompt = properties.getQueryRewrite().getHydePrompt().replace("{query}", query);
        String output = model.chat(prompt);
        return output == null ? null : output.trim();
    }

    /** Multi-query：生成查询变体列表。 */
    private List<String> generateMultiQuery(String query) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            log.debug("Multi-query requested but ChatModel unavailable, skipping");
            return List.of();
        }
        RagProperties.QueryRewrite qw = properties.getQueryRewrite();
        String prompt = qw.getMultiQueryPrompt()
                .replace("{query}", query)
                .replace("{count}", String.valueOf(qw.getMultiQueryCount()));
        String output = model.chat(prompt);
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<String> variants = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            // 去除可能的编号前缀
            trimmed = trimmed.replaceFirst("^\\d+[.、)\\]]\\s*", "");
            if (!trimmed.isBlank() && !trimmed.equalsIgnoreCase(query)) {
                variants.add(trimmed);
            }
        }
        return variants.size() > qw.getMultiQueryCount()
                ? variants.subList(0, qw.getMultiQueryCount())
                : variants;
    }

    // ──────────────────── 内部类型 ────────────────────

    /** 跨查询合并的候选（按 chunkId 去重，取最大融合得分）。 */
    private static final class MergedCandidate {
        final String chunkId;
        String content;
        double fusedScore;
        Map<String, String> metadata;

        MergedCandidate(HybridSearchResult r) {
            this.chunkId = r.chunkId();
            this.content = r.content();
            this.fusedScore = r.fusedScore();
            this.metadata = r.metadata();
        }

        /** 合并策略：保留更高融合得分及对应内容/元数据。 */
        static MergedCandidate mergeByMaxScore(MergedCandidate a, MergedCandidate b) {
            if (b.fusedScore > a.fusedScore) {
                a.fusedScore = b.fusedScore;
                a.content = b.content;
                a.metadata = b.metadata;
            }
            return a;
        }
    }
}
