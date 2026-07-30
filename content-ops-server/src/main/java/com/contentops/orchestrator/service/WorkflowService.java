package com.contentops.orchestrator.service;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.graph.LangGraphWorkflowEngine;
import com.contentops.orchestrator.workflow.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 工作流服务 — 双引擎切换入口。
 *
 * <p><b>B计划：</b>通过配置 {@code contentops.orchestrator.engine} 控制引擎选择：
 * <ul>
 *   <li>{@code legacy}（默认）：使用原 {@link PipelineOrchestrator}，A计划修复后的循环控制</li>
 *   <li>{@code langgraph}：使用 {@link LangGraphWorkflowEngine}，LangGraph4j 原生图编排</li>
 * </ul>
 *
 * <p>灰度切换：通过 Nacos 配置中心动态修改 engine 值即可切换，无需重启（配合 @RefreshScope）。
 * 回滚：改回 legacy 即可，原引擎代码完全保留。
 *
 * <p><b>P0 修复：</b>
 * <ul>
 *   <li>使用自定义有界线程池替代 ForkJoinPool.commonPool()，防止 I/O 任务耗尽默认线程池</li>
 *   <li>approveAndProceed / confirmSubStage 使用分布式锁保护，防止并发状态修改</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowStateManager stateManager;
    private final PipelineOrchestrator orchestrator;       // 原引擎（A计划）
    private final LangGraphWorkflowEngine langGraphEngine;  // 新引擎（B计划）

    @Value("${contentops.orchestrator.engine:legacy}")
    private String engineType;

    /** 自定义线程池：用于异步执行工作流管线 */
    private ExecutorService workflowExecutor;

    @PostConstruct
    void initExecutor() {
        int corePoolSize = 4;
        int maxPoolSize = 16;
        int queueCapacity = 50;
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "workflow-exec-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        this.workflowExecutor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时由调用线程执行，实现背压
        );
        log.info("Workflow executor initialized: core={}, max={}, queue={}",
                corePoolSize, maxPoolSize, queueCapacity);
    }

    @PreDestroy
    void shutdownExecutor() {
        if (workflowExecutor != null) {
            workflowExecutor.shutdown();
            try {
                if (!workflowExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    workflowExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                workflowExecutor.shutdownNow();
            }
            log.info("Workflow executor shutdown complete");
        }
    }

    /**
     * 判断是否使用 LangGraph4j 引擎。
     */
    private boolean useLangGraph() {
        return "langgraph".equalsIgnoreCase(engineType);
    }

    /**
     * Start a new workflow by executing the first stage.
     *
     * <p>根据配置选择执行引擎。工作流执行为异步——先保存状态并立即返回，
     * 管线在后台线程中执行。若 Agent 服务不可用，工作流状态会被标记为 FAILED。
     *
     * <p><b>P0 修复：</b>使用自定义有界线程池替代 ForkJoinPool.commonPool()，
     * 避免 I/O 密集型任务耗尽 JVM 默认线程池。
     */
    public void startWorkflow(TaskContext context) {
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        // 使用自定义线程池异步执行管线，避免阻塞 HTTP 请求
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                if (useLangGraph()) {
                    log.info("[Workflow:{}] Using LangGraph4j engine", context.getWorkflowId());
                    langGraphEngine.executeWorkflow(context);
                } else {
                    log.info("[Workflow:{}] Using legacy engine", context.getWorkflowId());
                    orchestrator.executeStage(context);
                }
                stateManager.saveWorkflowState(context.getWorkflowId(), context);
            } catch (Exception e) {
                log.error("[Workflow:{}] Pipeline execution failed: {}", context.getWorkflowId(), e.getMessage(), e);
                context.setStatus(TaskStatus.FAILED.name());
                context.setErrorMessage(e.getMessage());
                stateManager.saveWorkflowState(context.getWorkflowId(), context);
            }
        }, workflowExecutor);
    }

    /**
     * Get current workflow status from Redis.
     */
    public TaskContext getWorkflowStatus(String workflowId) {
        return stateManager.loadWorkflowState(workflowId).orElse(null);
    }

    /**
     * List all workflows from Redis, sorted by creation time (newest first).
     *
     * @return list of all TaskContext objects, newest first
     */
    public List<TaskContext> listAllWorkflows() {
        List<TaskContext> all = stateManager.listAllWorkflows();
        return all.stream()
                .sorted(Comparator.comparing(
                        TaskContext::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .collect(Collectors.toList());
    }

    /**
     * Approve current stage and proceed to the next.
     * This is called after human review.
     *
     * <p><b>P0 修复：</b>使用分布式锁保护状态修改，防止并发 approve 导致状态不一致。
     *
     * <p><b>双引擎支持：</b>
     * <ul>
     *   <li>LangGraph 模式：调用 {@link LangGraphWorkflowEngine#resumeWorkflow}</li>
     *   <li>Legacy 模式：保留 A计划的循环边界检查逻辑</li>
     * </ul>
     */
    public void approveAndProceed(String workflowId, Map<String, Object> feedback) {
        stateManager.executeWithLock(workflowId, wfId -> {
            TaskContext context = stateManager.loadWorkflowState(wfId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, wfId));

            if (!TaskStatus.AWAITING_HUMAN.name().equals(context.getStatus())) {
                throw new BusinessException(ErrorCode.WORKFLOW_NOT_AWAITING_REVIEW, context.getStatus());
            }

            if (useLangGraph()) {
                log.info("[Workflow:{}] Resuming via LangGraph engine", wfId);
                langGraphEngine.resumeWorkflow(context, feedback);
                stateManager.saveWorkflowState(wfId, context);
                return;
            }

            // Legacy 模式：保留 A计划的循环边界检查逻辑
            if (feedback != null && !feedback.isEmpty()) {
                if (context.getInputs() == null) {
                    context.setInputs(new java.util.HashMap<>());
                }
                context.getInputs().put("humanFeedback", feedback);
            }

            com.contentops.common.enums.AgentStage currentStage =
                    com.contentops.common.enums.AgentStage.fromCode(context.getCurrentStage());
            com.contentops.common.enums.AgentStage nextStage = currentStage.next();

            if (orchestrator.checkAndHandleCycleBoundary(context, currentStage, nextStage)) {
                log.info("[Workflow:{}] Cycle boundary handled in approveAndProceed. cycle={}",
                        wfId, context.getCycleCount());
                return;
            }

            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());

            log.info("[Workflow:{}] Human approved. Advancing {} → {}",
                    wfId, currentStage.getCode(), nextStage.getCode());

            orchestrator.executeStage(context);
        });
    }

    /**
     * Retry the current stage after a failure.
     *
     * <p><b>P0 修复：</b>使用分布式锁保护状态修改。
     */
    public void retryStage(String workflowId) {
        stateManager.executeWithLock(workflowId, wfId -> {
            TaskContext context = stateManager.loadWorkflowState(wfId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, wfId));

            context.setStatus(TaskStatus.PENDING.name());
            context.setErrorMessage(null);

            log.info("[Workflow:{}] Retrying stage: {}", wfId, context.getCurrentStage());

            if (useLangGraph()) {
                langGraphEngine.executeWorkflow(context);
            } else {
                orchestrator.executeStage(context);
            }
            stateManager.saveWorkflowState(wfId, context);
        });
    }

    /**
     * 确认当前子阶段并推进到下一个子阶段（渐进式生成）。
     *
     * <p>当工作流处于 AWAITING_HUMAN 状态且有 currentSubStage 时，
     * 用户确认子阶段一（大纲/风格方向）的输出后，调用此方法推进到子阶段二。
     *
     * <p><b>LangGraph 模式</b>下，子阶段确认通过 {@link LangGraphWorkflowEngine#resumeWorkflow} 处理，
     * 因为 LangGraph4j 的 interruptBefore 机制会自动在 content/image 节点前暂停。
     *
     * <p><b>P0 修复：</b>使用分布式锁保护状态修改。
     *
     * @param workflowId 工作流 ID
     * @param feedback   可选的反馈/修改（如修改后的大纲、选择的风格方向）
     */
    public void confirmSubStage(String workflowId, Map<String, Object> feedback) {
        stateManager.executeWithLock(workflowId, wfId -> {
            TaskContext context = stateManager.loadWorkflowState(wfId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, wfId));

            if (!TaskStatus.AWAITING_HUMAN.name().equals(context.getStatus())) {
                throw new BusinessException(ErrorCode.WORKFLOW_NOT_AWAITING_CONFIRMATION, context.getStatus());
            }

            if (useLangGraph()) {
                log.info("[Workflow:{}] Confirming via LangGraph resume", wfId);
                langGraphEngine.resumeWorkflow(context, feedback);
                stateManager.saveWorkflowState(wfId, context);
                return;
            }

            // Legacy 模式
            if (context.getCurrentSubStage() == null || context.getCurrentSubStage().isBlank()) {
                throw new BusinessException(ErrorCode.NO_SUBSTAGE_TO_CONFIRM);
            }

            log.info("[Workflow:{}] Confirming sub-stage: {}", wfId, context.getCurrentSubStage());
            orchestrator.confirmAndProceedSubStage(context, feedback);
        });
    }
}
