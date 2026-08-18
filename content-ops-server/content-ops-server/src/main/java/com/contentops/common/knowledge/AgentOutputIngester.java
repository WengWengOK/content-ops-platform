package com.contentops.common.knowledge;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 输出 → 知识库统一转换器（长期记忆与上下文工程 P0：打通闭环）。
 *
 * <p>此前 {@link KnowledgeBaseService} 的 ingest/ingestArticle/ingestTopicPlan/
 * ingestAnalysisReport 等写入方法<b>无任何调用方</b>——知识库"只读不写"，
 * RAG 检索永远搜不到本平台产出的内容。本类补齐该闭环：
 * <ul>
 *   <li>Agent 执行成功后，把结构化输出转换为可检索文本 + metadata，
 *       调用 {@link KnowledgeBaseService#ingest} 写入向量库；</li>
 *   <li>同时委托 {@link AgentOutputPersistence#saveAgentOutput} 落盘 JSON/Markdown
 *       审计副本，实现"向量库可检索 + 文件可审计"双持久化。</li>
 * </ul>
 *
 * <p><b>降级策略：</b>所有写入失败只记日志，不阻断工作流主流程
 * （参考 {@link RagRetrievalEnhancer} 的降级模式）。
 *
 * <h3>metadata 约定</h3>
 * <ul>
 *   <li>{@code type} — 内容类型：article / topic_plan / analysis_report / image_design / optimization</li>
 *   <li>{@code agent} — 产出 Agent 的 stageCode</li>
 *   <li>{@code accountId} — 账号 ID（跨工作流长期记忆的主维度）</li>
 *   <li>{@code niche} — 账号领域/赛道</li>
 *   <li>{@code workflowId} — 产出该内容的工作流 ID</li>
 *   <li>{@code timestamp} — 入库时间（ISO-8601）</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentOutputIngester {

    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentOutputPersistence agentOutputPersistence;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AgentOutputIngester(KnowledgeBaseService knowledgeBaseService,
                               AgentOutputPersistence agentOutputPersistence) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.agentOutputPersistence = agentOutputPersistence;
        log.info("AgentOutputIngester initialized: knowledgeBaseAvailable={}, storeMode={}",
                knowledgeBaseService.isAvailable(), knowledgeBaseService.storeMode());
    }

    /**
     * 把 Agent 输出 ingest 进知识库 + 落盘审计副本。
     *
     * <p>按 {@link AgentStage} 类型路由到对应的 ingest 方法，并提取可检索文本与 metadata。
     * 失败时只记日志，不抛异常，不影响工作流主流程。
     *
     * @param stage   Agent 阶段
     * @param data    Agent 输出数据（response.getData()，键值对形式）
     * @param context 工作流上下文（提供 accountId/niche/workflowId 等维度信息）
     */
    public void ingest(AgentStage stage, Map<String, Object> data, TaskContext context) {
        if (stage == null || data == null || data.isEmpty()) {
            log.debug("Skip ingest: stage={}, dataNull={}", stage, data == null);
            return;
        }

        // 1. 落盘审计副本（独立于向量库，即使知识库不可用也保留文件）
        try {
            agentOutputPersistence.saveAgentOutput(stage, context.getWorkflowId(), data);
        } catch (Exception e) {
            log.warn("[Ingester] 落盘失败 stage={}, workflowId={}: {}",
                    stage.getCode(), context.getWorkflowId(), e.getMessage());
        }

        // 2. 写入向量库（知识库不可用时跳过）
        if (!knowledgeBaseService.isAvailable()) {
            log.debug("[Ingester] 知识库不可用，跳过向量入库 stage={}", stage.getCode());
            return;
        }

        String niche = extractNiche(context);
        String accountId = extractAccountId(context);
        String workflowId = context.getWorkflowId();

        try {
            switch (stage) {
                case TOPIC_PLANNING -> ingestTopicPlan(data, niche, accountId, workflowId);
                case CONTENT_CREATION, PUBLISHING -> ingestArticle(stage, data, niche, accountId, workflowId);
                case DATA_ANALYSIS -> ingestAnalysisReport(data, niche, accountId, workflowId);
                case IMAGE_DESIGN, OPTIMIZATION -> ingestGeneric(stage, data, niche, accountId, workflowId);
            }
        } catch (Exception e) {
            log.warn("[Ingester] 向量入库失败 stage={}, workflowId={}: {}",
                    stage.getCode(), workflowId, e.getMessage());
        }
    }

    /**
     * 便捷重载：仅提供维度三元组（供 LangGraph 引擎的 AgentNodeAdapter 使用，
     * 避免完整重建 TaskContext）。
     *
     * @param stage     Agent 阶段
     * @param data      Agent 输出数据
     * @param accountId 账号 ID
     * @param niche     账号领域
     * @param workflowId 工作流 ID
     */
    public void ingest(AgentStage stage, Map<String, Object> data,
                       String accountId, String niche, String workflowId) {
        if (stage == null || data == null || data.isEmpty()) {
            return;
        }
        try {
            agentOutputPersistence.saveAgentOutput(stage, workflowId, data);
        } catch (Exception e) {
            log.warn("[Ingester] 落盘失败 stage={}, workflowId={}: {}",
                    stage.getCode(), workflowId, e.getMessage());
        }
        if (!knowledgeBaseService.isAvailable()) {
            return;
        }
        try {
            switch (stage) {
                case TOPIC_PLANNING -> ingestTopicPlan(data, niche, accountId, workflowId);
                case CONTENT_CREATION, PUBLISHING -> ingestArticle(stage, data, niche, accountId, workflowId);
                case DATA_ANALYSIS -> ingestAnalysisReport(data, niche, accountId, workflowId);
                case IMAGE_DESIGN, OPTIMIZATION -> ingestGeneric(stage, data, niche, accountId, workflowId);
            }
        } catch (Exception e) {
            log.warn("[Ingester] 向量入库失败 stage={}, workflowId={}: {}",
                    stage.getCode(), workflowId, e.getMessage());
        }
    }

    // ──────────────────── 按 stage 类型的 ingest 实现 ────────────────────

    private void ingestTopicPlan(Map<String, Object> data, String niche,
                                 String accountId, String workflowId) {
        String content = extractText(data, "topics", "topic", "title", "description", "content");
        if (content.isBlank()) {
            return;
        }
        // KnowledgeBaseService.ingestTopicPlan 不带 accountId，用通用 ingest 补 metadata
        Map<String, String> metadata = buildMetadata("topic_plan", "topic-planning",
                accountId, niche, workflowId);
        metadata.put("title", extractFirst(data, "title", "topic"));
        knowledgeBaseService.ingest(content, metadata);
        log.info("[Ingester] topic-plan ingested: workflowId={}, chars={}",
                workflowId, content.length());
    }

    private void ingestArticle(AgentStage stage, Map<String, Object> data, String niche,
                               String accountId, String workflowId) {
        String title = extractFirst(data, "title", "topic");
        String body = extractText(data, "draftContent", "content", "body", "markdown", "summary");
        if (body.isBlank()) {
            body = safeJson(data);
        }
        String platform = extractFirst(data, "platform");
        String metrics = extractFirst(data, "metrics", "performance");
        String fullContent = "标题: " + title + "\n\n" + body;

        Map<String, String> metadata = buildMetadata("article", stage.getCode(),
                accountId, niche, workflowId);
        metadata.put("title", title);
        metadata.put("platform", platform);
        metadata.put("metrics", metrics);
        knowledgeBaseService.ingest(fullContent, metadata);
        log.info("[Ingester] article ingested: stage={}, workflowId={}, title={}, chars={}",
                stage.getCode(), workflowId, title, fullContent.length());
    }

    private void ingestAnalysisReport(Map<String, Object> data, String niche,
                                      String accountId, String workflowId) {
        String content = extractText(data, "report", "analysis", "summary", "content", "findings");
        if (content.isBlank()) {
            content = safeJson(data);
        }
        Map<String, String> metadata = buildMetadata("analysis_report", "data-analysis",
                accountId, niche, workflowId);
        metadata.put("title", extractFirst(data, "title", "reportTitle"));
        knowledgeBaseService.ingest(content, metadata);
        log.info("[Ingester] analysis-report ingested: workflowId={}, chars={}",
                workflowId, content.length());
    }

    private void ingestGeneric(AgentStage stage, Map<String, Object> data, String niche,
                               String accountId, String workflowId) {
        String content = extractText(data, "content", "summary", "description", "result", "output");
        if (content.isBlank()) {
            content = safeJson(data);
        }
        String type = stage == AgentStage.IMAGE_DESIGN ? "image_design" : "optimization";
        Map<String, String> metadata = buildMetadata(type, stage.getCode(),
                accountId, niche, workflowId);
        metadata.put("title", extractFirst(data, "title", "name"));
        knowledgeBaseService.ingest(content, metadata);
        log.info("[Ingester] {} ingested: workflowId={}, chars={}",
                type, workflowId, content.length());
    }

    // ──────────────────── 工具方法 ────────────────────

    private Map<String, String> buildMetadata(String type, String agent,
                                              String accountId, String niche, String workflowId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("type", type);
        metadata.put("agent", agent);
        metadata.put("accountId", accountId != null ? accountId : "unknown");
        metadata.put("niche", niche != null ? niche : "unknown");
        metadata.put("workflowId", workflowId != null ? workflowId : "");
        metadata.put("timestamp", Instant.now().toString());
        return metadata;
    }

    private String extractAccountId(TaskContext context) {
        return context.getAccountProfile() != null
                ? context.getAccountProfile().getAccountId() : null;
    }

    private String extractNiche(TaskContext context) {
        return context.getAccountProfile() != null
                ? context.getAccountProfile().getNiche() : null;
    }

    /**
     * 按候选 key 顺序提取第一个非空字符串值。
     */
    @SuppressWarnings("unchecked")
    private String extractFirst(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object v = data.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            } else if (v instanceof Number || v instanceof Boolean) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    /**
     * 按候选 key 顺序拼接多个字段的文本（用于可检索内容）。
     */
    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> data, String... keys) {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            Object v = data.get(key);
            if (v == null) {
                continue;
            }
            if (v instanceof String s && !s.isBlank()) {
                sb.append(s).append("\n");
            } else if (v instanceof java.util.List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        sb.append(String.valueOf(item)).append("\n");
                    }
                }
            } else if (v instanceof Map<?, ?> map) {
                sb.append(safeJson(map)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String safeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
