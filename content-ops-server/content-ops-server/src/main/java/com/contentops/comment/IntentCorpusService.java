package com.contentops.comment;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 意图语料库（RAG 前置匹配）。
 *
 * <p>匹配顺序：
 * <ol>
 *   <li><b>精确匹配</b>：归一化后完全相同的说法直接命中（得分 1.0，确定性最强）；</li>
 *   <li><b>向量相似度</b>：本地 BGE 中文嵌入（无需外部 Key）算余弦相似度，
 *       ≥ {@code threshold}（默认 0.95）直接采用标准术语；</li>
 *   <li>未命中则由调用方交给模型判断（工作流匹配）。</li>
 * </ol>
 *
 * <p>语料库缓存为内存向量表（语料量级在千条以内），支持 {@link #rebuild} 重建与
 * 进程内版本号，便于接口侧观察。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentCorpusService {

    private final IntentCorpusRepository repository;
    private final CommentProperties properties;

    private final AtomicLong version = new AtomicLong();

    private volatile EmbeddingModel embeddingModel;
    private volatile List<Cached> cache = List.of();
    private volatile String cachedOwnerId = "";
    /** 是否已加载过缓存（与 cachedOwnerId 分开，避免 ownerId=null 与未加载状态混淆） */
    private volatile boolean cacheLoaded = false;

    /** 匹配结果。 */
    public record MatchResult(boolean matched, String intent, String phrase, double score,
                              String matchedBy, String corpusId) {

        public static MatchResult miss(double bestScore) {
            return new MatchResult(false, null, null, bestScore, "NONE", null);
        }
    }

    /** 候选（用于阈值调优预览）。 */
    public record Candidate(String intent, String phrase, double score, String matchedBy) {
    }

    private record Cached(IntentCorpusEntry entry, float[] vector) {
    }

    // ──────────────────────── 匹配 ────────────────────────

    /** 按配置阈值匹配；命中返回标准术语。 */
    public MatchResult match(String text, String ownerId) {
        return match(text, ownerId, properties.getIntentRag().getThreshold());
    }

    public MatchResult match(String text, String ownerId, double threshold) {
        if (!properties.getIntentRag().isEnabled() || text == null || text.isBlank()) {
            return MatchResult.miss(0);
        }
        ensureCache(ownerId);
        String normalized = normalize(text);

        // 1) 精确匹配（归一化）：确定性优先，且不受阈值影响
        for (Cached cached : cache) {
            if (normalize(cached.entry().getPhrase()).equals(normalized)) {
                repository.incrementHit(cached.entry().getCorpusId());
                return new MatchResult(true, cached.entry().getIntent(), cached.entry().getPhrase(),
                        1.0, "EXACT", cached.entry().getCorpusId());
            }
        }

        // 2) 向量相似度
        float[] query = embed(text);
        if (query == null) {
            return MatchResult.miss(0);
        }
        Cached best = null;
        double bestScore = -1;
        for (Cached cached : cache) {
            if (cached.vector() == null) {
                continue;
            }
            double score = cosine(query, cached.vector());
            if (score > bestScore) {
                bestScore = score;
                best = cached;
            }
        }
        if (best != null && bestScore >= threshold) {
            repository.incrementHit(best.entry().getCorpusId());
            return new MatchResult(true, best.entry().getIntent(), best.entry().getPhrase(),
                    round(bestScore), "VECTOR", best.entry().getCorpusId());
        }
        return MatchResult.miss(bestScore < 0 ? 0 : round(bestScore));
    }

    /** 预览最相近的若干语料（用于阈值调优）。 */
    public List<Candidate> preview(String text, String ownerId, int topK) {
        ensureCache(ownerId);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = normalize(text);
        List<Candidate> candidates = new ArrayList<>();
        float[] query = null;
        for (Cached cached : cache) {
            double score = normalize(cached.entry().getPhrase()).equals(normalized) ? 1.0 : 0;
            if (score == 0 && cached.vector() != null) {
                if (query == null) {
                    query = embed(text);
                }
                if (query == null) {
                    break;
                }
                score = round(cosine(query, cached.vector()));
            }
            candidates.add(new Candidate(cached.entry().getIntent(), cached.entry().getPhrase(), score,
                    score >= 1.0 ? "EXACT" : "VECTOR"));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return candidates.subList(0, Math.min(Math.max(1, topK), candidates.size()));
    }

    // ──────────────────────── 缓存与维护 ────────────────────────

    /** 重建缓存（新增/删除语料后调用；owner 为空时构建全局语料视图）。 */
    public synchronized int rebuild(String ownerId) {
        List<IntentCorpusEntry> entries = repository.listEnabled(ownerId);
        List<Cached> rebuilt = new ArrayList<>();
        for (IntentCorpusEntry entry : entries) {
            // 向量模型不可用时也保留条目：精确匹配不依赖向量，向量比对时跳过空向量
            rebuilt.add(new Cached(entry, embed(entry.getPhrase())));
        }
        this.cache = List.copyOf(rebuilt);
        this.cachedOwnerId = ownerId == null ? "" : ownerId;
        this.cacheLoaded = true;
        long next = version.incrementAndGet();
        log.info("[IntentCorpus] 语料库已加载: 条目={}, owner={}, version={}", rebuilt.size(), ownerId, next);
        return rebuilt.size();
    }

    /** 新增语料（重复短语自动跳过）。 */
    public IntentCorpusEntry add(String ownerId, String intent, String phrase) {
        if (intent == null || intent.isBlank() || phrase == null || phrase.isBlank()) {
            throw new IllegalArgumentException("intent 与 phrase 均不能为空");
        }
        String trimmedIntent = intent.trim();
        String trimmedPhrase = phrase.trim();
        if (repository.exists(trimmedIntent, trimmedPhrase, ownerId)) {
            return null;
        }
        IntentCorpusEntry entry = IntentCorpusEntry.builder()
                .corpusId(UUID.randomUUID().toString())
                .ownerId(ownerId)
                .intent(trimmedIntent)
                .phrase(trimmedPhrase)
                .source("USER")
                .enabled(true)
                .hitCount(0)
                .build();
        repository.insert(entry);
        invalidate();
        return entry;
    }

    /** 语料列表（含全局与本人维护的条目）。 */
    public List<IntentCorpusEntry> list(String ownerId) {
        return repository.listAll(ownerId);
    }

    public void delete(String corpusId) {
        repository.delete(corpusId);
        invalidate();
    }

    public void setEnabled(String corpusId, boolean enabled) {
        repository.setEnabled(corpusId, enabled);
        invalidate();
    }

    /** 语料库概览：规模、按意图分布、缓存状态。 */
    public Map<String, Object> stats(String ownerId) {
        ensureCache(ownerId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.getIntentRag().isEnabled());
        data.put("threshold", properties.getIntentRag().getThreshold());
        data.put("cacheSize", cache.size());
        data.put("cacheVersion", version.get());
        data.put("embeddingReady", embeddingModel != null);
        data.put("totalInDb", repository.count());
        Map<String, Integer> byIntent = new LinkedHashMap<>();
        for (Cached cached : cache) {
            byIntent.merge(cached.entry().getIntent(), 1, Integer::sum);
        }
        data.put("byIntent", byIntent);
        return data;
    }

    private void invalidate() {
        this.cacheLoaded = false;
    }

    private void ensureCache(String ownerId) {
        String key = ownerId == null ? "" : ownerId;
        if (!cacheLoaded || !key.equals(cachedOwnerId)) {
            rebuild(ownerId);
        }
    }

    // ──────────────────────── 向量工具 ────────────────────────

    private synchronized EmbeddingModel model() {
        if (embeddingModel == null) {
            try {
                embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
                log.info("[IntentCorpus] 本地嵌入模型就绪: BgeSmallZhV15, dim={}", embeddingModel.dimension());
            } catch (Exception e) {
                log.warn("[IntentCorpus] 嵌入模型初始化失败（将退化为精确匹配）: {}", e.getMessage());
            }
        }
        return embeddingModel;
    }

    private float[] embed(String text) {
        EmbeddingModel model = model();
        if (model == null) {
            return null;
        }
        try {
            Embedding embedding = model.embed(text).content();
            return embedding == null ? null : embedding.vector();
        } catch (Exception e) {
            log.debug("[IntentCorpus] 嵌入失败: {}", e.getMessage());
            return null;
        }
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 归一化：去空白与常见标点，便于精确匹配。 */
    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）【】~…]+", "").toLowerCase();
    }

    private double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}