package com.contentops.common.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan-and-Execute（规划-执行）模式 Agent。
 *
 * <p>将复杂任务的处理拆分为两个阶段：先由 Planner 规划出子任务列表，再由 Executor
 * 逐个（或并行）执行子任务，并在执行过程中根据实际情况动态重新规划。
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li><b>规划</b>：Planner Agent（通常为 {@link AgentRole#SUPERVISOR}）调用 LLM，
 *       将根任务分解为带依赖关系的子任务列表（DAG）</li>
 *   <li><b>执行</b>：按拓扑顺序执行子任务，依赖满足的子任务可并行执行
 *       （由 {@link ReActAgentExecutor} 实际执行单个子任务）</li>
 *   <li><b>重规划</b>：当某子任务失败或暴露出计划缺陷时，将已完成结果反馈给 Planner
 *       重新规划剩余子任务（受 {@code max-replan-count} 限制）</li>
 *   <li><b>聚合</b>：所有子任务完成后，合并结果作为根任务的最终输出</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AgentResult result = planAndExecuteAgent.execute(rootTask, AgentRole.SUPERVISOR);
 * }</pre>
 *
 * @see ReActAgentExecutor
 * @see AgentTask
 * @see AgentResult
 * @see MultiAgentProperties.PlanAndExecuteConfig
 */
@Slf4j
@Component
public class PlanAndExecuteAgent {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ReActAgentExecutor reactExecutor;
    private final MultiAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    /** 匹配模型输出中第一个 JSON 数组（兼容模型额外附带说明文本的场景）。 */
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[\\s*\\{[\\s\\S]*}\\s*]");

    /** 规划提示词模板。 */
    private static final String PLAN_SYSTEM_PROMPT = """
            你是任务规划专家。请将给定的复杂任务分解为可执行的子任务列表。
            输出要求：只输出一个 JSON 数组，不要输出任何其他内容。每个元素格式如下：
            {"taskId":"s1","description":"子任务描述","assignedRole":"researcher","dependencies":[],"priority":8}
            字段说明：
            - taskId: 子任务唯一标识（如 s1、s2）
            - description: 子任务的自然语言描述
            - assignedRole: 执行角色，可选值 supervisor / researcher / writer / reviewer / critic
            - dependencies: 依赖的前置子任务 taskId 列表
            - priority: 优先级（1-10，10 最高）
            分解原则：粒度适中、依赖清晰、角色匹配、覆盖完整。
            """;

    /**
     * 构造 Plan-and-Execute Agent。
     *
     * @param chatModelProvider ChatModel 提供者（用于规划，允许缺失降级）
     * @param reactExecutor     ReAct 执行器（用于执行单个子任务）
     * @param properties        多 Agent 配置
     * @param objectMapper      Jackson JSON 解析器
     * @param executorService   多 Agent 线程池（用于并行执行子任务）
     */
    public PlanAndExecuteAgent(ObjectProvider<ChatModel> chatModelProvider,
                                ReActAgentExecutor reactExecutor,
                                MultiAgentProperties properties,
                                ObjectMapper objectMapper,
                                @Qualifier(MultiAgentThreadPoolConfig.EXECUTOR_BEAN_NAME)
                                ExecutorService executorService) {
        this.chatModelProvider = chatModelProvider;
        this.reactExecutor = reactExecutor;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executorService = executorService;
        log.info("[PlanAndExecute] 已初始化, maxReplan={}, parallelSubtasks={}",
                properties.getPlanAndExecute().getMaxReplanCount(),
                properties.getPlanAndExecute().isParallelSubtasks());
    }

    // ──────────────────────── 主入口 ────────────────────────

    /**
     * 执行 Plan-and-Execute 流程。
     *
     * @param rootTask    根任务
     * @param plannerRole 规划角色（提供系统提示词与温度）
     * @return 根任务的聚合结果
     */
    public AgentResult execute(AgentTask rootTask, AgentRole plannerRole) {
        long start = System.currentTimeMillis();
        log.info("[PlanAndExecute] 开始, taskId={}", rootTask.taskId());

        // 第 1 步：规划
        List<AgentTask> plan = plan(rootTask, plannerRole, Map.of());
        if (plan.isEmpty()) {
            // 规划失败或模型不可用 → 直接用 ReAct 执行根任务（降级）
            log.warn("[PlanAndExecute] 规划未产出子任务，降级为直接 ReAct 执行, taskId={}",
                    rootTask.taskId());
            AgentResult direct = reactExecutor.execute(rootTask, resolveRole(rootTask.assignedRole()));
            return direct;
        }

        // 第 2 步：执行（含重规划）
        Map<String, AgentResult> completed = new ConcurrentHashMap<>();
        int replanCount = 0;
        int maxReplan = properties.getPlanAndExecute().getMaxReplanCount();

        while (true) {
            List<AgentTask> remaining = plan.stream()
                    .filter(t -> !completed.containsKey(t.taskId()))
                    .toList();

            if (remaining.isEmpty()) {
                break; // 全部完成
            }

            // 执行一轮（按依赖并行/顺序）
            boolean allSuccess = executeWave(remaining, completed, rootTask.taskId());

            // 检查是否需要重规划
            boolean hasFailure = completed.values().stream().anyMatch(r -> !r.success());
            List<AgentTask> stillRemaining = plan.stream()
                    .filter(t -> !completed.containsKey(t.taskId()))
                    .toList();

            if (!stillRemaining.isEmpty() && hasFailure && replanCount < maxReplan) {
                replanCount++;
                log.info("[PlanAndExecute] 触发重规划 #{}, taskId={}, 已完成={}, 剩余={}",
                        replanCount, rootTask.taskId(), completed.size(), stillRemaining.size());
                // 将已完成结果作为上下文重新规划
                List<AgentTask> replanned = plan(rootTask, plannerRole, summarizeCompleted(completed));
                // 合并：保留已完成子任务，用新计划替换剩余部分
                plan = mergePlan(plan, replanned, completed.keySet());
            } else if (!stillRemaining.isEmpty()) {
                // 无法重规划或重规划次数耗尽，跳过无法执行的剩余子任务
                log.warn("[PlanAndExecute] 剩余 {} 个子任务无法完成（依赖失败或重规划耗尽），taskId={}",
                        stillRemaining.size(), rootTask.taskId());
                break;
            }
        }

        // 第 3 步：聚合
        AgentResult aggregated = AgentResult.mergeAll(rootTask.taskId(),
                new ArrayList<>(completed.values()));
        long elapsed = System.currentTimeMillis() - start;
        log.info("[PlanAndExecute] 完成, taskId={}, 子任务={}, 成功={}, 耗时={}ms",
                rootTask.taskId(), completed.size(),
                completed.values().stream().filter(AgentResult::success).count(), elapsed);

        // 聚合结果补充执行耗时
        return new AgentResult(
                aggregated.taskId(),
                aggregated.success(),
                aggregated.output(),
                aggregated.errors(),
                elapsed,
                aggregated.tokenUsage(),
                aggregated.qualityScore()
        );
    }

    // ──────────────────────── 规划 ────────────────────────

    /**
     * 调用 LLM 进行任务规划，返回子任务列表。
     *
     * @param rootTask     根任务
     * @param plannerRole  规划角色
     * @param contextExtra 额外上下文（已完成子任务的摘要，用于重规划）
     * @return 子任务列表（规划失败时返回空列表）
     */
    public List<AgentTask> plan(AgentTask rootTask, AgentRole plannerRole, Map<String, String> contextExtra) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("[PlanAndExecute] ChatModel 不可用，规划降级为空列表");
            return List.of();
        }

        String userPrompt = buildPlanPrompt(rootTask, contextExtra);
        try {
            var planResponse = chatModel.chat(
                    SystemMessage.from(plannerRole.systemPrompt() + "\n\n" + PLAN_SYSTEM_PROMPT),
                    UserMessage.from(userPrompt));
            String raw = planResponse.aiMessage() != null ? planResponse.aiMessage().text() : null;
            return parsePlan(raw, rootTask.taskId());
        } catch (Exception e) {
            log.error("[PlanAndExecute] 规划异常, taskId={}", rootTask.taskId(), e);
            return List.of();
        }
    }

    /**
     * 构建规划用户提示词。
     */
    private String buildPlanPrompt(AgentTask rootTask, Map<String, String> contextExtra) {
        StringBuilder sb = new StringBuilder("请分解以下任务为子任务列表：\n");
        sb.append("任务描述：").append(rootTask.description()).append('\n');
        if (!rootTask.inputs().isEmpty()) {
            sb.append("输入参数：").append(rootTask.inputs()).append('\n');
        }
        if (contextExtra != null && !contextExtra.isEmpty()) {
            sb.append("已完成子任务摘要（用于重规划参考）：\n");
            contextExtra.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        }
        sb.append("\n请输出 JSON 数组。");
        return sb.toString();
    }

    /**
     * 解析模型输出为子任务列表。
     *
     * <p>兼容模型在 JSON 前后附加说明文本的情况，提取首个 JSON 数组后解析。
     */
    List<AgentTask> parsePlan(String raw, String rootTaskId) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String json = raw.trim();
        // 尝试直接解析；失败则用正则提取 JSON 数组
        if (!json.startsWith("[")) {
            Matcher m = JSON_ARRAY_PATTERN.matcher(json);
            if (!m.find()) {
                log.warn("[PlanAndExecute] 无法从规划输出中提取 JSON 数组: {}",
                        json.length() > 200 ? json.substring(0, 200) + "..." : json);
                return List.of();
            }
            json = m.group();
        }
        try {
            List<PlanItem> items = objectMapper.readValue(json, new TypeReference<>() {});
            List<AgentTask> tasks = new ArrayList<>();
            for (PlanItem item : items) {
                tasks.add(new AgentTask(
                        item.taskId(),
                        item.description(),
                        item.assignedRole(),
                        Map.of(),
                        item.dependencies() != null ? item.dependencies() : List.of(),
                        item.priority() != null ? item.priority() : AgentTask.DEFAULT_PRIORITY
                ));
            }
            // 校验依赖环
            if (AgentTask.detectCycle(tasks)) {
                log.warn("[PlanAndExecute] 规划结果存在依赖环，丢弃该计划, rootTaskId={}", rootTaskId);
                return List.of();
            }
            log.info("[PlanAndExecute] 规划完成, rootTaskId={}, 子任务数={}", rootTaskId, tasks.size());
            return tasks;
        } catch (Exception e) {
            log.warn("[PlanAndExecute] 解析规划 JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ──────────────────────── 执行 ────────────────────────

    /**
     * 执行一轮子任务：按依赖分波次，依赖满足的子任务并行/顺序执行。
     *
     * @param remaining 剩余子任务
     * @param completed 已完成结果（会被本方法更新）
     * @param rootId    根任务 ID（用于日志）
     * @return 本轮所有执行的子任务是否全部成功
     */
    private boolean executeWave(List<AgentTask> remaining, Map<String, AgentResult> completed, String rootId) {
        Set<String> completedIds = new HashSet<>(completed.keySet());
        List<AgentTask> ready = AgentTask.readyTasks(remaining, completedIds);
        if (ready.isEmpty()) {
            // 无可执行任务（可能因依赖失败）→ 标记为跳过
            return false;
        }

        boolean parallel = properties.getPlanAndExecute().isParallelSubtasks();
        boolean allSuccess = true;

        if (parallel && ready.size() > 1) {
            // 并行执行
            List<CompletableFuture<AgentResult>> futures = ready.stream()
                    .map(t -> CompletableFuture.supplyAsync(
                            () -> runSubtask(t, completed), executorService))
                    .toList();
            for (int i = 0; i < ready.size(); i++) {
                AgentResult r = futures.get(i).join();
                completed.put(ready.get(i).taskId(), r);
                allSuccess = allSuccess && r.success();
            }
        } else {
            // 顺序执行（按拓扑优先级）
            for (AgentTask t : ready) {
                AgentResult r = runSubtask(t, completed);
                completed.put(t.taskId(), r);
                allSuccess = allSuccess && r.success();
            }
        }

        log.info("[PlanAndExecute] 一轮执行完成, rootId={}, 本轮={}, 全部成功={}",
                rootId, ready.size(), allSuccess);
        return allSuccess;
    }

    /**
     * 执行单个子任务：将上游依赖结果注入输入参数后交给 ReAct 执行。
     */
    private AgentResult runSubtask(AgentTask task, Map<String, AgentResult> completed) {
        long start = System.currentTimeMillis();
        try {
            // 注入上游依赖结果作为输入
            Map<String, Object> enrichedInputs = new HashMap<>(task.inputs());
            for (String dep : task.dependencies()) {
                AgentResult depResult = completed.get(dep);
                if (depResult != null && depResult.output() != null) {
                    enrichedInputs.put("upstream:" + dep, depResult.output());
                }
            }
            AgentTask enriched = new AgentTask(
                    task.taskId(), task.description(), task.assignedRole(),
                    enrichedInputs, task.dependencies(), task.priority());

            AgentRole role = resolveRole(task.assignedRole());
            return reactExecutor.execute(enriched, role);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[PlanAndExecute] 子任务执行异常, taskId={}", task.taskId(), e);
            return AgentResult.failure(task.taskId(), "子任务执行异常: " + e.getMessage(), elapsed);
        }
    }

    // ──────────────────────── 辅助方法 ────────────────────────

    /**
     * 解析角色名称为 {@link AgentRole}，未知角色回退到 WRITER。
     */
    private AgentRole resolveRole(String roleName) {
        try {
            return AgentRole.fromName(roleName);
        } catch (IllegalArgumentException e) {
            log.debug("[PlanAndExecute] 未知角色 {}，回退到 writer", roleName);
            return AgentRole.WRITER;
        }
    }

    /**
     * 汇总已完成子任务结果，供重规划参考。
     */
    private Map<String, String> summarizeCompleted(Map<String, AgentResult> completed) {
        Map<String, String> summary = new HashMap<>();
        completed.forEach((id, r) -> {
            String text = r.output() != null ? r.output() : ("失败: " + r.errors());
            // 截断过长内容
            if (text.length() > 500) {
                text = text.substring(0, 500) + "...";
            }
            summary.put(id, text);
        });
        return summary;
    }

    /**
     * 合并旧计划与新计划：保留已完成子任务，用新计划替换未完成部分。
     */
    private List<AgentTask> mergePlan(List<AgentTask> oldPlan, List<AgentTask> newPlan, Set<String> completedIds) {
        if (newPlan.isEmpty()) {
            return oldPlan;
        }
        List<AgentTask> merged = new ArrayList<>();
        // 保留已完成的旧子任务（仅用于依赖判断，不会重复执行）
        oldPlan.stream().filter(t -> completedIds.contains(t.taskId())).forEach(merged::add);
        // 追加新计划中未完成的子任务
        newPlan.stream().filter(t -> !completedIds.contains(t.taskId())).forEach(merged::add);
        return merged;
    }

    // ──────────────────────── 规划结果 DTO ────────────────────────

    /**
     * 规划结果解析 DTO（对应 LLM 输出的 JSON 元素）。
     */
    private record PlanItem(
            String taskId,
            String description,
            String assignedRole,
            List<String> dependencies,
            Integer priority
    ) {
    }
}
