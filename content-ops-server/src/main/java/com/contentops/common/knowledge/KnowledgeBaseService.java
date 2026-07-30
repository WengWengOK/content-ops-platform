package com.contentops.common.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG knowledge base service backed by PGVector and in-process BGE embeddings.
 *
 * <p>This is the shared vector store for the entire platform — all 6 agents
 * query the same {@link EmbeddingStore} via metadata filters for logical isolation.
 *
 * <p>Key capabilities:
 * <ul>
 *   <li>{@link #ingest} — embed and store content (articles, topic plans, analysis reports)</li>
 *   <li>{@link #searchSimilar} — semantic similarity search across the knowledge base</li>
 *   <li>{@link #ingestArticle} — convenience method for storing historical articles with metadata</li>
 * </ul>
 *
 * <p>The embedding model runs in-process via ONNX Runtime (BGE-small-zh-v1.5 quantized),
 * so no external API key is needed for embeddings. The vector store is PGVector, which
 * requires a PostgreSQL instance with the {@code pgvector} extension.
 *
 * <p>Metadata conventions:
 * <ul>
 *   <li>{@code type} — content type: "article", "topic_plan", "analysis_report", "competitor_data"</li>
 *   <li>{@code agent} — which agent produced this content: "topic-planning", "content-creation", etc.</li>
 *   <li>{@code niche} — the account niche/domain for filtering</li>
 *   <li>{@code workflowId} — the workflow that produced this content</li>
 *   <li>{@code timestamp} — when the content was ingested (ISO-8601 string)</li>
 * </ul>
 */
@Slf4j
@Component
public class KnowledgeBaseService {

    private final KnowledgeBaseProperties properties;
    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> embeddingStore;

    public KnowledgeBaseService(KnowledgeBaseProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        try {
            // Initialize the in-process BGE embedding model (runs locally, no API key needed)
            this.embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
            log.info("Embedding model initialized: BgeSmallZhV15 (quantized), dimension={}",
                    embeddingModel.dimension());

            // Initialize the PGVector embedding store
            this.embeddingStore = PgVectorEmbeddingStore.builder()
                    .host(properties.getPgHost())
                    .port(properties.getPgPort())
                    .database(properties.getPgDatabase())
                    .user(properties.getPgUser())
                    .password(properties.getPgPassword())
                    .table(properties.getTableName())
                    .dimension(embeddingModel.dimension())
                    .useIndex(properties.isUseIndex())
                    .createTable(properties.isCreateTable())
                    .dropTableFirst(properties.isDropTableFirst())
                    .build();
            log.info("PGVector embedding store initialized: table={}, host={}:{}",
                    properties.getTableName(), properties.getPgHost(), properties.getPgPort());
        } catch (Exception e) {
            log.error("Failed to initialize KnowledgeBaseService — vector search will be unavailable", e);
            // Don't rethrow: allow the application to start without PGVector
            // (tools will gracefully degrade to returning empty results)
        }
    }

    @PreDestroy
    public void destroy() {
        if (embeddingStore instanceof AutoCloseable) {
            try {
                ((AutoCloseable) embeddingStore).close();
            } catch (Exception e) {
                log.warn("Error closing embedding store", e);
            }
        }
    }

    /**
     * Check whether the knowledge base is available (PGVector connected + embedding model loaded).
     */
    public boolean isAvailable() {
        return embeddingModel != null && embeddingStore != null;
    }

    /**
     * Ingest a piece of content into the vector store with metadata.
     *
     * @param content  the text content to embed and store
     * @param metadata metadata map (type, agent, niche, workflowId, timestamp, etc.)
     * @return true if ingestion succeeded
     */
    public boolean ingest(String content, Map<String, String> metadata) {
        if (!isAvailable()) {
            log.warn("Knowledge base unavailable, skipping ingest");
            return false;
        }
        try {
            TextSegment segment = TextSegment.from(content,
                    dev.langchain4j.data.document.Metadata.from(metadata));
            Embedding embedding = embeddingModel.embed(segment.text()).content();
            embeddingStore.add(embedding, segment);
            log.debug("Ingested content ({} chars) with metadata: {}", content.length(), metadata);
            return true;
        } catch (Exception e) {
            log.error("Failed to ingest content into knowledge base", e);
            return false;
        }
    }

    /**
     * Convenience method: ingest a historical article with standard metadata.
     *
     * @param title    article title
     * @param body     article body (Markdown)
     * @param niche    account niche/domain
     * @param platform publishing platform
     * @param metrics  performance metrics (e.g., "views:18500,likes:620")
     */
    public void ingestArticle(String title, String body, String niche, String platform, String metrics) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("type", "article");
        metadata.put("agent", "content-creation");
        metadata.put("niche", niche != null ? niche : "unknown");
        metadata.put("platform", platform != null ? platform : "unknown");
        metadata.put("metrics", metrics != null ? metrics : "");
        metadata.put("title", title != null ? title : "");
        metadata.put("timestamp", java.time.Instant.now().toString());

        String fullContent = "标题: " + title + "\n\n" + body;
        ingest(fullContent, metadata);
    }

    /**
     * Ingest a topic plan result for future reference.
     *
     * @param topics    the topic plan content (JSON or text)
     * @param niche     account niche
     * @param workflowId workflow ID for traceability
     */
    public void ingestTopicPlan(String topics, String niche, String workflowId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("type", "topic_plan");
        metadata.put("agent", "topic-planning");
        metadata.put("niche", niche != null ? niche : "unknown");
        metadata.put("workflowId", workflowId != null ? workflowId : "");
        metadata.put("timestamp", java.time.Instant.now().toString());
        ingest(topics, metadata);
    }

    /**
     * Ingest an analysis report for historical reference.
     *
     * @param report     the analysis report content
     * @param niche      account niche
     * @param workflowId workflow ID for traceability
     */
    public void ingestAnalysisReport(String report, String niche, String workflowId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("type", "analysis_report");
        metadata.put("agent", "data-analysis");
        metadata.put("niche", niche != null ? niche : "unknown");
        metadata.put("workflowId", workflowId != null ? workflowId : "");
        metadata.put("timestamp", java.time.Instant.now().toString());
        ingest(report, metadata);
    }

    /**
     * Ingest competitor analysis data.
     *
     * @param competitorData the competitor analysis content
     * @param niche          account niche
     */
    public void ingestCompetitorData(String competitorData, String niche) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("type", "competitor_data");
        metadata.put("agent", "topic-planning");
        metadata.put("niche", niche != null ? niche : "unknown");
        metadata.put("timestamp", java.time.Instant.now().toString());
        ingest(competitorData, metadata);
    }

    /**
     * Search for content similar to the given query.
     *
     * @param query    the search query text
     * @param topK     maximum number of results to return
     * @param minScore minimum similarity score (0.0 - 1.0); pass null to use default
     * @return list of matching text segments with their similarity scores
     */
    public List<SearchResult> searchSimilar(String query, int topK, Double minScore) {
        if (!isAvailable()) {
            log.warn("Knowledge base unavailable, returning empty search results");
            return new ArrayList<>();
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            double threshold = minScore != null ? minScore : properties.getMinScore();
            int maxResults = topK > 0 ? topK : properties.getMaxResults();

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(threshold)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

            List<SearchResult> results = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                double score = match.score() != null ? match.score() : 0.0;
                results.add(new SearchResult(
                        match.embedded().text(),
                        score,
                        extractMetadataMap(match.embedded().metadata())
                ));
            }
            log.debug("Similarity search for '{}' returned {} results (topK={}, minScore={})",
                    query, results.size(), maxResults, threshold);
            return results;
        } catch (Exception e) {
            log.error("Similarity search failed for query: {}", query, e);
            return new ArrayList<>();
        }
    }

    /**
     * Search for content similar to the query, filtered by metadata type.
     *
     * @param query     the search query text
     * @param type      metadata type filter (e.g., "article", "topic_plan", "analysis_report")
     * @param topK      maximum number of results
     * @return list of matching text segments
     */
    public List<SearchResult> searchByType(String query, String type, int topK) {
        List<SearchResult> allResults = searchSimilar(query, topK, null);
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult result : allResults) {
            String resultType = result.metadata().get("type");
            if (type.equals(resultType)) {
                filtered.add(result);
            }
        }
        return filtered;
    }

    /**
     * Search for content filtered by niche.
     */
    public List<SearchResult> searchByNiche(String query, String niche, int topK) {
        List<SearchResult> allResults = searchSimilar(query, topK, null);
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult result : allResults) {
            String resultNiche = result.metadata().get("niche");
            if (niche.equals(resultNiche)) {
                filtered.add(result);
            }
        }
        return filtered;
    }

    private Map<String, String> extractMetadataMap(dev.langchain4j.data.document.Metadata metadata) {
        Map<String, String> map = new HashMap<>();
        if (metadata != null) {
            metadata.toMap().forEach((key, value) -> map.put(key, String.valueOf(value)));
        }
        return map;
    }

    /**
     * Represents a single search result from the vector store.
     */
    public record SearchResult(
            String content,
            double score,
            Map<String, String> metadata
    ) {}
}
