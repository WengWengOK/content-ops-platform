package com.contentops.orchestrator.workflow;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.event.StageTransitionEvent;
import com.contentops.common.util.WorkflowStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 循环控制器 — 负责 OPTIMIZATION → TOPIC_PLANNING 的循环边界处理。
 *
 * <p>从 {@link PipelineOrchestrator} 拆分而来（P2-13），保持原有逻辑不变。
 *
 * <p>A计划循环控制：当 OPTIMIZATION 阶段完成时，判断是否进入新一轮循环：
 * <ul>
 *   <li>有剩余轮次：快照当前轮次产物 → 递增 cycleCount → 注入优化反馈 → 开始新一轮</li>
 *   <li>无剩余轮次：标记工作流 COMPLETED</li>
 * </ul>
 */
@Slf4j
@Component
public class CycleHandler {

    private final WorkflowStateManager stateManager;
    private final StageExecutor stageExecutor;

    public CycleHandler(WorkflowStateManager stateManager,
                        StageExecutor stageExecutor) {
        this.stateManager = stateManager;
        this.stageExecutor = stageExecutor;
    }

    /**
     * 判断是否为循环边界（OPTIMIZATION → TOPIC_PLANNING）。
     */
    boolean isCycleBoundary(AgentStage currentStage, AgentStage nextStage) {
        return currentStage == AgentStage.OPTIMIZATION
                && nextStage == AgentStage.TOPIC_PLANNING;
    }

    /**
     * 检查并处理循环边界（公开方法，供 WorkflowService.approveAndProceed 调用）。
     *
     * <p>当人工审批 OPTIMIZATION 阶段后，WorkflowService 调用此方法判断是否为循环边界：
     * <ul>
     *   <li>如果是循环边界且有剩余轮次：快照 + 开始新一轮 + 自动执行</li>
     *   <li>如果是循环边界但无剩余轮次：标记 COMPLETED</li>
     *   <li>如果不是循环边界：返回 false，调用方正常推进</li>
     * </ul>
     *
     * @return true 如果已处理循环边界（调用方不应继续正常推进），false 如果不是循环边界
     */
    @SuppressWarnings("unchecked")
    public boolean checkAndHandleCycleBoundary(TaskContext context,
                                                 AgentStage currentStage,
                                                 AgentStage nextStage) {
        if (!isCycleBoundary(currentStage, nextStage)) {
            return false;
        }

        // 从 accumulatedArtifacts 提取 OPTIMIZATION 阶段的产物作为响应数据
        Map<String, Object> optimizationData = null;
        if (context.getAccumulatedArtifacts() != null) {
            Object stored = context.getAccumulatedArtifacts().get(AgentStage.OPTIMIZATION.getCode());
            if (stored instanceof Map) {
                optimizationData = (Map<String, Object>) stored;
            }
        }

        handleCycleBoundary(context, currentStage, optimizationData);
        return true;
    }

    /**
     * 处理循环边界：OPTIMIZATION 完成后，决定是否开始新一轮循环。
     *
     * <p>逻辑：
     * <ol>
     *   <li>提取 OptimizeAgent 的优化反馈</li>
     *   <li>检查 {@code hasRemainingCycles()}：cycleCount < maxCycles</li>
     *   <li>若有剩余轮次：快照当前轮次产物 → 递增 cycleCount → 重置阶段 → 注入反馈 → 开始新一轮</li>
     *   <li>若无剩余轮次：标记工作流 COMPLETED</li>
     * </ol>
     */
    void handleCycleBoundary(TaskContext context, AgentStage stage,
                              Map<String, Object> responseData) {
        // 提取优化反馈（A3: 反馈注入）
        extractOptimizationFeedback(context, responseData);

        if (context.hasRemainingCycles()) {
            // 快照当前轮次产物 + 递增 cycleCount + 重置阶段 + 注入反馈
            context.startNewCycle();
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Cycle boundary: cycle {} → {}. Starting new cycle.",
                    context.getWorkflowId(), context.getCycleCount() - 1, context.getCycleCount());

            stageExecutor.publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(),
                    context.getCurrentStage(), responseData));

            // 开始新一轮的 TOPIC_PLANNING
            stageExecutor.executeStage(context);
        } else {
            // 达到最大循环次数，工作流完成
            context.setStatus(TaskStatus.COMPLETED.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] All {} cycles completed. Workflow COMPLETED.",
                    context.getWorkflowId(), context.getMaxCycles());

            stageExecutor.publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(),
                    "COMPLETED", responseData));
        }
    }

    /**
     * 从 OptimizeAgent 的响应中提取优化反馈。
     *
     * <p>尝试多个可能的 key（optimizationSuggestions / recommendations / feedback），
     * 将提取到的反馈存入 {@link TaskContext#lastOptimizationFeedback}，
     * 供下一轮循环的 TOPIC_PLANNING Agent 使用。
     */
    @SuppressWarnings("unchecked")
    private void extractOptimizationFeedback(TaskContext context, Map<String, Object> responseData) {
        if (responseData == null) return;
        Object feedback = responseData.get("optimizationSuggestions");
        if (feedback == null) feedback = responseData.get("recommendations");
        if (feedback == null) feedback = responseData.get("feedback");
        if (feedback == null) feedback = responseData.get("suggestions");
        if (feedback != null) {
            context.setLastOptimizationFeedback(feedback.toString());
            log.info("[Workflow:{}] Optimization feedback extracted ({} chars)",
                    context.getWorkflowId(), feedback.toString().length());
        }
    }
}
