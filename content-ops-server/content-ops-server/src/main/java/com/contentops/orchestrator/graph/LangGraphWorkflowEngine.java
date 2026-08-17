package com.contentops.orchestrator.graph;

import com.contentops.common.dto.TaskContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LangGraph4j 工作流执行引擎。
 *
 * <p>封装 {@link CompiledGraph} 的调用逻辑，提供与原 {@code PipelineOrchestrator} 等价的能力：
 * <ul>
 *   <li>{@link #executeWorkflow(TaskContext)}: 启动完整工作流</li>
 *   <li>{@link #resumeWorkflow(TaskContext, Map)}: 从人工审核暂停处恢复执行</li>
 * </ul>
 *
 * <p><b>与 PipelineOrchestrator 的关键区别：</b>
 * <ul>
 *   <li>循环控制由 LangGraph4j 条件边原生处理，无需手动管理 cycleCount</li>
 *   <li>人机协同由 {@code interruptBefore} 原生处理，无需手动暂停/恢复</li>
 *   <li>状态检查点由 {@link org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver} 自动管理</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangGraphWorkflowEngine {

    private final ContentOpsGraph contentOpsGraph;
    private final StateMapper stateMapper;

    private volatile CompiledGraph<ContentOpsState> compiledGraph;

    /**
     * 获取已编译的图（懒加载 + 线程安全）。
     */
    private CompiledGraph<ContentOpsState> getCompiledGraph() {
        if (compiledGraph == null) {
            synchronized (this) {
                if (compiledGraph == null) {
                    try {
                        compiledGraph = contentOpsGraph.buildGraph();
                        log.info("[LangGraph] Graph compiled successfully");
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to compile graph", e);
                    }
                }
            }
        }
        return compiledGraph;
    }

    /**
     * 通过 LangGraph4j 图执行完整工作流。
     *
     * <p>流程：
     * <ol>
     *   <li>TaskContext → ContentOpsState 初始数据</li>
     *   <li>调用 {@code CompiledGraph.invoke()} 执行图</li>
     *   <li>如果因 interruptBefore 暂停 → 设置 TaskContext 状态为 AWAITING_HUMAN</li>
     *   <li>如果正常完成 → ContentOpsState → TaskContext 回写</li>
     * </ol>
     *
     * @param context 工作流上下文
     */
    public void executeWorkflow(TaskContext context) {
        try {
            Map<String, Object> stateData = stateMapper.toStateData(context);
            String threadId = context.getWorkflowId();

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            log.info("[LangGraph:{}] Starting workflow, threadId={}",
                    context.getWorkflowId(), threadId);

            var result = getCompiledGraph().invoke(stateData, config);

            if (result.isPresent()) {
                ContentOpsState finalState = result.get();
                stateMapper.toTaskContext(finalState, context);

                // 检查是否因 interrupt 暂停（状态未到 END）
                String currentStage = finalState.value(ContentOpsState.CURRENT_STAGE)
                        .map(Object::toString).orElse("");
                if (!currentStage.isEmpty()) {
                    context.setCurrentStage(currentStage);
                    context.setStatus(com.contentops.common.enums.TaskStatus.AWAITING_HUMAN.name());
                }

                log.info("[LangGraph:{}] Workflow paused or completed. Stage={}",
                        context.getWorkflowId(), currentStage);
            } else {
                log.info("[LangGraph:{}] Workflow completed (no final state)",
                        context.getWorkflowId());
                context.setStatus(com.contentops.common.enums.TaskStatus.COMPLETED.name());
            }

        } catch (Exception e) {
            log.error("[LangGraph:{}] Workflow failed", context.getWorkflowId(), e);
            context.setStatus(com.contentops.common.enums.TaskStatus.FAILED.name());
            context.setErrorMessage("LangGraph workflow failed: " + e.getMessage());
            throw new RuntimeException("LangGraph workflow failed", e);
        }
    }

    /**
     * 恢复暂停的工作流（人工审核后调用）。
     *
     * <p>当图因 {@code interruptBefore} 在 content/image 节点前暂停后，
     * 用户确认大纲/风格方向后调用此方法恢复执行。
     *
     * @param context  工作流上下文
     * @param feedback 人工反馈数据（如确认的大纲、选择的风格方向）
     */
    public void resumeWorkflow(TaskContext context, Map<String, Object> feedback) {
        try {
            String threadId = context.getWorkflowId();

            // 注入人工反馈到状态
            if (feedback != null && !feedback.isEmpty()) {
                Map<String, Object> inputs = context.getInputs() != null
                        ? new java.util.HashMap<>(context.getInputs())
                        : new java.util.HashMap<>();
                inputs.putAll(feedback);
                context.setInputs(inputs);
            }

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            log.info("[LangGraph:{}] Resuming workflow with feedback", threadId);

            var result = getCompiledGraph().invoke(
                    feedback != null && !feedback.isEmpty()
                            ? GraphInput.resume(feedback)
                            : GraphInput.resume(),
                    config);

            if (result.isPresent()) {
                ContentOpsState finalState = result.get();
                stateMapper.toTaskContext(finalState, context);

                String currentStage = finalState.value(ContentOpsState.CURRENT_STAGE)
                        .map(Object::toString).orElse("");
                if (!currentStage.isEmpty()) {
                    context.setCurrentStage(currentStage);
                    context.setStatus(com.contentops.common.enums.TaskStatus.AWAITING_HUMAN.name());
                }

                log.info("[LangGraph:{}] Workflow resumed. Stage={}", threadId, currentStage);
            } else {
                log.info("[LangGraph:{}] Workflow completed after resume", threadId);
                context.setStatus(com.contentops.common.enums.TaskStatus.COMPLETED.name());
            }

        } catch (Exception e) {
            log.error("[LangGraph:{}] Resume failed", context.getWorkflowId(), e);
            context.setStatus(com.contentops.common.enums.TaskStatus.FAILED.name());
            context.setErrorMessage("LangGraph resume failed: " + e.getMessage());
            throw new RuntimeException("LangGraph resume failed", e);
        }
    }
}
