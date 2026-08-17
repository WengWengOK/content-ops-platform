package com.contentops.common.mcp;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.rag.AdvancedRagService;
import com.contentops.trend.TrendService;
import com.contentops.orchestrator.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 工具注册表：把平台核心能力暴露给外部 Agent（Claude/Cursor/Codex 等）。
 * 覆盖：热点监控（热榜/搜索/突发）、工作流（启动/状态）、RAG 检索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolRegistry {

    private final TrendService trendService;
    private final WorkflowService workflowService;
    private final AdvancedRagService ragService;
    private final ObjectMapper objectMapper;

    /** 脚手架（McpToolScanner/PlatformToolIntegration）注册的反射工具描述符 */
    private final Map<String, McpToolDescriptor> descriptors = new LinkedHashMap<>();

    private static final Map<String, String> PLATFORM_NAMES = Map.of(
            "xiaohongshu", "小红书",
            "wechat", "公众号",
            "douyin", "抖音",
            "bilibili", "哔哩哔哩",
            "weibo", "微博",
            "zhihu", "知乎");

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(trendsGetHotspots());
        list.add(trendsSearch());
        list.add(trendsBursts());
        list.add(workflowStart());
        list.add(workflowStatus());
        list.add(ragSearch());
        return list;
    }

    // ────────────────────── 脚手架兼容 API（McpServerEndpoint/McpToolScanner 使用） ──────────────────────

    /** 注册反射式 @Tool 描述符 */
    public void register(McpToolDescriptor descriptor) {
        if (descriptor != null && descriptor.getToolName() != null) {
            descriptors.put(descriptor.getToolName(), descriptor);
        }
    }

    /** 全部工具描述符（标准工具自动合成 + 反射注册的描述符） */
    public List<McpToolDescriptor> listTools() {
        List<McpToolDescriptor> result = new ArrayList<>(descriptors.values());
        for (McpTool tool : tools()) {
            if (!descriptors.containsKey(tool.name())) {
                result.add(synthesize(tool));
            }
        }
        return result;
    }

    public McpToolDescriptor getTool(String name) {
        McpToolDescriptor descriptor = descriptors.get(name);
        if (descriptor != null) {
            return descriptor;
        }
        return tools().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .map(this::synthesize)
                .orElse(null);
    }

    /** 执行工具：优先标准工具，其次反射描述符 */
    public Object executeTool(String name, Map<String, Object> args) {
        McpTool tool = tools().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool != null) {
            return tool.call(args == null ? Map.of() : args);
        }
        McpToolDescriptor descriptor = descriptors.get(name);
        if (descriptor == null || descriptor.getMethod() == null || descriptor.getBean() == null) {
            throw new RuntimeException("工具不存在: " + name);
        }
        try {
            Method method = descriptor.getMethod();
            Object[] params = new Object[method.getParameterCount()];
            for (int i = 0; i < method.getParameters().length; i++) {
                String paramName = method.getParameters()[i].getName();
                Object value = args == null ? null : args.get(paramName);
                params[i] = coerce(value, method.getParameterTypes()[i]);
            }
            return method.invoke(descriptor.getBean(), params);
        } catch (Exception e) {
            throw new RuntimeException("工具执行失败: " + name + "，原因: " + e.getMessage(), e);
        }
    }

    private McpToolDescriptor synthesize(McpTool tool) {
        List<McpToolParameter> parameters = new ArrayList<>();
        Object properties = tool.inputSchema().get("properties");
        Object required = tool.inputSchema().get("required");
        if (properties instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                String paramName = String.valueOf(entry.getKey());
                Map<?, ?> schema = entry.getValue() instanceof Map<?, ?> m ? m : Map.of();
                Object descObj = schema.get("description");
                Object typeObj = schema.get("type");
                parameters.add(McpToolParameter.builder()
                        .name(paramName)
                        .description(descObj == null ? "" : String.valueOf(descObj))
                        .type(typeObj == null ? "string" : String.valueOf(typeObj))
                        .required(required instanceof List<?> list && list.contains(paramName))
                        .build());
            }
        }
        return McpToolDescriptor.builder()
                .toolName(tool.name())
                .description(tool.description())
                .parameters(parameters)
                .build();
    }

    private Object coerce(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
        }
        if (targetType == long.class || targetType == Long.class) {
            return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        }
        if (targetType == double.class || targetType == Double.class) {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        }
        if (targetType == List.class && value instanceof List<?> l) {
            return l;
        }
        return String.valueOf(value);
    }

    // ────────────────────── 热点监控 ──────────────────────

    private McpTool trendsGetHotspots() {
        return new McpTool() {
            @Override
            public String name() {
                return "trends_get_hotspots";
            }

            @Override
            public String description() {
                return "获取多平台实时热榜（微博/知乎/抖音/B站/百度/头条/小红书），支持只看突发与时间范围";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "platform", Map.of("type", "string", "description", "平台 code，空则全部"),
                                "limit", Map.of("type", "integer", "description", "返回条数，默认 20"),
                                "burst", Map.of("type", "boolean", "description", "只看突发热点"),
                                "timeRange", Map.of("type", "string", "description", "latest/1h/24h/7d"),
                                "watch", Map.of("type", "boolean", "description", "只看我关注的方向")),
                        "required", List.of());
            }

            @Override
            public String call(Map<String, Object> args) {
                return toJson(trendService.listLatest(
                        str(args.get("platform")), intVal(args.get("limit"), 20),
                        boolVal(args.get("watch")), boolVal(args.get("burst")),
                        str(args.get("timeRange"), "latest")));
            }
        };
    }

    private McpTool trendsSearch() {
        return new McpTool() {
            @Override
            public String name() {
                return "trends_search";
            }

            @Override
            public String description() {
                return "关键词搜索热点（热榜快照内，按热度排序）";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "q", Map.of("type", "string", "description", "关键词"),
                                "platform", Map.of("type", "string"),
                                "limit", Map.of("type", "integer", "default", 10)),
                        "required", List.of("q"));
            }

            @Override
            public String call(Map<String, Object> args) {
                return toJson(trendService.search(
                        str(args.get("q")), str(args.get("platform")), intVal(args.get("limit"), 10)));
            }
        };
    }

    private McpTool trendsBursts() {
        return new McpTool() {
            @Override
            public String name() {
                return "trends_bursts";
            }

            @Override
            public String description() {
                return "查询突发热点事件记录（新上榜/飙升/上升，最近优先）";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "platform", Map.of("type", "string"),
                                "limit", Map.of("type", "integer", "default", 20),
                                "timeRange", Map.of("type", "string", "description", "latest/1h/24h/7d")),
                        "required", List.of());
            }

            @Override
            public String call(Map<String, Object> args) {
                return toJson(trendService.recentBurstEvents(
                        str(args.get("platform")), intVal(args.get("limit"), 20),
                        str(args.get("timeRange"), "latest")));
            }
        };
    }

    // ────────────────────── 工作流 ──────────────────────

    private McpTool workflowStart() {
        return new McpTool() {
            @Override
            public String name() {
                return "workflow_start";
            }

            @Override
            public String description() {
                return "启动内容生产工作流（选题→内容→配图→发布），返回 workflowId";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "accountName", Map.of("type", "string", "description", "账号名称"),
                                "niche", Map.of("type", "string", "description", "定位领域"),
                                "targetAudience", Map.of("type", "string"),
                                "tone", Map.of("type", "string"),
                                "topicHint", Map.of("type", "string", "description", "选题方向"),
                                "platforms", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "平台 code 列表，如 xiaohongshu/wechat/douyin"),
                                "publishMode", Map.of("type", "string", "description", "text-cover/image-text/full-image")),
                        "required", List.of("accountName", "topicHint"));
            }

            @Override
            public String call(Map<String, Object> args) {
                String workflowId = UUID.randomUUID().toString();
                List<String> platforms = new ArrayList<>();
                Object rawPlatforms = args.get("platforms");
                if (rawPlatforms instanceof List<?> list) {
                    list.forEach(p -> platforms.add(String.valueOf(p)));
                } else if (rawPlatforms instanceof String s && !s.isBlank()) {
                    for (String p : s.split(",")) {
                        platforms.add(p.trim());
                    }
                }
                if (platforms.isEmpty()) {
                    platforms.add("xiaohongshu");
                }
                List<String> names = platforms.stream()
                        .map(p -> PLATFORM_NAMES.getOrDefault(p, p)).toList();

                AccountProfile profile = new AccountProfile();
                profile.setAccountName(str(args.get("accountName"), "AI 内容账号"));
                profile.setNiche(str(args.get("niche"), "通用内容"));
                profile.setTargetAudience(str(args.get("targetAudience"), "大众用户"));
                profile.setTone(str(args.get("tone"), "轻松实用"));
                profile.setPlatforms(names);

                Map<String, Object> inputs = new LinkedHashMap<>();
                inputs.put("topicHint", str(args.get("topicHint"), ""));
                inputs.put("maxCycles", 1);
                inputs.put("publishMode", str(args.get("publishMode"), "text-cover"));
                inputs.put("collectionIds", List.of());
                inputs.put("platforms", platforms);
                inputs.put("platformNames", names);

                TaskContext context = TaskContext.builder()
                        .workflowId(workflowId)
                        .currentStage(AgentStage.TOPIC_PLANNING.getCode())
                        .accountProfile(profile)
                        .inputs(inputs)
                        .accumulatedArtifacts(new LinkedHashMap<>())
                        .status(TaskStatus.PENDING.name())
                        .createdAt(LocalDateTime.now())
                        .requireHumanReview(false)
                        .maxCycles(1)
                        .cycleCount(1)
                        .build();
                workflowService.startWorkflow(context);
                return toJson(Map.of(
                        "workflowId", workflowId,
                        "currentStage", AgentStage.TOPIC_PLANNING.getCode(),
                        "message", "Workflow started. Topic Planning Agent is now executing."));
            }
        };
    }

    private McpTool workflowStatus() {
        return new McpTool() {
            @Override
            public String name() {
                return "workflow_status";
            }

            @Override
            public String description() {
                return "查询工作流状态（当前阶段/子阶段/状态/产物）";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of("workflowId", Map.of("type", "string")),
                        "required", List.of("workflowId"));
            }

            @Override
            public String call(Map<String, Object> args) {
                TaskContext context = workflowService.getWorkflowStatus(str(args.get("workflowId")));
                if (context == null) {
                    return "工作流不存在: " + args.get("workflowId");
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("workflowId", context.getWorkflowId());
                data.put("currentStage", context.getCurrentStage());
                data.put("currentSubStage", context.getCurrentSubStage());
                data.put("status", context.getStatus());
                data.put("errorMessage", context.getErrorMessage());
                data.put("updatedAt", context.getUpdatedAt());
                return toJson(data);
            }
        };
    }

    // ────────────────────── RAG ──────────────────────

    private McpTool ragSearch() {
        return new McpTool() {
            @Override
            public String name() {
                return "rag_search";
            }

            @Override
            public String description() {
                return "知识库混合检索（关键词+向量+重排），返回最相关内容片段";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string"),
                                "topK", Map.of("type", "integer", "default", 5)),
                        "required", List.of("query"));
            }

            @Override
            public String call(Map<String, Object> args) {
                try {
                    List<AdvancedRagService.RetrievalResult> results =
                            ragService.retrieveAndRerank(str(args.get("query")), null, intVal(args.get("topK"), 5));
                    if (results.isEmpty()) {
                        return "知识库暂无相关结果";
                    }
                    StringBuilder sb = new StringBuilder("知识库检索结果（" + results.size() + " 条）：\n");
                    for (AdvancedRagService.RetrievalResult r : results) {
                        sb.append("- [").append(r.source() == null ? "未知来源" : r.source())
                                .append("] ").append(r.content()).append("\n");
                    }
                    return sb.toString();
                } catch (Exception e) {
                    return "RAG 检索失败: " + e.getMessage();
                }
            }
        };
    }

    // ────────────────────── 工具函数 ──────────────────────

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String str(Object v, String def) {
        String s = str(v);
        return s == null || s.isBlank() ? def : s;
    }

    private int intVal(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return def;
    }

    private boolean boolVal(Object v) {
        return v instanceof Boolean b && b;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
