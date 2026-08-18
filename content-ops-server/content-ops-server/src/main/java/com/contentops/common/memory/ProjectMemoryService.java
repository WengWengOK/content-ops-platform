package com.contentops.common.memory;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.knowledge.KnowledgeBaseService;
import com.contentops.common.knowledge.KnowledgeBaseService.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 跨工作流项目记忆读写服务（长期记忆与上下文工程 P2）。
 *
 * <p>按 accountId 维度在 Redis 中沉淀/加载项目记忆，让同账号的多次工作流共享历史上下文：
 * <ul>
 *   <li>{@link #enrichContextWithMemory} — 工作流启动时调用，把项目记忆摘要注入
 *       {@link TaskContext#getInputs()} 的 {@code projectMemory} 字段</li>
 *   <li>{@link #summarizeWorkflow} — 工作流完成时调用，提取本次工作流的关键信息
 *       （选题/表现/风格）更新到项目记忆</li>
 * </ul>
 *
 * <p><b>降级策略：</b>
 * <ul>
 *   <li>{@code enabled=false} 时所有方法短路返回，不阻断主流程</li>
 *   <li>Redis 不可用时记日志跳过，不抛异常</li>
 *   <li>冷数据（历史输出全文）复用知识库向量检索，不重复存储</li>
 * </ul>
 */
@Slf4j
@Component
public class ProjectMemoryService {

    private final ProjectMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final KnowledgeBaseService knowledgeBaseService;

    @Autowired
    public ProjectMemoryService(ProjectMemoryProperties properties,
                                @Autowired(required = false) StringRedisTemplate redisTemplate,
                                @Autowired(required = false) KnowledgeBaseService knowledgeBaseService) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        log.info("ProjectMemoryService initialized: enabled={}, ttlDays={}, redisAvailable={}",
                properties.isEnabled(), properties.getTtlDays(), redisTemplate != null);
    }

    /**
     * 工作流启动时加载项目记忆并注入到 TaskContext.inputs。
     *
     * <p>把项目记忆摘要（偏好风格、近期选题、高表现选题）格式化为字符串，
     * 塞入 {@code inputs.put("projectMemory", summary)}，供各 Agent 在 prompt 中引用。
     * 失败时只记日志，不影响工作流启动。
     */
    public void enrichContextWithMemory(TaskContext context) {
        if (!properties.isEnabled() || context == null) {
            return;
        }
        String accountId = extractAccountId(context);
        if (accountId == null) {
            log.debug("[ProjectMemory] accountId 为空，跳过项目记忆注入 workflowId={}",
                    context.getWorkflowId());
            return;
        }
        try {
            ProjectMemory memory = loadMemory(accountId);
            String summary = formatMemorySummary(memory);
            if (summary != null && !summary.isBlank()) {
                if (context.getInputs() == null) {
                    context.setInputs(new java.util.HashMap<>());
                }
                context.getInputs().put("projectMemory", summary);
                log.info("[ProjectMemory] 项目记忆已注入 accountId={}, workflowId={}, chars={}",
                        accountId, context.getWorkflowId(), summary.length());
            }
        } catch (Exception e) {
            log.warn("[ProjectMemory] 加载项目记忆失败 accountId={}: {}",
                    accountId, e.getMessage());
        }
    }

    /**
     * 工作流完成时沉淀本次工作流摘要到项目记忆。
     *
     * <p>从 {@link TaskContext#getAccumulatedArtifacts()} 提取选题、表现、风格等关键信息，
     * 追加到 {@link ProjectMemory#getRecentWorkflowSummaries()}，并刷新高表现选题。
     * 失败时只记日志，不影响工作流完成。
     */
    public void summarizeWorkflow(TaskContext context) {
        if (!properties.isEnabled() || context == null) {
            return;
        }
        String accountId = extractAccountId(context);
        if (accountId == null) {
            return;
        }
        try {
            ProjectMemory memory = loadMemory(accountId);
            ProjectMemory.WorkflowSummary summary = extractSummary(context);
            prependSummary(memory, summary);
            refreshTopPerformingTopics(memory, accountId, context);
            memory.setAccountId(accountId);
            memory.setNiche(extractNiche(context));
            memory.setUpdatedAt(Instant.now());
            saveMemory(accountId, memory);
            log.info("[ProjectMemory] 项目记忆已沉淀 accountId={}, workflowId={}, totalSummaries={}",
                    accountId, context.getWorkflowId(), memory.getRecentWorkflowSummaries().size());
        } catch (Exception e) {
            log.warn("[ProjectMemory] 沉淀项目记忆失败 accountId={}: {}",
                    accountId, e.getMessage());
        }
    }

    /**
     * 从 Redis 加载项目记忆；不存在时返回空记忆。
     */
    public ProjectMemory loadMemory(String accountId) {
        if (redisTemplate == null || accountId == null) {
            return new ProjectMemory();
        }
        try {
            String key = properties.getKeyPrefix() + accountId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return new ProjectMemory();
            }
            return objectMapper.readValue(json, ProjectMemory.class);
        } catch (Exception e) {
            log.warn("[ProjectMemory] 读取 Redis 失败 accountId={}: {}", accountId, e.getMessage());
            return new ProjectMemory();
        }
    }

    /**
     * 保存项目记忆到 Redis（带 TTL）。
     */
    private void saveMemory(String accountId, ProjectMemory memory) {
        if (redisTemplate == null) {
            log.debug("[ProjectMemory] Redis 不可用，跳过保存 accountId={}", accountId);
            return;
        }
        try {
            String key = properties.getKeyPrefix() + accountId;
            String json = objectMapper.writeValueAsString(memory);
            Duration ttl = Duration.ofDays(properties.getTtlDays());
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("[ProjectMemory] 写入 Redis 失败 accountId={}: {}", accountId, e.getMessage());
        }
    }

    // ──────────────────── 内部工具方法 ────────────────────

    @SuppressWarnings("unchecked")
    private ProjectMemory.WorkflowSummary extractSummary(TaskContext context) {
        ProjectMemory.WorkflowSummary summary = new ProjectMemory.WorkflowSummary();
        summary.setWorkflowId(context.getWorkflowId());
        summary.setCompletedAt(Instant.now());

        Map<String, Object> artifacts = context.getAccumulatedArtifacts();
        if (artifacts != null) {
            // 选题
            Map<String, Object> topicOut = (Map<String, Object>) artifacts.get("topic-planning");
            if (topicOut != null) {
                summary.setTopic(extractString(topicOut, "topic", "title"));
            }
            // 发布平台
            Map<String, Object> publishOut = (Map<String, Object>) artifacts.get("publishing");
            if (publishOut != null) {
                summary.setPlatform(extractString(publishOut, "platform"));
            }
            // 表现
            Map<String, Object> analysisOut = (Map<String, Object>) artifacts.get("data-analysis");
            if (analysisOut != null) {
                summary.setPerformance(extractString(analysisOut, "summary", "report"));
            }
            // 风格
            Map<String, Object> contentOut = (Map<String, Object>) artifacts.get("content-creation");
            if (contentOut != null) {
                summary.setStyle(extractString(contentOut, "summary", "tone"));
            }
        }
        return summary;
    }

    private void prependSummary(ProjectMemory memory, ProjectMemory.WorkflowSummary summary) {
        List<ProjectMemory.WorkflowSummary> list = memory.getRecentWorkflowSummaries();
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(0, summary);
        // 截断到最大条数
        while (list.size() > properties.getMaxRecentSummaries()) {
            list.remove(list.size() - 1);
        }
        memory.setRecentWorkflowSummaries(list);
    }

    /**
     * 从知识库检索历史高表现选题（冷数据复用）。
     */
    private void refreshTopPerformingTopics(ProjectMemory memory, String accountId, TaskContext context) {
        if (knowledgeBaseService == null || !knowledgeBaseService.isAvailable()) {
            return;
        }
        try {
            String niche = extractNiche(context);
            String query = "高表现 优秀 选题" + (niche != null ? " " + niche : "");
            List<SearchResult> results = knowledgeBaseService.searchSimilar(
                    query, properties.getTopPerformingTopicsCount(), null);
            List<String> topics = new ArrayList<>();
            for (SearchResult r : results) {
                if (r.metadata() != null) {
                    String title = r.metadata().get("title");
                    if (title != null && !title.isBlank()) {
                        topics.add(title);
                    }
                }
            }
            if (!topics.isEmpty()) {
                memory.setTopPerformingTopics(topics);
            }
        } catch (Exception e) {
            log.debug("[ProjectMemory] 刷新高表现选题失败 accountId={}: {}", accountId, e.getMessage());
        }
    }

    private String formatMemorySummary(ProjectMemory memory) {
        if (memory == null || memory.getRecentWorkflowSummaries().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目历史记忆（跨工作流）\n\n");
        if (memory.getPreferredStyle() != null && !memory.getPreferredStyle().isBlank()) {
            sb.append("> 偏好风格: ").append(memory.getPreferredStyle()).append("\n\n");
        }
        if (!memory.getTopPerformingTopics().isEmpty()) {
            sb.append("### 历史高表现选题\n");
            int idx = 1;
            for (String t : memory.getTopPerformingTopics()) {
                sb.append(idx++).append(". ").append(t).append("\n");
            }
            sb.append("\n");
        }
        if (!memory.getRecentWorkflowSummaries().isEmpty()) {
            sb.append("### 近期工作流摘要\n");
            int idx = 1;
            for (ProjectMemory.WorkflowSummary s : memory.getRecentWorkflowSummaries()) {
                sb.append(idx++).append(". 选题: ").append(s.getTopic() != null ? s.getTopic() : "未知");
                if (s.getPerformance() != null && !s.getPerformance().isBlank()) {
                    sb.append(" | 表现: ").append(truncate(s.getPerformance(), 80));
                }
                sb.append("\n");
            }
        }
        sb.append("\n请参考以上历史记忆保持风格一致性，避免重复选题，复用高表现方向。\n");
        return sb.toString();
    }

    private String extractAccountId(TaskContext context) {
        return context.getAccountProfile() != null
                ? context.getAccountProfile().getAccountId() : null;
    }

    private String extractNiche(TaskContext context) {
        return context.getAccountProfile() != null
                ? context.getAccountProfile().getNiche() : null;
    }

    @SuppressWarnings("unchecked")
    private String extractString(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object v = data.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            } else if (v instanceof Number || v instanceof Boolean) {
                return String.valueOf(v);
            } else if (v instanceof List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
        }
        return null;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max) + "...";
    }
}
