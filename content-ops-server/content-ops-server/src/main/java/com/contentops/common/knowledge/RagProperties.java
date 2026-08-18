package com.contentops.common.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索增强配置（v1.2.0 RAG 知识库 P0 遗留项）。
 *
 * <p>绑定到 application.yml 中的 {@code contentops.rag}：
 * <pre>
 * contentops:
 *   rag:
 *     enabled: true
 *     max-results: 5
 *     min-score: 0.6
 *     context-injection:
 *       topic-planning: true
 *       optimization: true
 * </pre>
 *
 * <p>该配置驱动 {@link RagRetrievalEnhancer}：在 TopicAgent 与 OptimizeAgent 调用前，
 * 通过 RAG 检索历史相似内容并注入上下文，让 Agent 决策有「历史记忆」而非从零开始。
 *
 * <p><b>与 KnowledgeBaseService 的关系：</b>本配置仅控制「检索增强层」的阈值与开关，
 * 底层向量检索由 {@link KnowledgeBaseService}（PGVector + BGE 嵌入）提供。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.rag")
public class RagProperties {

    /** 是否启用 RAG 检索增强（关闭时 enhancer 返回空上下文，Agent 退化为无记忆模式） */
    private boolean enabled = true;

    /** 单次检索返回的最大结果数 */
    private int maxResults = 5;

    /** 相似度最低阈值（0.0 - 1.0），低于该值的结果被过滤 */
    private double minScore = 0.6;

    /** 上下文注入开关：控制哪些 Agent 阶段在调用前注入历史上下文 */
    private ContextInjection contextInjection = new ContextInjection();

    /**
     * 上下文注入开关。
     * <p>按 Agent 阶段粒度控制是否注入 RAG 检索结果，便于灰度与对照实验。
     *
     * <p>长期记忆与上下文工程 P1：在 topic-planning / optimization 之外，
     * 新增 content-creation / image-design / publishing / data-analysis 四个开关，
     * 让全部六个 Agent 都能按需注入历史上下文。新开关默认关闭，可按需开启。
     */
    @Data
    public static class ContextInjection {
        /** TopicAgent（选题策划）调用前是否注入历史选题上下文 */
        private boolean topicPlanning = true;

        /** OptimizeAgent（优化迭代）调用前是否注入历史表现模式 */
        private boolean optimization = true;

        /** ContentAgent（内容创作）调用前是否注入历史文章上下文 */
        private boolean contentCreation = false;

        /** ImageAgent（配图设计）调用前是否注入历史配图风格上下文 */
        private boolean imageDesign = false;

        /** PublishAgent（发布排版）调用前是否注入历史发布上下文 */
        private boolean publishing = false;

        /** DataAnalysisAgent（数据分析）调用前是否注入历史分析报告上下文 */
        private boolean dataAnalysis = false;
    }
}
