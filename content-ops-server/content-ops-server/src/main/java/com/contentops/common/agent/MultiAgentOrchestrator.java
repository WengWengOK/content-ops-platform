package com.contentops.common.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 多 Agent 协作编排器。
 *
 * <p>统一的协作调度入口，支持三种协作模式，每种模式均具备超时控制、失败重试与结果聚合能力，
 * 基于 {@link CompletableFuture} 实现异步并行执行。
 *
 * <h3>协作模式</h3>
 * <ul>
 *   <li><b>Sequential（顺序执行）</b>：按任务依赖（DAG）的拓扑顺序依次执行，
 *       上游结果自动注入下游任务输入，适用于有严格先后依赖的流水线</li>
 *   <li><b>Parallel（并行执行）</b>：所有任务同时并发执行，收集全部结果后合并，
 *       适用于互相独立的任务集合</li>
 *   <li><b>Hierarchical（层级协作）</b>：Supervisor Agent 将根任务分解为子任务，
 *       多个 Worker Agent 按依赖分波次并行执行，最后由 Supervisor 聚合结果</li>
 * </ul>
 *
 * <h3>统一调度</h3>
 * <p>通过 {@link CollaborationStrategy} 密封接口与 Java 21 模式匹配 switch 表达式
 * 实现策略分发，调用方可传入任意一种策略由 {@link #execute} 统一调度。
 *
 * <h3>容错机制</h3>
 * <ul>
 *   <li><b>超时控制</b>：每个任务执行附带 {@code task-timeout-seconds} 超时，超时降级返回部分结果</li>
 *   <li><b>失败重试</b>：任务失败后按 {@code max-retries} 重试，重试间隔 {@code retry-backoff-ms}</li>
 *   <li><b>异常降级</b>：执行异常被捕获并转换为 {@link AgentResult#failure}，不中断整体流程</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 顺序模式
 * List<AgentResult> seq = orchestrator.executeSequential(tasks, orchestrator.reactTaskRunner());
 *
 * // 并行模式
 * List<AgentResult> par = orchestrator.executeParallel(tasks, orchestrator.reactTaskRunner());
 *
 * // 层级模式
 * AgentResult hier = orchestrator.executeHierarchical(
 *     rootTask,
 *     t -> planAndExecuteAgent.plan(t, AgentRole.SUPERVISOR, Map.of()),
 *     orchestrator.reactTaskRunner(),
 *     (root, results) -> AgentResult.mergeAll(root.taskId(), results));
 *
 * // 统一调度
 * List<AgentResult> r = orchestrator.execute(
 *     new MultiAgentOrchestrator.ParallelStrategy(tasks),
 *     orchestrator.reactTaskRunner());
 * }</pre>
 *
 * @see AgentTask
 * @see AgentResult
 * @see AgentRole
 * @see ReActAgentExecutor
 * @see PlanAndExecuteAgent
 * @see MultiAgentProperties
 */
@Slf4j
@Component
public class MultiAgentOrchestrator {

    private final MultiAgentProperties properties;
    private final ExecutorService executorService;
    private final ReActAgentExecutor reactExecutor;
    private final PlanAndExecuteAgent planAndExecuteAgent;

    /**
     * 构造编排器。
     *
     * @param properties          多 Agent 配置
     * @param executorService     自定义线程池（{@code multiAgentExecutor}）
     * @param reactExecutor       ReAct 执行器（提供默认 TaskRunner）
     * @param planAndExecuteAgent Plan-and-Execute 执行器（提供规划能力）
     */
    public MultiAgentOrchestrator(MultiAgentProperties properties,
                                   @Qualifier(MultiAgentThreadPoolConfig.EXECUTOR_BEAN_NAME)
                                   ExecutorService executorService,
                                   ReActAgentExecutor reactExecutor,
                                   PlanAndExecuteAgent planAndExecuteAgent) {
        this.properties = properties;
        this.executorService = executorService;
        this.reactExecutor = reactExecutor;
        this.planAndExecuteAgent = planAndExecuteAgent;
        log.info("[Orchestrator] 多 Agent 编排器已初始化, timeout={}s, retries={}",
                properties.getTaskTimeoutSeconds(), properties.getMaxRetries());
    }

    // ════════════════════════ 协作模式：Sequential ════════════════════════

    /**
     * 顺序执行模式。
     *
     * <p>按任务依赖（DAG）的拓扑顺序依次执行，上游任务的输出会自动注入到下游任务的
     * 输入参数中（键为 {@code "upstream:{taskId}"}）。某个任务失败不会中断后续任务，
     * 但会在结果中记录错误。
     *
     * @param tasks  待执行任务集合（含依赖关系）
     * @param runner 单任务执行器
     * @return 与拓扑顺序一致的结果列表
     */
    public List<AgentResult> executeSequential(List<AgentTask> tasks, TaskRunner runner) {
        log.info("[Orchestrator][Sequential] 开始, 任务数={}", tasks.size());
        List<AgentTask> ordered = AgentTask.topologicalSort(tasks);
        List<AgentResult> results = new ArrayList<>(ordered.size());
        Map<String, AgentResult> completed = new HashMap<>();

        for (AgentTask task : ordered) {
            AgentTask enriched = enrichWithUpstream(task, completed);
            AgentResult result = scheduleTask(enriched, runner).join();
            results.add(result);
            completed.put(task.taskId(), result);
            log.info("[Orchestrator][Sequential] taskId={}, success={}", task.taskId(), result.success());
        }
        return results;
    }

    // ════════════════════════ 协作模式：Parallel ════════════════════════

    /**
     * 并行执行模式。
     *
     * <p>所有任务同时并发执行，互不阻塞，收集全部结果后返回。适用于互相独立的任务集合。
     * 若需按依赖并行，请使用 {@link #executeHierarchical} 或 {@link PlanAndExecuteAgent}。
     *
     * @param tasks  待执行任务集合（应互相独立）
     * @param runner 单任务执行器
     * @return 与输入任务顺序一致的结果列表
     */
    public List<AgentResult> executeParallel(List<AgentTask> tasks, TaskRunner runner) {
        log.info("[Orchestrator][Parallel] 开始, 任务数={}", tasks.size());
        List<CompletableFuture<AgentResult>> futures = tasks.stream()
                .map(t -> scheduleTask(t, runner))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        List<AgentResult> results = futures.stream().map(CompletableFuture::join).toList();
        long successCount = results.stream().filter(AgentResult::success).count();
        log.info("[Orchestrator][Parallel] 完成, 成功={}/{}", successCount, results.size());
        return results;
    }

    /**
     * 并行执行并合并为单个结果。
     *
     * @param tasks     待执行任务集合
     * @param runner    单任务执行器
     * @param mergedId  合并结果的任务 ID
     * @return 合并后的聚合结果
     */
    public AgentResult executeParallelAndMerge(List<AgentTask> tasks, TaskRunner runner, String mergedId) {
        return AgentResult.mergeAll(mergedId, executeParallel(tasks, runner));
    }

    // ════════════════════════ 协作模式：Hierarchical ════════════════════════

    /**
     * 层级协作模式。
     *
     * <p>Supervisor Agent（由 {@code planner} 函数模拟）将根任务分解为子任务列表，
     * 多个 Worker Agent 按依赖关系分波次并行执行（每波内无依赖的子任务并行，
     * 波次间按 DAG 顺序推进），最后由 {@code aggregator} 聚合所有子任务结果。
     *
     * @param rootTask     根任务
     * @param planner      任务分解函数（Supervisor）：根任务 → 子任务列表
     * @param workerRunner Worker 任务执行器
     * @param aggregator   结果聚合函数：根任务 + 子任务结果列表 → 聚合结果
     * @return 聚合后的根任务结果
     */
    public AgentResult executeHierarchical(AgentTask rootTask,
                                            Function<AgentTask, List<AgentTask>> planner,
                                            TaskRunner workerRunner,
                                            BiFunction<AgentTask, List<AgentResult>, AgentResult> aggregator) {
        long start = System.currentTimeMillis();
        log.info("[Orchestrator][Hierarchical] 开始, rootTaskId={}", rootTask.taskId());

        // Supervisor 分解任务
        List<AgentTask> subtasks = planner.apply(rootTask);
        if (subtasks == null || subtasks.isEmpty()) {
            log.warn("[Orchestrator][Hierarchical] 主管未分解出子任务, rootTaskId={}", rootTask.taskId());
            return AgentResult.failure(rootTask.taskId(), "Supervisor 未分解出子任务");
        }
        log.info("[Orchestrator][Hierarchical] 主管分解出 {} 个子任务", subtasks.size());

        // 按依赖分波次并行执行
        Map<String, AgentResult> completed = new ConcurrentHashMap<>();
        List<AgentTask> remaining = new ArrayList<>(subtasks);

        while (!remaining.isEmpty()) {
            Set<String> doneIds = completed.keySet();
            List<AgentTask> ready = AgentTask.readyTasks(remaining, doneIds);

            if (ready.isEmpty()) {
                log.warn("[Orchestrator][Hierarchical] 无可执行子任务（依赖死锁或全部失败），终止, rootTaskId={}",
                        rootTask.taskId());
                break;
            }

            // 并行执行当前波次
            List<CompletableFuture<AgentResult>> waveFutures = ready.stream()
                    .map(t -> scheduleTask(enrichWithUpstream(t, completed), workerRunner))
                    .toList();
            CompletableFuture.allOf(waveFutures.toArray(CompletableFuture[]::new)).join();

            for (int i = 0; i < ready.size(); i++) {
                AgentResult r = waveFutures.get(i).join();
                completed.put(ready.get(i).taskId(), r);
            }
            remaining = remaining.stream()
                    .filter(t -> !completed.containsKey(t.taskId()))
                    .toList();
        }

        // Supervisor 聚合结果
        List<AgentResult> allResults = new ArrayList<>(completed.values());
        AgentResult aggregated = aggregator.apply(rootTask, allResults);
        long elapsed = System.currentTimeMillis() - start;
        log.info("[Orchestrator][Hierarchical] 完成, rootTaskId={}, 子任务={}, 成功={}, 耗时={}ms",
                rootTask.taskId(), allResults.size(),
                allResults.stream().filter(AgentResult::success).count(), elapsed);

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

    // ════════════════════════ 统一策略调度 ════════════════════════

    /**
     * 统一调度入口：根据 {@link CollaborationStrategy} 的具体子类型分发到对应模式。
     *
     * <p>使用 Java 21 密封接口与模式匹配 switch 表达式实现类型安全分发。
     *
     * @param strategy 协作策略（顺序 / 并行 / 层级）
     * @param runner   单任务执行器（层级模式下作为 Worker 执行器）
     * @return 结果列表（层级模式返回单元素列表）
     */
    public List<AgentResult> execute(CollaborationStrategy strategy, TaskRunner runner) {
        return switch (strategy) {
            case SequentialStrategy s -> executeSequential(s.tasks(), runner);
            case ParallelStrategy p -> executeParallel(p.tasks(), runner);
            case HierarchicalStrategy h -> {
                AgentResult r = executeHierarchical(h.rootTask(), h.planner(), runner, h.aggregator());
                yield List.of(r);
            }
        };
    }

    // ════════════════════════ 默认 TaskRunner ════════════════════════

    /**
     * 返回基于 {@link ReActAgentExecutor} 的默认任务执行器。
     *
     * <p>执行时根据 {@link AgentTask#assignedRole()} 解析角色，未知角色回退到
     * {@link AgentRole#WRITER}。
     *
     * @return ReAct 任务执行器
     */
    public TaskRunner reactTaskRunner() {
        return task -> reactExecutor.execute(task, resolveRole(task.assignedRole()));
    }

    /**
     * 返回基于 {@link PlanAndExecuteAgent} 的任务执行器。
     *
     * <p>每个任务都会经过「规划→执行→聚合」完整流程，适用于复杂任务。
     *
     * @param plannerRole 规划角色
     * @return Plan-and-Execute 任务执行器
     */
    public TaskRunner planAndExecuteTaskRunner(AgentRole plannerRole) {
        return task -> planAndExecuteAgent.execute(task, plannerRole);
    }

    // ════════════════════════ 内部调度与容错 ════════════════════════

    /**
     * 调度单个任务执行：异步提交 + 超时控制 + 异常降级。
     *
     * @param task   待执行任务
     * @param runner 单任务执行器
     * @return 带超时与降级的 CompletableFuture
     */
    private CompletableFuture<AgentResult> scheduleTask(AgentTask task, TaskRunner runner) {
        long timeoutSeconds = properties.getTaskTimeoutSeconds();
        return CompletableFuture
                .supplyAsync(() -> executeWithRetry(task, runner), executorService)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> degrade(task, ex));
    }

    /**
     * 带重试的同步执行循环（在调度线程内运行）。
     *
     * <p>失败时按 {@code max-retries} 重试，重试间隔 {@code retry-backoff-ms}。
     * 单次执行异常被捕获并转换为失败结果，不抛出。
     */
    private AgentResult executeWithRetry(AgentTask task, TaskRunner runner) {
        int maxRetries = properties.getMaxRetries();
        AgentResult lastResult = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                lastResult = runner.execute(task);
                if (lastResult != null && lastResult.success()) {
                    if (attempt > 0) {
                        log.info("[Orchestrator] 任务重试成功, taskId={}, attempts={}", task.taskId(), attempt + 1);
                    }
                    return lastResult;
                }
                log.warn("[Orchestrator] 任务失败, taskId={}, attempt={}/{}, errors={}",
                        task.taskId(), attempt + 1, maxRetries + 1,
                        lastResult != null ? lastResult.errors() : "null");
            } catch (Exception e) {
                lastResult = AgentResult.failure(task.taskId(), "执行异常: " + e.getMessage());
                log.error("[Orchestrator] 任务执行异常, taskId={}, attempt={}",
                        task.taskId(), attempt + 1, e);
            }
            if (attempt < maxRetries) {
                sleepBackoff();
            }
        }
        return lastResult != null ? lastResult
                : AgentResult.failure(task.taskId(), "未知失败");
    }

    /**
     * 异常降级：将超时或执行异常转换为对应的失败/超时结果。
     */
    private AgentResult degrade(AgentTask task, Throwable ex) {
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                ? ex.getCause() : ex;
        if (cause instanceof TimeoutException) {
            log.warn("[Orchestrator] 任务超时降级, taskId={}, timeout={}s",
                    task.taskId(), properties.getTaskTimeoutSeconds());
            return AgentResult.timeout(task.taskId(), null, properties.getTaskTimeoutSeconds() * 1000L);
        }
        log.error("[Orchestrator] 任务异常降级, taskId={}", task.taskId(), cause);
        return AgentResult.failure(task.taskId(), "异常降级: " + cause.getMessage());
    }

    /**
     * 重试退避等待。
     */
    private void sleepBackoff() {
        try {
            Thread.sleep(properties.getRetryBackoffMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 将上游依赖任务的输出注入到当前任务输入参数。
     *
     * @param task      当前任务
     * @param completed 已完成任务结果
     * @return 注入上游结果后的新任务
     */
    private AgentTask enrichWithUpstream(AgentTask task, Map<String, AgentResult> completed) {
        if (!task.hasDependencies()) {
            return task;
        }
        Map<String, Object> enriched = new HashMap<>(task.inputs());
        for (String dep : task.dependencies()) {
            AgentResult upstream = completed.get(dep);
            if (upstream != null && upstream.output() != null) {
                enriched.put("upstream:" + dep, upstream.output());
            }
        }
        return new AgentTask(task.taskId(), task.description(), task.assignedRole(),
                enriched, task.dependencies(), task.priority());
    }

    /**
     * 根据角色名解析 {@link AgentRole}，未知角色回退到 {@link AgentRole#WRITER}。
     */
    private AgentRole resolveRole(String roleName) {
        try {
            return AgentRole.fromName(roleName);
        } catch (IllegalArgumentException e) {
            log.debug("[Orchestrator] 未知角色 {}，回退到 writer", roleName);
            return AgentRole.WRITER;
        }
    }

    // ════════════════════════ 密封策略接口 ════════════════════════

    /**
     * 协作策略密封接口，三种具体策略通过 Java 21 密封类型约束。
     */
    public sealed interface CollaborationStrategy
            permits SequentialStrategy, ParallelStrategy, HierarchicalStrategy {
    }

    /**
     * 顺序执行策略。
     *
     * @param tasks 任务列表（含依赖关系）
     */
    public record SequentialStrategy(List<AgentTask> tasks) implements CollaborationStrategy {
    }

    /**
     * 并行执行策略。
     *
     * @param tasks 任务列表（应互相独立）
     */
    public record ParallelStrategy(List<AgentTask> tasks) implements CollaborationStrategy {
    }

    /**
     * 层级协作策略。
     *
     * @param rootTask  根任务
     * @param planner   任务分解函数（Supervisor）
     * @param aggregator 结果聚合函数（Supervisor）
     */
    public record HierarchicalStrategy(
            AgentTask rootTask,
            Function<AgentTask, List<AgentTask>> planner,
            BiFunction<AgentTask, List<AgentResult>, AgentResult> aggregator
    ) implements CollaborationStrategy {
    }

    // ════════════════════════ 任务执行器函数式接口 ════════════════════════

    /**
     * 单任务执行器函数式接口。
     *
     * <p>由调用方提供具体的 Agent 执行逻辑（如 ReAct、Plan-and-Execute 或自定义）。
     */
    @FunctionalInterface
    public interface TaskRunner {
        /**
         * 执行单个任务并返回结果。
         *
         * @param task 待执行任务
         * @return 执行结果
         */
        AgentResult execute(AgentTask task);
    }
}
