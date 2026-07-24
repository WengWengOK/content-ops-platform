package com.contentops.orchestrator.service;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.graph.LangGraphWorkflowEngine;
import com.contentops.orchestrator.workflow.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

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

    /**
     * 判断是否使用 LangGraph4j 引擎。
     */
    private boolean useLangGraph() {
        return "langgraph".equalsIgnoreCase(engineType);
    }

    /**
     * Start a new workflow by executing the first stage.
     *
     * <p>根据配置选择执行引擎。
     */
    public void startWorkflow(TaskContext context) {
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        if (useLangGraph()) {
            log.info("[Workflow:{}] Using LangGraph4j engine", context.getWorkflowId());
            langGraphEngine.executeWorkflow(context);
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
        } else {
            log.info("[Workflow:{}] Using legacy engine", context.getWorkflowId());
            orchestrator.executeStage(context);
        }
    }

    /**
     * Get current workflow status from Redis.
     */
    public TaskContext getWorkflowStatus(String workflowId) {
        return stateManager.loadWorkflowState(workflowId).orElse(null);
    }

    /**
     * Approve current stage and proceed to the next.
     * This is called after human review.
     *
     * <p><b>双引擎支持：</b>
     * <ul>
     *   <li>LangGraph 模式：调用 {@link LangGraphWorkflowEngine#resumeWorkflow}</li>
     *   <li>Legacy 模式：保留 A计划的循环边界检查逻辑</li>
     * </ul>
     */
    public void approveAndProceed(String workflowId, Map<String, Object> feedback) {
        TaskContext context = stateManager.loadWorkflowState(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowId));

        if (!TaskStatus.AWAITING_HUMAN.name().equals(context.getStatus())) {
            throw new RuntimeException("Workflow is not awaiting human review. Current status: "
                    + context.getStatus());
        }

        if (useLangGraph()) {
            log.info("[Workflow:{}] Resuming via LangGraph engine", workflowId);
            langGraphEngine.resumeWorkflow(context, feedback);
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
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
                    workflowId, context.getCycleCount());
            return;
        }

        context.setCurrentStage(nextStage.getCode());
        context.setStatus(TaskStatus.PENDING.name());

        log.info("[Workflow:{}] Human approved. Advancing {} → {}",
                workflowId, currentStage.getCode(), nextStage.getCode());

        orchestrator.executeStage(context);
    }

    /**
     * Retry the current stage after a failure.
     */
    public void retryStage(String workflowId) {
        TaskContext context = stateManager.loadWorkflowState(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowId));

        context.setStatus(TaskStatus.PENDING.name());
        context.setErrorMessage(null);

        log.info("[Workflow:{}] Retrying stage: {}", workflowId, context.getCurrentStage());

        if (useLangGraph()) {
            langGraphEngine.executeWorkflow(context);
        } else {
            orchestrator.executeStage(context);
        }
        stateManager.saveWorkflowState(context.getWorkflowId(), context);
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
     * @param workflowId 工作流 ID
     * @param feedback   可选的反馈/修改（如修改后的大纲、选择的风格方向）
     */
    public void confirmSubStage(String workflowId, Map<String, Object> feedback) {
        TaskContext context = stateManager.loadWorkflowState(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowId));

        if (!TaskStatus.AWAITING_HUMAN.name().equals(context.getStatus())) {
            throw new RuntimeException("Workflow is not awaiting confirmation. Current status: "
                    + context.getStatus());
        }

        if (useLangGraph()) {
            log.info("[Workflow:{}] Confirming via LangGraph resume", workflowId);
            langGraphEngine.resumeWorkflow(context, feedback);
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
            return;
        }

        // Legacy 模式
        if (context.getCurrentSubStage() == null || context.getCurrentSubStage().isBlank()) {
            throw new RuntimeException("No current sub-stage to confirm. This may be a regular stage approval.");
        }

        log.info("[Workflow:{}] Confirming sub-stage: {}", workflowId, context.getCurrentSubStage());
        orchestrator.confirmAndProceedSubStage(context, feedback);
    }
}
