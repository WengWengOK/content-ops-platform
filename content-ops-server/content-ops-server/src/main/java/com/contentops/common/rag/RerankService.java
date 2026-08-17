package com.contentops.common.rag;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 重排序服务（RAG 全链路升级）。
 *
 * <p>对混合检索召回的候选结果进行二次排序，提升最终送入 LLM 上下文的相关性。
 * 支持两种重排序策略，由 {@link RagProperties.Rerank#getStrategy()} 决定：
 * <ul>
 *   <li>{@link RagProperties.RerankStrategy#RULE} —— 基于规则的重排序（默认实现）：
 *       综合关键词匹配度、检索位置加权、长度归一化对候选打分。零依赖、低延迟。</li>
 *   <li>{@link RagProperties.RerankStrategy#CROSS_ENCODER} —— Cross-encoder 重排序：
 *       使用 {@code (query, document)} 对联合打分。当配置了 {@code rerankModel} 或
 *       注入了 {@link ChatModel} 时，采用 LLM-as-cross-encoder 方式逐对打分；
 *       模型不可用或打分异常时回退到规则重排序。</li>
 * </ul>
 *
 * <p><b>配置项：</b>{@code rerankTopK}（{@code rerank.top-k}）、{@code rerankModel}
 * （{@code rerank.rerank-model}）、{@code keywordBoost}、{@code positionWeight}、
 * {@code lengthNormWeight}、{@code fallbackOnError}。
 *
 * <p><b>降级策略：</b>重排序整体关闭、策略不可用或执行异常时，若 {@code fallbackOnError=true}，
 * 则保持原始检索顺序截取 topK 返回；否则返回空结果。绝不抛出异常阻断主流程。
 *
 * @see RagProperties.Rerank
 */
@Slf4j
@Component
public class RerankService {

    private final RagProperties properties;
    /** 可选的聊天模型，CROSS_ENCODER 策略下用作 LLM-as-cross-encoder 打分器 */
    private final ObjectProvider<ChatModel> chatModelProvider;

    /** 用于从 LLM 输出中提取相关性分数（0-1 浮点） */
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /**
     * 构造重排序服务。
     *
     * @param properties         RAG 配置
     * @param chatModelProvider  聊天模型提供者（可选，cross-encoder 策略使用）
     */
    public RerankService(RagProperties properties,
                         ObjectProvider<ChatModel> chatModelProvider) {
        this.properties = properties;
        this.chatModelProvider = chatModelProvider;
        RagProperties.Rerank r = properties.getRerank();
        log.info("RerankService initialized: enabled={}, strategy={}, topK={}, rerankModel='{}', fallbackOnError={}, chatModel={}",
                r.isEnabled(), r.getStrategy(), r.getTopK(), r.getRerankModel(),
                r.isFallbackOnError(), chatModelProvider.getIfAvailable() != null ? "available" : "absent");
    }

    /**
     * 重排序候选项（输入类型，与具体检索实现解耦）。
     *
     * @param chunkId       分块标识
     * @param content       分块文本
     * @param score         原始检索得分（融合后）
     * @param rank          原始检索排名（从 0 开始）
     * @param metadata      元数据
     */
    public record RerankCandidate(String chunkId, String content, double score, int rank,
                                  Map<String, String> metadata) {
    }

    /**
     * 重排序结果。
     *
     * @param chunkId        分块标识
     * @param content        分块文本
     * @param originalScore  原始检索得分
     * @param rerankScore    重排序得分
     * @param originalRank   原始检索排名
     * @param metadata       元数据
     */
    public record RerankResult(String chunkId, String content, double originalScore,
                               double rerankScore, int originalRank, Map<String, String> metadata) {
    }

    /**
     * 重排序策略接口（密封）。具体实现：{@link RuleBasedReranker}、{@link CrossEncoderReranker}。
     */
    private sealed interface Reranker permits RuleBasedReranker, CrossEncoderReranker {
        /** 对候选打分并按重排序得分降序返回。 */
        List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topK);
    }

    /**
     * 基于规则的重排序：综合关键词匹配度、位置加权、长度归一化。
     */
    private final class RuleBasedReranker implements Reranker {
        @Override
        public List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topK) {
            RagProperties.Rerank cfg = properties.getRerank();
            Set<String> queryTerms = Set.copyOf(tokenize(query));
            int maxRank = Math.max(1, candidates.size() - 1);
            int idealLen = candidates.stream().mapToInt(c -> c.content().length()).sum()
                    / Math.max(1, candidates.size());

            List<RerankResult> scored = new ArrayList<>(candidates.size());
            for (RerankCandidate c : candidates) {
                double keywordScore = keywordMatchScore(queryTerms, c.content());
                double positionScore = 1.0 - ((double) c.rank() / maxRank);
                double lengthScore = lengthScore(c.content().length(), idealLen);
                double rerankScore = c.score()
                        + cfg.getKeywordBoost() * keywordScore
                        + cfg.getPositionWeight() * positionScore
                        + cfg.getLengthNormWeight() * lengthScore;
                scored.add(new RerankResult(c.chunkId(), c.content(), c.score(),
                        rerankScore, c.rank(), c.metadata()));
            }
            scored.sort(Comparator.comparingDouble(RerankResult::rerankScore).reversed());
            return scored.size() > topK ? scored.subList(0, topK) : scored;
        }

        /** 关键词匹配度：查询词在文档中命中比例（带词频加权），结果归一化到 0-1。 */
        private double keywordMatchScore(Set<String> queryTerms, String content) {
            if (queryTerms.isEmpty()) {
                return 0.0;
            }
            Map<String, Integer> docFreq = termFrequency(content);
            int hit = 0;
            int total = 0;
            for (String term : queryTerms) {
                total++;
                Integer f = docFreq.get(term);
                if (f != null && f > 0) {
                    hit++;
                }
            }
            return total == 0 ? 0.0 : (double) hit / total;
        }

        /** 长度评分：偏离理想长度越多分越低，结果在 0-1。 */
        private double lengthScore(int len, int ideal) {
            if (ideal <= 0) {
                return 0.5;
            }
            double ratio = (double) len / ideal;
            double penalty = Math.abs(1.0 - ratio);
            return Math.max(0.0, 1.0 - penalty);
        }
    }

    /**
     * Cross-encoder 重排序：使用 LLM 对 (query, document) 对打分，失败回退规则重排序。
     */
    private final class CrossEncoderReranker implements Reranker {
        @Override
        public List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topK) {
            ChatModel model = chatModelProvider.getIfAvailable();
            if (model == null) {
                log.warn("Cross-encoder rerank requested but ChatModel unavailable; "
                        + "falling back to rule-based rerank");
                return new RuleBasedReranker().rerank(query, candidates, topK);
            }
            try {
                List<RerankResult> scored = new ArrayList<>(candidates.size());
                for (RerankCandidate c : candidates) {
                    double s = scoreWithLlm(model, query, c.content(), c.score());
                    scored.add(new RerankResult(c.chunkId(), c.content(), c.score(),
                            s, c.rank(), c.metadata()));
                }
                scored.sort(Comparator.comparingDouble(RerankResult::rerankScore).reversed());
                List<RerankResult> result = scored.size() > topK ? scored.subList(0, topK) : scored;
                log.debug("Cross-encoder rerank completed for {} candidates (model-based)", candidates.size());
                return result;
            } catch (Exception e) {
                log.warn("Cross-encoder rerank failed, falling back to rule-based rerank", e);
                return new RuleBasedReranker().rerank(query, candidates, topK);
            }
        }

        /**
         * 用 LLM 对单条 (query, passage) 对打分。
         * <p>提示模型输出 0-1 之间的相关性分数；解析失败时回退到原始检索得分。
         */
        private double scoreWithLlm(ChatModel model, String query, String passage, double fallback) {
            String prompt = """
                    请评估下面「查询」与「段落」的相关性，只输出一个 0 到 1 之间的浮点数（1 表示完全相关，0 表示无关），不要输出任何其它内容。
                    查询：%s
                    段落：%s
                    """.formatted(query, truncate(passage, 800));
            try {
                String output = model.chat(prompt);
                double parsed = parseScore(output, fallback);
                return parsed;
            } catch (Exception e) {
                log.debug("LLM scoring failed for a candidate, using fallback score", e);
                return fallback;
            }
        }

        /** 从模型输出中解析首个浮点数，并限定到 [0,1]；解析失败返回 fallback。 */
        private double parseScore(String output, double fallback) {
            if (output == null || output.isBlank()) {
                return fallback;
            }
            Matcher m = SCORE_PATTERN.matcher(output.trim());
            if (!m.find()) {
                return fallback;
            }
            try {
                double v = Double.parseDouble(m.group(1));
                if (v > 1.0 && v <= 100.0) {
                    v = v / 100.0;
                }
                return Math.max(0.0, Math.min(1.0, v));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /**
     * 对候选结果执行重排序。
     *
     * <p>流程：
     * <ol>
     *   <li>重排序关闭或候选为空时直接返回（关闭时按原始顺序截取 topK）</li>
     *   <li>依据配置策略选择 {@link Reranker} 实现打分排序</li>
     *   <li>整体异常时按 {@code fallbackOnError} 决定回退或返回空</li>
     * </ol>
     *
     * @param query      查询文本
     * @param candidates 候选列表（已按原始检索得分排序，rank 字段反映原始排名）
     * @param topK       返回数量（<=0 时使用配置默认值）
     * @return 重排序后的结果列表
     */
    public List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topK) {
        int limit = topK > 0 ? topK : properties.getRerank().getTopK();
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (!properties.getRerank().isEnabled()) {
            log.debug("Rerank disabled, returning candidates in original order (topK={})", limit);
            return candidates.stream()
                    .limit(limit)
                    .map(c -> new RerankResult(c.chunkId(), c.content(), c.score(),
                            c.score(), c.rank(), c.metadata()))
                    .toList();
        }
        try {
            Reranker reranker = switch (properties.getRerank().getStrategy()) {
                case RULE -> new RuleBasedReranker();
                case CROSS_ENCODER -> new CrossEncoderReranker();
            };
            return reranker.rerank(query, candidates, limit);
        } catch (Exception e) {
            log.error("Rerank failed unexpectedly", e);
            if (properties.getRerank().isFallbackOnError()) {
                log.warn("Falling back to original retrieval order due to rerank failure");
                return candidates.stream()
                        .limit(limit)
                        .map(c -> new RerankResult(c.chunkId(), c.content(), c.score(),
                                c.score(), c.rank(), c.metadata()))
                        .toList();
            }
            return List.of();
        }
    }

    // ──────────────────── 词频/分词工具（与 DocumentChunker 保持一致的中文处理） ────────────────────

    /** 分词：CJK 字符二元组 + 拉丁/数字词（小写）。 */
    private static List<String> tokenize(String text) {
        return new ArrayList<>(termFrequency(text).keySet());
    }

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

    /** 截断文本并折叠空白。 */
    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max) + "...";
    }
}
