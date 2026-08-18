package com.contentops.common.memory;

import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨工作流项目记忆数据模型（长期记忆与上下文工程 P2）。
 *
 * <p>按 accountId 维度沉淀同一账号跨工作流的历史信息，让每次工作流不再从零开始：
 * <ul>
 *   <li>{@link #recentWorkflowSummaries} — 近期 N 次工作流摘要（选题/表现/风格），
 *       从 {@link com.contentops.orchestrator.workflow.StageExecutor#handleStageSuccess}
 *       终态时调用 {@link ProjectMemoryService#summarizeWorkflow} 沉淀</li>
 *   <li>{@link #preferredStyle} — 偏好风格摘要（可复用 StyleProfile 的结论）</li>
 *   <li>{@link #topPerformingTopics} — 历史高表现选题（冷数据，从知识库向量检索聚合）</li>
 * </ul>
 *
 * <p>存储于 Redis，key：{@code contentops:project-memory:{accountId}}，TTL 30 天。
 * 冷数据（历史输出全文）已由 {@link com.contentops.common.knowledge.AgentOutputIngester}
 * 沉淀进知识库向量库，本模型只存"摘要级"热数据。
 */
@Data
public class ProjectMemory implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 账号 ID（主维度） */
    private String accountId;

    /** 账号领域/赛道 */
    private String niche;

    /** 近期工作流摘要列表（按时间倒序，最近在前，最多 maxRecentSummaries 条） */
    private List<WorkflowSummary> recentWorkflowSummaries = new ArrayList<>();

    /** 偏好风格摘要（自然语言描述，可复用 StyleProfile 结论） */
    private String preferredStyle;

    /** 历史高表现选题（从知识库聚合的标题列表） */
    private List<String> topPerformingTopics = new ArrayList<>();

    /** 最后更新时间 */
    private Instant updatedAt;

    /**
     * 单次工作流摘要。
     */
    @Data
    public static class WorkflowSummary implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 工作流 ID */
        private String workflowId;

        /** 本次选题（标题/方向） */
        private String topic;

        /** 发布平台 */
        private String platform;

        /** 表现摘要（如有，来自 AnalysisAgent） */
        private String performance;

        /** 风格摘要（如有，来自 ContentAgent） */
        private String style;

        /** 完成时间 */
        private Instant completedAt;
    }
}
