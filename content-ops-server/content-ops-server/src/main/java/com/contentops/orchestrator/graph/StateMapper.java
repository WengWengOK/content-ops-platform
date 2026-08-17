package com.contentops.orchestrator.graph;

import com.contentops.common.dto.TaskContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * TaskContext ↔ ContentOpsState 双向转换器。
 *
 * <p>Agent 微服务使用 {@link TaskContext}，LangGraph4j 使用 {@link ContentOpsState}。
 * 此类负责在两种状态表示之间无损转换。
 */
@Component
public class StateMapper {

    /**
     * TaskContext → ContentOpsState 的初始化数据（图执行前转换）。
     *
     * @param context 工作流上下文
     * @return 可用于构造 ContentOpsState 的 Map 数据
     */
    public Map<String, Object> toStateData(TaskContext context) {
        Map<String, Object> data = new HashMap<>();
        data.put(ContentOpsState.WORKFLOW_ID, context.getWorkflowId());
        data.put(ContentOpsState.CURRENT_STAGE, context.getCurrentStage());
        data.put(ContentOpsState.CURRENT_SUB_STAGE, context.getCurrentSubStage());
        data.put(ContentOpsState.ACCOUNT_PROFILE, context.getAccountProfile());
        data.put(ContentOpsState.INPUTS, context.getInputs() != null
                ? new HashMap<>(context.getInputs()) : new HashMap<>());
        data.put(ContentOpsState.OUTPUTS, context.getOutputs());
        data.put(ContentOpsState.ACCUMULATED_ARTIFACTS, context.getAccumulatedArtifacts() != null
                ? new HashMap<>(context.getAccumulatedArtifacts()) : new HashMap<>());
        data.put(ContentOpsState.CYCLE_COUNT, context.getCycleCount());
        data.put(ContentOpsState.MAX_CYCLES, context.getMaxCycles());
        data.put(ContentOpsState.REQUIRE_HUMAN_REVIEW, context.isRequireHumanReview());

        if (context.getLastOptimizationFeedback() != null) {
            data.put(ContentOpsState.LAST_OPTIMIZATION_FEEDBACK, context.getLastOptimizationFeedback());
        }

        if (context.getCycleHistory() != null) {
            data.put(ContentOpsState.CYCLE_HISTORY, new java.util.ArrayList<>(context.getCycleHistory().values()));
        }

        return data;
    }

    /**
     * ContentOpsState → TaskContext（图执行后转换，用于回写 Redis）。
     *
     * @param state    LangGraph4j 状态
     * @param original 原始 TaskContext（会被原地修改并返回）
     * @return 修改后的 TaskContext
     */
    @SuppressWarnings("unchecked")
    public TaskContext toTaskContext(ContentOpsState state, TaskContext original) {
        original.setCycleCount(state.cycleCount());
        original.setMaxCycles(state.maxCycles());

        state.value(ContentOpsState.INPUTS).ifPresent(v ->
                original.setInputs(new HashMap<>((Map<String, Object>) v)));
        state.value(ContentOpsState.OUTPUTS).ifPresent(v ->
                original.setOutputs((Map<String, Object>) v));
        state.value(ContentOpsState.ACCUMULATED_ARTIFACTS).ifPresent(v ->
                original.setAccumulatedArtifacts(new HashMap<>((Map<String, Object>) v)));
        state.value(ContentOpsState.CURRENT_STAGE).ifPresent(v ->
                original.setCurrentStage(v.toString()));
        state.value(ContentOpsState.LAST_OPTIMIZATION_FEEDBACK).ifPresent(v ->
                original.setLastOptimizationFeedback(v.toString()));

        return original;
    }
}
