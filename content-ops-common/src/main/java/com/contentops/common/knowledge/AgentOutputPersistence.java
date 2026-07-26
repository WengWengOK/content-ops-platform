package com.contentops.common.knowledge;

import com.contentops.common.enums.AgentStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 输出持久化（v1.2.0 RAG 知识库 P0 遗留项）。
 *
 * <p>将各 Agent 的结构化输出自动保存为 Markdown 与 JSON 文件，实现：
 * <ul>
 *   <li>结构化 JSON：供程序后续读取、回放、入库 RAG</li>
 *   <li>人类可读 Markdown：供运营人员快速浏览、分享、归档</li>
 * </ul>
 *
 * <p>依赖已有的 {@link FileTools} 完成实际文件写入（含路径沙箱与扩展名校验），
 * 文件按 {@code {stageCode}/{workflowId}_{timestamp}.{ext}} 组织，保证同一次工作流的
 * 多格式输出共享时间戳便于关联。
 */
@Slf4j
@Component
public class AgentOutputPersistence {

    private final FileTools fileTools;
    private final AgentOutputPersistenceProperties properties;
    private final ObjectMapper objectMapper;

    public AgentOutputPersistence(FileTools fileTools, AgentOutputPersistenceProperties properties) {
        this.fileTools = fileTools;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        log.info("AgentOutputPersistence initialized: enabled={}, formats={}, baseDir={}",
                properties.isEnabled(), properties.getFormats(), properties.getBaseDir());
    }

    /**
     * 将 Agent 输出保存为 JSON 与/或 Markdown 文件。
     *
     * <p>保存逻辑：
     * <ol>
     *   <li>功能关闭、stage 为空、result 为空时直接返回空列表</li>
     *   <li>将 result 序列化为 JSON 字符串</li>
     *   <li>生成人类可读的 Markdown 摘要</li>
     *   <li>按 {@link AgentOutputPersistenceProperties#getFormats()} 逐格式写入文件</li>
     * </ol>
     *
     * @param stage      Agent 阶段
     * @param workflowId 工作流 ID（用于归档与追溯）
     * @param result     Agent 的结构化输出对象（任意可被 Jackson 序列化的对象）
     * @return 实际保存的文件相对路径列表；未保存任何文件时返回空列表
     */
    public List<String> saveAgentOutput(AgentStage stage, String workflowId, Object result) {
        if (!properties.isEnabled()) {
            log.debug("Output persistence disabled, skip saving");
            return List.of();
        }
        if (stage == null) {
            log.warn("saveAgentOutput received null stage, skip");
            return List.of();
        }
        if (result == null) {
            log.debug("saveAgentOutput received null result for stage={}, skip", stage.getCode());
            return List.of();
        }

        String json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            log.error("Failed to serialize result for stage={}, workflowId={}",
                    stage.getCode(), workflowId, e);
            return List.of();
        }

        String markdown = buildMarkdownSummary(stage, workflowId, result, json);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
        String safeWorkflow = sanitize(workflowId);

        List<String> savedPaths = new ArrayList<>();
        for (String format : properties.getFormats()) {
            String ext = sanitizeExtension(format);
            String relativePath = stage.getCode() + "/" + safeWorkflow + "_" + timestamp + "." + ext;
            String content = "json".equals(ext) ? json : markdown;

            String writeResult = fileTools.writeLocalFile(relativePath, content);
            if (isWriteSuccess(writeResult)) {
                savedPaths.add(relativePath);
                log.info("Saved {} output for stage={}, workflowId={}: {}",
                        ext, stage.getCode(), workflowId, relativePath);
            } else {
                log.warn("Failed to save {} output for stage={}: {}", ext, stage.getCode(), writeResult);
            }
        }
        return savedPaths;
    }

    /**
     * 便捷方法：仅保存为 JSON 文件并返回相对路径。
     */
    public String saveAsJson(AgentStage stage, String workflowId, Object result) {
        List<String> paths = saveAgentOutput(stage, workflowId, result);
        return paths.stream()
                .filter(p -> p.endsWith(".json"))
                .findFirst()
                .orElse(null);
    }

    /**
     * 便捷方法：仅保存为 Markdown 文件并返回相对路径。
     */
    public String saveAsMarkdown(AgentStage stage, String workflowId, Object result) {
        List<String> paths = saveAgentOutput(stage, workflowId, result);
        return paths.stream()
                .filter(p -> p.endsWith(".md"))
                .findFirst()
                .orElse(null);
    }

    // ──────────────────── 内部工具方法 ────────────────────

    /**
     * 构造人类可读的 Markdown 摘要。
     * <p>包含：阶段、工作流 ID、时间戳、对象渲染，以及 JSON 全文（代码块）便于核对。
     */
    private String buildMarkdownSummary(AgentStage stage, String workflowId,
                                        Object result, String json) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Agent 输出归档\n\n");
        sb.append("- **阶段**: ").append(stage.getNameCn())
                .append("（").append(stage.getCode()).append("）\n");
        sb.append("- **工作流 ID**: ").append(workflowId != null ? workflowId : "unknown").append("\n");
        sb.append("- **生成时间**: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        sb.append("- **类型**: ").append(result.getClass().getSimpleName()).append("\n\n");

        sb.append("## 结构化字段概览\n\n");
        appendRenderedObject(sb, result, 0);

        sb.append("\n## 完整 JSON\n\n");
        sb.append("```json\n").append(json).append("\n```\n");
        return sb.toString();
    }

    /**
     * 递归将对象渲染为 Markdown 键值列表。
     * <p>将对象转为 Map 后逐层展开，List 渲染为有序列表，Map 渲染为键值。
     */
    @SuppressWarnings("unchecked")
    private void appendRenderedObject(StringBuilder sb, Object value, int depth) {
        Object converted;
        try {
            converted = objectMapper.convertValue(value, Object.class);
        } catch (Exception e) {
            sb.append(indent(depth)).append("- ").append(String.valueOf(value)).append("\n");
            return;
        }

        if (converted instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append(indent(depth)).append("- （空）\n");
                return;
            }
            Map<String, Object> ordered = new LinkedHashMap<>();
            map.forEach((k, v) -> ordered.put(String.valueOf(k), v));
            for (Map.Entry<String, Object> entry : ordered.entrySet()) {
                appendEntry(sb, entry.getKey(), entry.getValue(), depth);
            }
        } else if (converted instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append(indent(depth)).append("- （空列表）\n");
                return;
            }
            int idx = 1;
            for (Object item : list) {
                sb.append(indent(depth)).append(idx++).append(". ");
                if (item instanceof Map<?, ?> || item instanceof List<?>) {
                    sb.append("\n");
                    appendRenderedObject(sb, item, depth + 1);
                } else {
                    sb.append(String.valueOf(item)).append("\n");
                }
            }
        } else {
            sb.append(indent(depth)).append("- ").append(String.valueOf(converted)).append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void appendEntry(StringBuilder sb, String key, Object value, int depth) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            sb.append(indent(depth)).append("- **").append(key).append("**:\n");
            appendRenderedObject(sb, value, depth + 1);
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            sb.append(indent(depth)).append("- **").append(key).append("**:\n");
            appendRenderedObject(sb, value, depth + 1);
        } else {
            sb.append(indent(depth)).append("- **").append(key).append("**: ")
                    .append(value == null ? "null" : String.valueOf(value)).append("\n");
        }
    }

    private String indent(int depth) {
        return "  ".repeat(Math.max(0, depth));
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9-]", "_");
    }

    private String sanitizeExtension(String format) {
        if (format == null || format.isBlank()) {
            return "json";
        }
        String ext = format.toLowerCase(Locale.ROOT).replace(".", "").trim();
        return switch (ext) {
            case "md", "markdown" -> "md";
            case "json" -> "json";
            default -> "json";
        };
    }

    private boolean isWriteSuccess(String writeResult) {
        return writeResult != null && writeResult.contains("成功");
    }
}
