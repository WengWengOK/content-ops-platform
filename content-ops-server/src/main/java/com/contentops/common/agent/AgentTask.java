package com.contentops.common.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 任务封装（多 Agent 协作框架）。
 *
 * <p>采用 Java 21 {@code record} 封装一个可被 Agent 执行的任务单元，包含任务标识、
 * 描述、分配角色、输入参数、依赖任务列表与优先级。任务之间通过 {@link #dependencies}
 * 建立依赖关系，形成有向无环图（DAG），支持编排器按拓扑顺序调度执行。
 *
 * <h3>任务依赖（DAG）</h3>
 * <p>{@code dependencies} 中存放的是前置任务的 {@code taskId}。一个任务只有在其所有
 * 依赖任务都成功完成后才会被调度执行。{@link #topologicalSort(Collection)} 方法
 * 对任务集合进行拓扑排序，{@link #detectCycle(Collection)} 用于检测依赖环。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Map<String, Object> inputs = Map.of("topic", "AI 趋势");
 * AgentTask research = AgentTask.of("t-1", "调研 AI 趋势", "researcher", inputs, 1);
 * AgentTask write = AgentTask.of("t-2", "撰写文章", "writer", inputs, 1, List.of("t-1"));
 * List<AgentTask> ordered = AgentTask.topologicalSort(List.of(research, write));
 * }</pre>
 *
 * @param taskId        任务唯一标识
 * @param description   任务描述（自然语言，供 LLM 理解任务目标）
 * @param assignedRole  分配的角色名称（对应 {@link AgentRole#roleName()}）
 * @param inputs        任务输入参数（键值对，可包含上游任务的产出）
 * @param dependencies  依赖的前置任务 ID 列表（构成 DAG 边）
 * @param priority      优先级（数值越大优先级越高，调度时优先执行）
 *
 * @see AgentRole
 * @see AgentResult
 */
public record AgentTask(
        String taskId,
        String description,
        String assignedRole,
        Map<String, Object> inputs,
        List<String> dependencies,
        int priority
) {

    /** 默认优先级。 */
    public static final int DEFAULT_PRIORITY = 5;

    /**
     * 紧凑构造器：校验必填字段并规范化集合类型为不可变副本。
     *
     * @throws IllegalArgumentException 当 taskId 或 description 为空时
     */
    public AgentTask {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    // ──────────────────────── 工厂方法 ────────────────────────

    /**
     * 创建一个无依赖、默认优先级的任务。
     *
     * @param taskId       任务 ID
     * @param description  任务描述
     * @param assignedRole 分配角色
     * @param inputs       输入参数
     * @param priority     优先级
     * @return 任务实例
     */
    public static AgentTask of(String taskId, String description, String assignedRole,
                                Map<String, Object> inputs, int priority) {
        return new AgentTask(taskId, description, assignedRole, inputs, List.of(), priority);
    }

    /**
     * 创建一个带依赖的任务。
     *
     * @param taskId       任务 ID
     * @param description  任务描述
     * @param assignedRole 分配角色
     * @param inputs       输入参数
     * @param priority     优先级
     * @param dependencies 依赖的前置任务 ID 列表
     * @return 任务实例
     */
    public static AgentTask of(String taskId, String description, String assignedRole,
                                Map<String, Object> inputs, int priority,
                                List<String> dependencies) {
        return new AgentTask(taskId, description, assignedRole, inputs, dependencies, priority);
    }

    /**
     * 创建一个无依赖、默认优先级、空输入的简单任务。
     *
     * @param taskId       任务 ID
     * @param description  任务描述
     * @param assignedRole 分配角色
     * @return 任务实例
     */
    public static AgentTask simple(String taskId, String description, String assignedRole) {
        return new AgentTask(taskId, description, assignedRole, Map.of(), List.of(), DEFAULT_PRIORITY);
    }

    // ──────────────────────── DAG 工具方法 ────────────────────────

    /**
     * 判断该任务是否有依赖。
     *
     * @return true 表示存在前置依赖
     */
    public boolean hasDependencies() {
        return dependencies != null && !dependencies.isEmpty();
    }

    /**
     * 判断该任务的依赖是否全部包含在给定已完成任务集合中。
     *
     * @param completedTaskIds 已成功完成的任务 ID 集合
     * @return true 表示所有依赖均已完成，可以调度执行
     */
    public boolean dependenciesSatisfied(Set<String> completedTaskIds) {
        Set<String> completed = completedTaskIds == null ? Set.of() : completedTaskIds;
        return dependencies.stream().allMatch(completed::contains);
    }

    /**
     * 对任务集合进行拓扑排序（Kahn 算法）。
     *
     * <p>同一拓扑层级内按优先级降序排列，保证高优先级任务优先被调度。
     *
     * @param tasks 待排序的任务集合
     * @return 拓扑有序的任务列表
     * @throws IllegalStateException 当检测到依赖环时
     */
    public static List<AgentTask> topologicalSort(Collection<AgentTask> tasks) {
        Objects.requireNonNull(tasks, "tasks 不能为 null");
        Map<String, AgentTask> byId = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>(); // taskId → 依赖它的任务集合
        Map<String, Integer> inDegree = new HashMap<>();

        for (AgentTask t : tasks) {
            byId.put(t.taskId(), t);
            dependents.computeIfAbsent(t.taskId(), k -> new HashSet<>());
            inDegree.put(t.taskId(), t.dependencies().size());
        }

        // 仅保留存在于集合中的依赖，忽略外部依赖
        for (AgentTask t : tasks) {
            for (String dep : t.dependencies()) {
                if (byId.containsKey(dep)) {
                    dependents.get(dep).add(t.taskId());
                } else {
                    // 外部依赖视为已完成，入度减一
                    inDegree.merge(t.taskId(), -1, Integer::sum);
                }
            }
        }

        // 入度为 0 的任务作为起点，按优先级降序
        List<AgentTask> queue = new ArrayList<>(byId.values().stream()
                .filter(t -> inDegree.get(t.taskId()) <= 0)
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .toList());

        List<AgentTask> sorted = new ArrayList<>(byId.size());
        while (!queue.isEmpty()) {
            AgentTask current = queue.remove(0);
            sorted.add(current);
            for (String dependent : dependents.get(current.taskId())) {
                int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(byId.get(dependent));
                    queue.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
                }
            }
        }

        if (sorted.size() != byId.size()) {
            throw new IllegalStateException("检测到任务依赖环，无法完成拓扑排序");
        }
        return sorted;
    }

    /**
     * 检测任务集合中是否存在依赖环。
     *
     * @param tasks 任务集合
     * @return true 表示存在依赖环（DAG 非法）
     */
    public static boolean detectCycle(Collection<AgentTask> tasks) {
        try {
            topologicalSort(tasks);
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /**
     * 获取一组任务中可立即执行的任务（入度为 0 或依赖均已完成）。
     *
     * @param tasks            任务集合
     * @param completedTaskIds 已完成任务 ID 集合
     * @return 可执行任务列表（按优先级降序）
     */
    public static List<AgentTask> readyTasks(Collection<AgentTask> tasks, Set<String> completedTaskIds) {
        return tasks.stream()
                .filter(t -> !completedTaskIds.contains(t.taskId()))
                .filter(t -> t.dependenciesSatisfied(completedTaskIds))
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .toList();
    }
}
