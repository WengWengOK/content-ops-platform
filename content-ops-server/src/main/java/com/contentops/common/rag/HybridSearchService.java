package com.contentops.common.rag;

import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.knowledge.KnowledgeBaseService.SearchResult;
import com.contentops.common.rag.DocumentChunker.Chunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 混合检索服务（RAG 全链路升级）。
 *
 * <p>融合两路检索结果以兼顾「语义相关性」与「精确匹配」：
 * <ul>
 *   <li><b>向量检索</b>：复用 {@link KnowledgeBaseService}（PGVector + BGE 嵌入）做语义相似检索；</li>
 *   <li><b>关键词检索</b>：基于内存 BM25 索引做精确词项匹配，索引由
 *       {@link DocumentIngestionPipeline} 摄入时通过 {@link #indexChunk} 注册。</li>
 * </ul>
 *
 * <p>两路结果使用 <b>Reciprocal Rank Fusion (RRF)</b> 融合排序：
 * <pre>
 *   fusedScore = vectorWeight / (rrfK + vectorRank) + keywordWeight / (rrfK + keywordRank)
 * </pre>
 * 其中 rank 为 1-based 排名，某路未命中的候选项该项贡献为 0；权重经归一化处理。
 *
 * <p><b>配置项：</b>{@code vectorWeight}、{@code keywordWeight}、{@code topK}、
 * {@code rrfK}、{@code candidatePoolFactor}、{@code minScore}、{@code keywordEnabled}、
 * {@code bm25K1}、{@code bm25B}。
 *
 * <p><b>降级策略：</b>知识库不可用时向量路返回空；关键词索引为空或禁用时退化为纯向量检索；
 * 任一路异常不影响另一路。BM25 索引为内存态，重启后需重新摄入文档重建。
 *
 * @see RagProperties.Search
 */
@Slf4j
@Component
public class HybridSearchService {

    private final RagProperties properties;
    private final KnowledgeBaseService knowledgeBaseService;

    /** BM25 内存索引：chunkId → 索引文档 */
    private final ConcurrentHashMap<String, IndexedChunk> index = new ConcurrentHashMap<>();
    /** 各词项的文档频率（df），用于 BM25 IDF 计算 */
    private final ConcurrentHashMap<String, Integer> documentFrequency = new ConcurrentHashMap<>();
    /** 索引文档总数 */
    private final AtomicInteger docCount = new AtomicInteger(0);
    /** 索引文档 token 长度总和，用于计算平均文档长度 */
    private final AtomicLong totalTokenLen = new AtomicLong(0);
    /** 索引统计更新的锁（摄入/删除为低频操作） */
    private final Object indexLock = new Object();

    /**
     * 构造混合检索服务。
     *
     * @param properties         RAG 配置
     * @param knowledgeBaseService 知识库服务（提供向量检索）
     */
    public HybridSearchService(RagProperties properties, KnowledgeBaseService knowledgeBaseService) {
        this.properties = properties;
        this.knowledgeBaseService = knowledgeBaseService;
        RagProperties.Search s = properties.getSearch();
        log.info("HybridSearchService initialized: vectorWeight={}, keywordWeight={}, topK={}, rrfK={}, "
                        + "candidatePoolFactor={}, keywordEnabled={}, bm25(k1={}, b={})",
                s.getVectorWeight(), s.getKeywordWeight(), s.getTopK(), s.getRrfK(),
                s.getCandidatePoolFactor(), s.isKeywordEnabled(), s.getBm25K1(), s.getBm25B());
    }

    /**
     * 混合检索单条结果。
     *
     * @param chunkId      分块标识
     * @param content      分块文本
     * @param vectorScore  向量检索得分（未命中为 0）
     * @param keywordScore 关键词检索得分（未命中为 0）
     * @param fusedScore   RRF 融合得分
     * @param metadata     元数据
     */
    public record HybridSearchResult(String chunkId, String content, double vectorScore,
                                     double keywordScore, double fusedScore,
                                     Map<String, String> metadata) {
    }

    /** BM25 索引文档。 */
    private record IndexedChunk(String chunkId, String content, Map<String, Integer> termFreqs,
                                int length, Map<String, String> metadata) {
    }

    /**
     * 将分块注册到 BM25 关键词索引（向量入库由 {@link DocumentIngestionPipeline} 另行处理）。
     *
     * @param chunk 分块
     */
    public void indexChunk(Chunk chunk) {
        if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
            return;
        }
        Map<String, Integer> termFreqs = termFrequency(chunk.content());
        int length = termFreqs.values().stream().mapToInt(Integer::intValue).sum();
        IndexedChunk indexed = new IndexedChunk(chunk.id(), chunk.content(), termFreqs, length, chunk.metadata());
        synchronized (indexLock) {
            IndexedChunk old = index.get(chunk.id());
            if (old != null) {
                for (String term : old.termFreqs().keySet()) {
                    documentFrequency.computeIfPresent(term, (k, v) -> v <= 1 ? null : v - 1);
                }
                totalTokenLen.addAndGet(-old.length());
                docCount.decrementAndGet();
            }
            index.put(chunk.id(), indexed);
            for (String term : termFreqs.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
            totalTokenLen.addAndGet(length);
            docCount.incrementAndGet();
        }
        log.debug("Indexed chunk '{}' ({} tokens) into BM25 index", chunk.id(), length);
    }

    /**
     * 批量注册分块到 BM25 索引。
     *
     * @param chunks 分块列表
     */
    public void indexChunks(List<Chunk> chunks) {
        if (chunks == null) {
            return;
        }
        for (Chunk chunk : chunks) {
            indexChunk(chunk);
        }
    }

    /**
     * 从 BM25 索引移除指定分块。
     *
     * @param chunkId 分块标识
     * @return 是否成功移除
     */
    public boolean removeChunk(String chunkId) {
        if (chunkId == null) {
            return false;
        }
        synchronized (indexLock) {
            IndexedChunk removed = index.remove(chunkId);
            if (removed == null) {
                return false;
            }
            for (String term : removed.termFreqs().keySet()) {
                documentFrequency.computeIfPresent(term, (k, v) -> v <= 1 ? null : v - 1);
            }
            totalTokenLen.addAndGet(-removed.length());
            docCount.decrementAndGet();
        }
        log.debug("Removed chunk '{}' from BM25 index", chunkId);
        return true;
    }

    /** 清空 BM25 索引。 */
    public void clearIndex() {
        synchronized (indexLock) {
            index.clear();
            documentFrequency.clear();
            docCount.set(0);
            totalTokenLen.set(0);
        }
        log.info("BM25 keyword index cleared");
    }

    /** 当前 BM25 索引文档数。 */
    public int indexSize() {
        return docCount.get();
    }

    /**
     * 执行混合检索（向量 + 关键词 + RRF 融合）。
     *
     * @param query   查询文本
     * @param topK    返回数量（<=0 使用配置默认值）
     * @param filters 元数据过滤条件（键值对需完全匹配，可为 null 或空）
     * @return 融合排序后的结果列表
     */
    public List<HybridSearchResult> search(String query, int topK, Map<String, String> filters) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!properties.isEnabled()) {
            log.debug("Advanced RAG disabled, hybrid search returns empty");
            return List.of();
        }

        RagProperties.Search cfg = properties.getSearch();
        int limit = topK > 0 ? topK : cfg.getTopK();
        int pool = Math.max(limit, limit * Math.max(1, cfg.getCandidatePoolFactor()));

        // ── 向量检索 ──
        List<RankedItem> vectorRanked = vectorSearch(query, pool, filters, cfg);

        // ── 关键词检索（BM25）──
        List<RankedItem> keywordRanked = cfg.isKeywordEnabled()
                ? keywordSearch(query, pool, filters, cfg)
                : List.of();

        // ── RRF 融合 ──
        List<HybridSearchResult> fused = rrfFuse(vectorRanked, keywordRanked, cfg);
        List<HybridSearchResult> result = fused.size() > limit ? fused.subList(0, limit) : fused;
        log.info("Hybrid search for '{}' returned {} results (vector={}, keyword={}, indexSize={})",
                query, result.size(), vectorRanked.size(), keywordRanked.size(), indexSize());
        return result;
    }

    /**
     * 向量检索并按元数据过滤，返回带 1-based 排名的候选项。
     */
    private List<RankedItem> vectorSearch(String query, int pool, Map<String, String> filters,
                                          RagProperties.Search cfg) {
        List<RankedItem> ranked = new ArrayList<>();
        try {
            List<SearchResult> results = knowledgeBaseService.searchSimilar(query, pool, cfg.getMinScore());
            if (results == null || results.isEmpty()) {
                return ranked;
            }
            int rank = 1;
            for (SearchResult r : results) {
                if (!matchesFilters(r.metadata(), filters)) {
                    continue;
                }
                String chunkId = extractChunkId(r);
                ranked.add(new RankedItem(chunkId, r.content(), r.score(), rank,
                        r.metadata() != null ? r.metadata() : Map.of(), SourceType.VECTOR));
                rank++;
            }
        } catch (Exception e) {
            log.warn("Vector search failed for query '{}', vector path will be empty", query, e);
        }
        return ranked;
    }

    /**
     * 关键词检索（BM25）并按元数据过滤，返回带 1-based 排名的候选项。
     */
    private List<RankedItem> keywordSearch(String query, int pool, Map<String, String> filters,
                                           RagProperties.Search cfg) {
        List<RankedItem> ranked = new ArrayList<>();
        if (index.isEmpty()) {
            return ranked;
        }
        List<String> queryTerms = new ArrayList<>(termFrequency(query).keySet());
        if (queryTerms.isEmpty()) {
            return ranked;
        }
        int n = docCount.get();
        double avgdl = n > 0 ? (double) totalTokenLen.get() / n : 0.0;
        double k1 = cfg.getBm25K1();
        double b = cfg.getBm25B();

        List<KeywordHit> hits = new ArrayList<>();
        for (IndexedChunk doc : index.values()) {
            if (!matchesFilters(doc.metadata(), filters)) {
                continue;
            }
            double score = bm25Score(doc, queryTerms, n, avgdl, k1, b);
            if (score > 0.0) {
                hits.add(new KeywordHit(doc, score));
            }
        }
        hits.sort(Comparator.comparingDouble(KeywordHit::score).reversed());
        int rank = 1;
        int max = Math.min(pool, hits.size());
        for (int i = 0; i < max; i++) {
            KeywordHit hit = hits.get(i);
            IndexedChunk doc = hit.doc();
            ranked.add(new RankedItem(doc.chunkId(), doc.content(), hit.score(), rank,
                    doc.metadata() != null ? doc.metadata() : Map.of(), SourceType.KEYWORD));
            rank++;
        }
        return ranked;
    }

    /** 计算 BM25 得分。 */
    private double bm25Score(IndexedChunk doc, List<String> queryTerms,
                             int n, double avgdl, double k1, double b) {
        double score = 0.0;
        for (String term : queryTerms) {
            Integer f = doc.termFreqs().get(term);
            if (f == null || f == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log((double) (n - df + 0.5) / (df + 0.5) + 1.0);
            double denom = f + k1 * (1.0 - b + b * (avgdl > 0 ? doc.length() / avgdl : 0.0));
            score += idf * (f * (k1 + 1.0)) / denom;
        }
        return score;
    }

    /**
     * RRF 融合两路带排名的候选。
     *
     * <pre>fusedScore = vectorWeight / (rrfK + vectorRank) + keywordWeight / (rrfK + keywordRank)</pre>
     * 某路未命中的候选项该项贡献为 0；权重归一化。
     */
    private List<HybridSearchResult> rrfFuse(List<RankedItem> vectorRanked,
                                             List<RankedItem> keywordRanked,
                                             RagProperties.Search cfg) {
        double vw = cfg.getVectorWeight();
        double kw = cfg.isKeywordEnabled() ? cfg.getKeywordWeight() : 0.0;
        double sum = vw + kw;
        if (sum <= 0) {
            vw = 1.0;
            kw = 0.0;
        } else {
            vw /= sum;
            kw /= sum;
        }
        int rrfK = Math.max(1, cfg.getRrfK());

        Map<String, FusionEntry> map = new HashMap<>();
        for (RankedItem item : vectorRanked) {
            FusionEntry e = map.computeIfAbsent(item.key(), k -> new FusionEntry(item));
            e.vectorRank = item.rank();
            e.vectorScore = item.score();
            e.takeContentFrom(item);
        }
        for (RankedItem item : keywordRanked) {
            FusionEntry e = map.computeIfAbsent(item.key(), k -> new FusionEntry(item));
            e.keywordRank = item.rank();
            e.keywordScore = item.score();
            e.takeContentFrom(item);
        }

        List<HybridSearchResult> fused = new ArrayList<>(map.size());
        for (FusionEntry e : map.values()) {
            double vPart = e.vectorRank > 0 ? vw / (rrfK + e.vectorRank) : 0.0;
            double kPart = e.keywordRank > 0 ? kw / (rrfK + e.keywordRank) : 0.0;
            double fusedScore = vPart + kPart;
            fused.add(new HybridSearchResult(e.chunkId, e.content, e.vectorScore,
                    e.keywordScore, fusedScore, e.metadata));
        }
        fused.sort(Comparator.comparingDouble(HybridSearchResult::fusedScore).reversed());
        return fused;
    }

    /** 从检索结果元数据中提取分块标识，缺失时回退到内容指纹。 */
    private static String extractChunkId(SearchResult r) {
        if (r.metadata() != null) {
            String id = r.metadata().get("chunk_id");
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return "content:" + Objects.hashCode(r.content());
    }

    /** 判断元数据是否满足过滤条件（键值需完全匹配，过滤值为空表示不过滤该字段）。 */
    private static boolean matchesFilters(Map<String, String> metadata, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        if (metadata == null) {
            return false;
        }
        for (Map.Entry<String, String> f : filters.entrySet()) {
            if (f.getValue() == null || f.getValue().isBlank()) {
                continue;
            }
            String val = metadata.get(f.getKey());
            if (val == null || !val.equalsIgnoreCase(f.getValue())) {
                return false;
            }
        }
        return true;
    }

    // ──────────────────── 内部类型 ────────────────────

    /** 检索来源类型。 */
    private enum SourceType { VECTOR, KEYWORD }

    /**
     * 单路带排名的候选项。
     *
     * @param key      分块标识（chunkId），用于跨路融合匹配
     * @param content  分块文本
     * @param score    该路检索原始得分
     * @param rank     该路 1-based 排名
     * @param metadata 元数据
     * @param source   检索来源
     */
    private record RankedItem(String key, String content, double score, int rank,
                              Map<String, String> metadata, SourceType source) {
    }

    /** BM25 命中临时结构。 */
    private record KeywordHit(IndexedChunk doc, double score) {
    }

    /** RRF 融合中间结构。 */
    private static final class FusionEntry {
        String chunkId;
        String content;
        Map<String, String> metadata;
        int vectorRank = 0;
        int keywordRank = 0;
        double vectorScore = 0.0;
        double keywordScore = 0.0;

        FusionEntry(RankedItem item) {
            this.chunkId = item.key();
            this.content = item.content();
            this.metadata = item.metadata();
        }

        void takeContentFrom(RankedItem item) {
            if (this.content == null || this.content.isBlank()) {
                this.content = item.content();
            }
            if (this.metadata == null || this.metadata.isEmpty()) {
                this.metadata = item.metadata();
            }
            if (this.chunkId == null || this.chunkId.isBlank()) {
                this.chunkId = item.key();
            }
        }
    }

    // ──────────────────── 词频/分词工具（中文 CJK 二元组 + 拉丁词） ────────────────────

    /** 词频向量：CJK 字符二元组 + 拉丁/数字词（小写）。 */
    private static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> freq = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return freq;
        }
        StringBuilder latin = new StringBuilder();
        char prevCjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                if (latin.length() > 0) {
                    freq.merge(latin.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
                    latin.setLength(0);
                }
                if (prevCjk != 0) {
                    freq.merge("" + prevCjk + c, 1, Integer::sum);
                }
                prevCjk = c;
            } else {
                prevCjk = 0;
                if (Character.isLetterOrDigit(c)) {
                    latin.append(c);
                } else if (latin.length() > 0) {
                    freq.merge(latin.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
                    latin.setLength(0);
                }
            }
        }
        if (latin.length() > 0) {
            freq.merge(latin.toString().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return freq;
    }

    /** 判断字符是否为 CJK 汉字。 */
    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }
}
