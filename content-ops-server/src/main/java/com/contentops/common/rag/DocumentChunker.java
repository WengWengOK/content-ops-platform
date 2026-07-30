package com.contentops.common.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文档分块器（RAG 全链路升级）。
 *
 * <p>提供三种分块策略，由 {@link RagProperties.Chunking#getStrategy()} 决定：
 * <ul>
 *   <li>{@link RagProperties.ChunkingStrategy#RECURSIVE} —— 递归字符分块
 *       （类似 LangChain {@code RecursiveCharacterTextSplitter}）：按分隔符优先级递归切分，
 *       在尽量保持语义边界的前提下逼近目标分块大小，默认分隔符对中文友好。</li>
 *   <li>{@link RagProperties.ChunkingStrategy#SEMANTIC} —— 语义分块：先切句子，再依据相邻句子
 *       相似度变化检测主题边界进行聚块。相似度优先使用注入的 {@link EmbeddingModel}（若存在）
 *       计算，否则退化为基于词频的余弦相似度（CJK 二元组 + 拉丁词）。</li>
 *   <li>{@link RagProperties.ChunkingStrategy#FIXED_SIZE} —— 固定大小分块：按字符数硬切并带重叠。</li>
 * </ul>
 *
 * <p><b>中文友好：</b>默认分隔符覆盖段落、换行、中文句号/叹号/问号/分号/冒号/逗号及英文标点；
 * 分句与分词均对 CJK 字符（Unicode HAN 文字）做专门处理。
 *
 * <p><b>配置项：</b>{@code chunkSize}、{@code chunkOverlap}、{@code separators}、
 * {@code minChunkSize}、{@code semanticThreshold}、{@code semanticMaxSize}。
 *
 * <p><b>降级策略：</b>语义分块在嵌入模型不可用或计算异常时，自动回退到词频余弦相似度，
 * 不会抛出异常阻断流程。
 *
 * @see RagProperties.Chunking
 */
@Slf4j
@Component
public class DocumentChunker {

    private final RagProperties properties;
    /** 可选的嵌入模型，用于语义分块的真实向量化相似度计算；不存在时退化为词频相似度 */
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /**
     * 构造分块器。
     *
     * @param properties             RAG 配置
     * @param embeddingModelProvider 嵌入模型提供者（可选，语义分块使用）
     */
    public DocumentChunker(RagProperties properties,
                           ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.properties = properties;
        this.embeddingModelProvider = embeddingModelProvider;
        RagProperties.Chunking c = properties.getChunking();
        log.info("DocumentChunker initialized: strategy={}, chunkSize={}, chunkOverlap={}, minChunkSize={}, "
                        + "semanticThreshold={}, embeddingModel={}",
                c.getStrategy(), c.getChunkSize(), c.getChunkOverlap(), c.getMinChunkSize(),
                c.getSemanticThreshold(), embeddingModelProvider.getIfAvailable() != null ? "available" : "absent");
    }

    /**
     * 文档分块结果。
     *
     * @param id       分块唯一标识
     * @param content  分块文本内容
     * @param order    分块在原文档中的顺序（从 0 开始）
     * @param metadata 分块元数据（包含传入的基础元数据与自动补充的分块信息）
     */
    public record Chunk(String id, String content, int order, Map<String, String> metadata) {
    }

    /**
     * 按配置策略对文本进行分块。
     *
     * @param text          待分块文本（null 或空白返回空列表）
     * @param baseMetadata  基础元数据，将合并到每个分块（如 docId、type、niche 等），可为 null
     * @return 分块列表，保持原文顺序
     */
    public List<Chunk> chunk(String text, Map<String, String> baseMetadata) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, String> meta = baseMetadata != null ? new HashMap<>(baseMetadata) : new HashMap<>();
        RagProperties.Chunking cfg = properties.getChunking();

        List<String> rawChunks = switch (cfg.getStrategy()) {
            case RECURSIVE -> chunkRecursive(text, cfg);
            case SEMANTIC -> chunkSemantic(text, cfg);
            case FIXED_SIZE -> chunkFixedSize(text, cfg);
        };

        List<Chunk> chunks = new ArrayList<>(rawChunks.size());
        String docId = meta.get("docId");
        int order = 0;
        for (String content : rawChunks) {
            if (content == null || content.isBlank()) {
                continue;
            }
            Map<String, String> chunkMeta = new HashMap<>(meta);
            chunkMeta.put("chunkIndex", String.valueOf(order));
            chunkMeta.put("chunkTotal", String.valueOf(rawChunks.size()));
            chunkMeta.put("chunkSize", String.valueOf(content.length()));
            String id = (docId != null && !docId.isBlank())
                    ? docId + ":chunk-" + order
                    : "chunk-" + UUID.randomUUID();
            chunks.add(new Chunk(id, content.trim(), order, chunkMeta));
            order++;
        }
        log.debug("Chunked text ({} chars) into {} chunks via {} strategy",
                text.length(), chunks.size(), cfg.getStrategy());
        return chunks;
    }

    /**
     * 递归字符分块。
     *
     * <p>两阶段实现：先用分隔符优先级递归切分为不超过 {@code chunkSize} 的原子片段，
     * 再贪心合并为带 {@code chunkOverlap} 重叠的目标分块，最后合并过短分块。
     */
    private List<String> chunkRecursive(String text, RagProperties.Chunking cfg) {
        List<String> separators = cfg.getSeparators() != null && !cfg.getSeparators().isEmpty()
                ? cfg.getSeparators() : List.of("\n\n", "\n", "。", " ", "");
        List<String> pieces = recursiveSplit(text, separators, cfg.getChunkSize());
        List<String> merged = mergeWithOverlap(pieces, cfg.getChunkSize(), cfg.getChunkOverlap());
        return mergeTiny(merged, cfg.getMinChunkSize());
    }

    /**
     * 递归切分：选取首个命中的分隔符切分文本，对仍超长的片段用下一级分隔符继续切分；
     * 当无分隔符命中时退化为按字符硬切。
     */
    private List<String> recursiveSplit(String text, List<String> separators, int chunkSize) {
        List<String> result = new ArrayList<>();
        if (text.length() <= chunkSize) {
            result.add(text);
            return result;
        }

        String separator = "";
        int sepIndex = -1;
        for (int i = 0; i < separators.size(); i++) {
            String s = separators.get(i);
            if (!s.isEmpty() && text.contains(s)) {
                separator = s;
                sepIndex = i;
                break;
            }
        }

        List<String> subPieces;
        if (separator.isEmpty()) {
            // 无可用分隔符，按字符硬切
            subPieces = splitByCharSize(text, chunkSize, 0);
        } else {
            // 用 lookbehind 保留分隔符在片段尾部，保证拼接后可精确还原原文
            String[] parts = text.split("(?<=" + Pattern.quote(separator) + ")", -1);
            subPieces = new ArrayList<>(Arrays.asList(parts));
        }

        List<String> nextSeparators = (sepIndex >= 0 && sepIndex + 1 < separators.size())
                ? separators.subList(sepIndex + 1, separators.size())
                : List.of();

        for (String piece : subPieces) {
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.length() > chunkSize && !separator.isEmpty()) {
                result.addAll(recursiveSplit(piece, nextSeparators, chunkSize));
            } else if (piece.length() > chunkSize) {
                result.addAll(splitByCharSize(piece, chunkSize, 0));
            } else {
                result.add(piece);
            }
        }
        return result;
    }

    /**
     * 固定大小分块：按 {@code chunkSize} 切分并携带 {@code chunkOverlap} 重叠。
     */
    private List<String> chunkFixedSize(String text, RagProperties.Chunking cfg) {
        return splitByCharSize(text, cfg.getChunkSize(), cfg.getChunkOverlap());
    }

    /** 按字符大小切分，step = max(1, size - overlap)。 */
    private List<String> splitByCharSize(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        int len = text.length();
        if (len == 0) {
            return result;
        }
        int step = Math.max(1, size - overlap);
        for (int i = 0; i < len; i += step) {
            int end = Math.min(i + size, len);
            result.add(text.substring(i, end));
            if (end == len) {
                break;
            }
        }
        return result;
    }

    /**
     * 语义分块：先分句，再依据相邻文本块相似度聚块。
     *
     * <p>相似度优先用嵌入模型，不可用或异常时退化为词频余弦相似度。聚块后若仍超过
     * {@code chunkSize}，再用递归分块二次切分，最后带重叠合并。
     */
    private List<String> chunkSemantic(String text, RagProperties.Chunking cfg) {
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= 1) {
            return mergeWithOverlap(
                    recursiveSplit(text, cfg.getSeparators(), cfg.getChunkSize()),
                    cfg.getChunkSize(), cfg.getChunkOverlap());
        }

        List<String> semanticChunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(sentences.get(0));
        double threshold = cfg.getSemanticThreshold();
        int maxSize = Math.min(cfg.getSemanticMaxSize(), cfg.getChunkSize() * 2);

        for (int i = 1; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            double sim = similarity(current.toString(), sentence);
            boolean tooLong = current.length() + sentence.length() > maxSize;
            if (sim < threshold || tooLong) {
                semanticChunks.add(current.toString());
                current.setLength(0);
                current.append(sentence);
            } else {
                current.append(sentence);
            }
        }
        if (current.length() > 0) {
            semanticChunks.add(current.toString());
        }

        // 对超长语义块二次切分
        List<String> finalized = new ArrayList<>(semanticChunks.size());
        for (String chunk : semanticChunks) {
            if (chunk.length() > cfg.getChunkSize()) {
                finalized.addAll(recursiveSplit(chunk, cfg.getSeparators(), cfg.getChunkSize()));
            } else {
                finalized.add(chunk);
            }
        }
        return mergeWithOverlap(finalized, cfg.getChunkSize(), cfg.getChunkOverlap());
    }

    /**
     * 计算两段文本的相似度（0.0-1.0）。
     *
     * <p>优先使用注入的 {@link EmbeddingModel} 计算向量余弦相似度；
     * 模型不存在或计算异常时退化为基于词频的余弦相似度。
     */
    private double similarity(String a, String b) {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model != null) {
            try {
                Response<Embedding> ra = model.embed(a);
                Response<Embedding> rb = model.embed(b);
                if (ra != null && rb != null && ra.content() != null && rb.content() != null) {
                    return cosine(ra.content().vector(), rb.content().vector());
                }
            } catch (Exception e) {
                log.debug("Embedding similarity failed, falling back to lexical similarity", e);
            }
        }
        return lexicalCosine(a, b);
    }

    /** 浮点向量余弦相似度。 */
    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 基于词频的余弦相似度（CJK 二元组 + 拉丁词）。 */
    private static double lexicalCosine(String a, String b) {
        Map<String, Integer> va = termFrequency(a);
        Map<String, Integer> vb = termFrequency(b);
        if (va.isEmpty() || vb.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> smaller = va.size() <= vb.size() ? va : vb;
        Map<String, Integer> larger = va.size() <= vb.size() ? vb : va;
        double dot = 0.0;
        for (Map.Entry<String, Integer> e : smaller.entrySet()) {
            Integer other = larger.get(e.getKey());
            if (other != null) {
                dot += e.getValue() * other;
            }
        }
        double na = 0.0, nb = 0.0;
        for (int v : va.values()) na += (double) v * v;
        for (int v : vb.values()) nb += (double) v * v;
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 词频向量：CJK 字符二元组 + 拉丁/数字词（小写）。 */
    private static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> freq = new HashMap<>();
        StringBuilder latin = new StringBuilder();
        char prevCjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                if (latin.length() > 0) {
                    freq.merge(latin.toString().toLowerCase(), 1, Integer::sum);
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
                } else {
                    if (latin.length() > 0) {
                        freq.merge(latin.toString().toLowerCase(), 1, Integer::sum);
                        latin.setLength(0);
                    }
                }
            }
        }
        if (latin.length() > 0) {
            freq.merge(latin.toString().toLowerCase(), 1, Integer::sum);
        }
        return freq;
    }

    /** 判断字符是否为 CJK 汉字（Unicode HAN 文字）。 */
    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /**
     * 中文友好分句：在中英文句末标点（。！？；.!?）与换行符之后切分，并保留标点。
     */
    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        String[] parts = text.split("(?<=[。！？；.!?\\n])", -1);
        for (String part : parts) {
            if (!part.isBlank()) {
                sentences.add(part);
            }
        }
        return sentences;
    }

    /**
     * 贪心合并片段为带字符级重叠的分块。
     *
     * <p>依次累加片段，达到 {@code chunkSize} 即封口；新分块以旧分块尾部
     * {@code overlap} 个字符作为重叠起始，保持上下文连贯。
     */
    private static List<String> mergeWithOverlap(List<String> pieces, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        if (pieces.isEmpty()) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            if (current.length() == 0) {
                current.append(piece);
            } else if (current.length() + piece.length() <= chunkSize) {
                current.append(piece);
            } else {
                result.add(current.toString());
                String tail = current.length() > overlap
                        ? current.substring(current.length() - overlap)
                        : current.toString();
                current.setLength(0);
                current.append(tail).append(piece);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 合并过短分块：将长度小于 {@code minChunkSize} 的分块并入相邻分块，避免产生碎片。
     */
    private static List<String> mergeTiny(List<String> chunks, int minChunkSize) {
        if (minChunkSize <= 0 || chunks.size() <= 1) {
            return chunks;
        }
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            if (!result.isEmpty() && chunk.length() < minChunkSize) {
                int lastIdx = result.size() - 1;
                result.set(lastIdx, result.get(lastIdx) + chunk);
            } else {
                result.add(chunk);
            }
        }
        return result;
    }
}
