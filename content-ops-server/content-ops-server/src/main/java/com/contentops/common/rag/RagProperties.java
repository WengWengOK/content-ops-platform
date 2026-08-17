package com.contentops.common.rag;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 高级 RAG 全链路配置属性类（RAG 全链路升级）。
 *
 * <p>统一承载分块（chunking）、混合检索（hybrid search）、重排序（rerank）、
 * 文档摄入（ingestion）、查询重写（query rewriting）等全部配置项，驱动
 * {@link DocumentChunker}、{@link HybridSearchService}、{@link RerankService}、
 * {@link DocumentIngestionPipeline}、{@link AdvancedRagService} 等组件。
 *
 * <p>绑定到 application.yml 中的 {@code contentops.advanced-rag}：
 * <pre>{@code
 * contentops:
 *   advanced-rag:
 *     enabled: true
 *     chunking:
 *       strategy: RECURSIVE          # RECURSIVE | SEMANTIC | FIXED_SIZE
 *       chunk-size: 800
 *       chunk-overlap: 100
 *       separators:
 *         - "\n\n"
 *         - "\n"
 *         - "。"
 *         - "！"
 *         - "？"
 *         - "；"
 *         - "."
 *         - " "
 *     search:
 *       vector-weight: 0.7
 *       keyword-weight: 0.3
 *       top-k: 10
 *       rrf-k: 60
 *       min-score: 0.2
 *     rerank:
 *       enabled: true
 *       strategy: RULE               # RULE | CROSS_ENCODER
 *       top-k: 5
 *       rerank-model: ""             # cross-encoder 模型名，留空则回退规则/LLM
 *       fallback-on-error: true
 *     ingestion:
 *       max-file-size-bytes: 10485760
 *       clean-whitespace: true
 *       clean-html: true
 *       extract-metadata: true
 *     query-rewrite:
 *       enabled: false
 *       strategy: NONE               # NONE | HYDE | MULTI_QUERY
 *       multi-query-count: 3
 * }</pre>
 *
 * <p><b>与 {@code common.knowledge.RagProperties} 的关系：</b>后者绑定 {@code contentops.rag}，
 * 仅控制「检索增强层」阈值与开关；本类为 RAG 全链路升级后的完整配置，绑定
 * {@code contentops.advanced-rag}，二者前缀与 bean 名均不同（本类 bean 名为
 * {@code advancedRagProperties}），互不冲突，可共存并逐步迁移。
 *
 * <p>所有配置项均提供合理默认值，未在 YAML 中显式配置时也能正常工作。
 */
@Slf4j
@Data
@Component("advancedRagProperties")
@ConfigurationProperties(prefix = "contentops.advanced-rag")
public class RagProperties {

    /** 全局开关：关闭时高级 RAG 链路退化为空结果，不阻断主流程 */
    private boolean enabled = true;

    /** 分块配置 */
    private Chunking chunking = new Chunking();

    /** 混合检索配置 */
    private Search search = new Search();

    /** 重排序配置 */
    private Rerank rerank = new Rerank();

    /** 文档摄入流水线配置 */
    private Ingestion ingestion = new Ingestion();

    /** 查询重写配置 */
    private QueryRewrite queryRewrite = new QueryRewrite();

    /** 分块策略枚举。 */
    public enum ChunkingStrategy {
        /** 递归字符分块（按分隔符层级递归切分，保持语义边界） */
        RECURSIVE,
        /** 语义分块（基于相邻句子相似度变化检测主题边界） */
        SEMANTIC,
        /** 固定大小分块（按字符数硬切，带重叠） */
        FIXED_SIZE
    }

    /** 重排序策略枚举。 */
    public enum RerankStrategy {
        /** 基于规则的重排序（关键词匹配 + 位置加权），默认实现，零依赖 */
        RULE,
        /** Cross-encoder 重排序（使用模型或 LLM-as-cross-encoder 对 query-doc 对打分） */
        CROSS_ENCODER
    }

    /** 查询重写策略枚举。 */
    public enum QueryRewriteStrategy {
        /** 不重写，直接使用原始查询 */
        NONE,
        /** HyDE：先让 LLM 生成假设性答案，再用该答案做检索 */
        HYDE,
        /** Multi-query：生成多个查询变体，分别检索后融合 */
        MULTI_QUERY
    }

    /**
     * 分块配置。
     *
     * <p>控制 {@link DocumentChunker} 的切分行为，支持递归字符、语义、固定大小三种策略，
     * 默认分隔符序列对中文内容友好（段落、句号、感叹号、问号、分号、逗号等）。
     */
    @Data
    public static class Chunking {
        /** 分块策略，默认递归字符分块 */
        private ChunkingStrategy strategy = ChunkingStrategy.RECURSIVE;

        /** 单个分块目标大小（字符数） */
        private int chunkSize = 800;

        /** 相邻分块重叠字符数，用于保持上下文连贯 */
        private int chunkOverlap = 100;

        /** 分块最小长度（短于此值的分块将与相邻分块合并） */
        private int minChunkSize = 50;

        /**
         * 递归分块使用的分隔符序列（按优先级从高到低）。
         * <p>默认对中文友好：优先按段落、换行、中文句号/叹号/问号/分号切分。
         */
        private List<String> separators = new ArrayList<>(List.of(
                "\n\n\n", "\n\n", "\n",
                "。", "！", "？", "；", "：",
                ". ", "! ", "? ", "; ",
                "，", ",", " ", ""
        ));

        /** 语义分块：相邻句子相似度低于该阈值时切分（0.0-1.0） */
        private double semanticThreshold = 0.5;

        /** 语义分块：单个语义块最大字符数（防止过长） */
        private int semanticMaxSize = 1200;
    }

    /**
     * 混合检索配置。
     *
     * <p>控制 {@link HybridSearchService} 的两路检索（向量 + 关键词）权重与
     * Reciprocal Rank Fusion (RRF) 融合参数。
     */
    @Data
    public static class Search {
        /** 向量检索权重（与 keywordWeight 一起归一化使用） */
        private double vectorWeight = 0.7;

        /** 关键词检索（BM25）权重 */
        private double keywordWeight = 0.3;

        /** 最终返回结果数 */
        private int topK = 10;

        /** RRF 融合常数（标准取值 60），越大对排名差异越平滑 */
        private int rrfK = 60;

        /** 候选池放大系数：每路检索获取 topK × 该系数 个候选后再融合，提升召回 */
        private int candidatePoolFactor = 3;

        /** 向量检索最低相似度阈值（0.0-1.0），低于该值的候选被过滤 */
        private double minScore = 0.2;

        /** 是否启用关键词检索（关闭则退化为纯向量检索） */
        private boolean keywordEnabled = true;

        /** BM25 参数 k1（词频饱和度） */
        private double bm25K1 = 1.2;

        /** BM25 参数 b（文档长度归一化强度） */
        private double bm25B = 0.75;
    }

    /**
     * 重排序配置。
     *
     * <p>控制 {@link RerankService} 的重排序行为，默认使用基于规则的重排序，
     * 可切换为 cross-encoder 策略（需模型或 LLM 支持，失败时按 {@link #fallbackOnError} 降级）。
     */
    @Data
    public static class Rerank {
        /** 是否启用重排序 */
        private boolean enabled = true;

        /** 重排序策略 */
        private RerankStrategy strategy = RerankStrategy.RULE;

        /** 重排序后返回的结果数 */
        private int topK = 5;

        /** Cross-encoder 模型名；留空时若策略为 CROSS_ENCODER 则尝试 LLM-as-cross-encoder */
        private String rerankModel = "";

        /** 关键词匹配加权系数（规则重排序用） */
        private double keywordBoost = 1.0;

        /** 位置加权系数：候选在原始检索中排名越靠前加分越高（规则重排序用） */
        private double positionWeight = 0.5;

        /** 长度归一化系数：对过短/过长文档做轻微惩罚（规则重排序用） */
        private double lengthNormWeight = 0.2;

        /** 重排序失败时是否回退到原始检索结果顺序 */
        private boolean fallbackOnError = true;
    }

    /**
     * 文档摄入流水线配置。
     *
     * <p>控制 {@link DocumentIngestionPipeline} 的解析、清洗、元数据提取行为。
     */
    @Data
    public static class Ingestion {
        /** 单个文档最大字节数，超过则拒绝摄入 */
        private long maxFileSizeBytes = 10L * 1024 * 1024;

        /** 是否清洗多余空白（合并连续空白、去除首尾空白） */
        private boolean cleanWhitespace = true;

        /** 是否清洗 HTML 标签（剥离标签只保留文本） */
        private boolean cleanHtml = true;

        /** 是否提取文档元数据（标题、作者、创建时间等） */
        private boolean extractMetadata = true;

        /** 支持的文档格式（小写扩展名） */
        private List<String> supportedFormats = new ArrayList<>(List.of("pdf", "docx", "md", "txt", "markdown"));

        /** 摄入失败时是否抛出异常（false 则记录日志并返回失败结果，不阻断） */
        private boolean failFast = false;
    }

    /**
     * 查询重写配置。
     *
     * <p>控制 {@link AdvancedRagService} 的查询重写策略，默认关闭（需 LLM 支持）。
     * 启用后可在检索前对查询进行 HyDE 或 Multi-query 改写，提升召回与相关性。
     */
    @Data
    public static class QueryRewrite {
        /** 是否启用查询重写 */
        private boolean enabled = false;

        /** 查询重写策略 */
        private QueryRewriteStrategy strategy = QueryRewriteStrategy.NONE;

        /** Multi-query 策略生成的查询变体数量 */
        private int multiQueryCount = 3;

        /** HyDE 提示词模板，{query} 占位符将被实际查询替换 */
        private String hydePrompt = """
                请针对以下问题，撰写一段可能包含答案的、信息密集的中文段落（200-400字），
                作为假设性文档用于检索增强。只输出段落正文，不要附加说明。
                问题：{query}
                """;

        /** Multi-query 提示词模板，{query} 与 {count} 占位符将被替换 */
        private String multiQueryPrompt = """
                请将以下检索查询改写为 {count} 个语义等价但表述不同的中文查询变体，
                每行一个，不要编号，不要附加说明。
                原始查询：{query}
                """;
    }
}
