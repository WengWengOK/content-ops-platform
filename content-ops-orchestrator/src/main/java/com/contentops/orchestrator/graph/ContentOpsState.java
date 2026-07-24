package com.contentops.orchestrator.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * LangGraph4j 共享状态 — 替代 TaskContext 的编排层角色。
 *
 * <p>Agent 微服务仍然使用 {@link com.contentops.common.dto.TaskContext}，
 * 此状态类仅在编排器内部使用。两者通过 {@link StateMapper} 相互转换。
 *
 * <p><b>Channel 更新策略：</b>
 * <ul>
 *   <li>{@code base}: 覆盖更新（默认）</li>
 *   <li>{@code appender}: 追加更新（列表追加，不覆盖）</li>
 * </ul>
 */
public class ContentOpsState extends AgentState {

    // ─── 循环控制 ───
    public static final String CYCLE_COUNT = "cycleCount";
    public static final String MAX_CYCLES = "maxCycles";
    public static final String CYCLE_HISTORY = "cycleHistory";

    // ─── 工作流字段（对应 TaskContext）───
    public static final String WORKFLOW_ID = "workflowId";
    public static final String CURRENT_STAGE = "currentStage";
    public static final String CURRENT_SUB_STAGE = "currentSubStage";
    public static final String ACCOUNT_PROFILE = "accountProfile";
    public static final String INPUTS = "inputs";
    public static final String OUTPUTS = "outputs";
    public static final String ACCUMULATED_ARTIFACTS = "accumulatedArtifacts";
    public static final String REQUIRE_HUMAN_REVIEW = "requireHumanReview";
    public static final String LAST_OPTIMIZATION_FEEDBACK = "lastOptimizationFeedback";

    /**
     * Schema 定义：Channel 更新策略。
     *
     * <p>每个 key 对应一个 {@link Channel}，决定节点返回的 partial update 如何合并到状态中：
     * <ul>
     *   <li>{@code base}: 新值直接覆盖旧值（默认策略）</li>
     *   <li>{@code appender}: 新值追加到列表末尾（用于 cycleHistory）</li>
     * </ul>
     */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
        CYCLE_COUNT, Channels.base(() -> 0),
        MAX_CYCLES, Channels.base(() -> 3),
        CYCLE_HISTORY, Channels.appender(ArrayList::new),
        ACCUMULATED_ARTIFACTS, Channels.base(() -> new HashMap<String, Object>()),
        INPUTS, Channels.base(() -> new HashMap<String, Object>()),
        WORKFLOW_ID, Channels.base(() -> ""),
        CURRENT_STAGE, Channels.base(() -> ""),
        REQUIRE_HUMAN_REVIEW, Channels.base(() -> false)
    );

    public ContentOpsState(Map<String, Object> initData) {
        super(initData);
    }

    // ─── 类型安全的访问器 ───

    public int cycleCount() {
        return value(CYCLE_COUNT).map(v -> (Integer) v).orElse(0);
    }

    public int maxCycles() {
        return value(MAX_CYCLES).map(v -> (Integer) v).orElse(3);
    }

    public String workflowId() {
        return value(WORKFLOW_ID).map(Object::toString).orElse("");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> accumulatedArtifacts() {
        return value(ACCUMULATED_ARTIFACTS).map(v -> (Map<String, Object>) v).orElse(new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> inputs() {
        return value(INPUTS).map(v -> (Map<String, Object>) v).orElse(new HashMap<>());
    }

    public boolean requireHumanReview() {
        return value(REQUIRE_HUMAN_REVIEW).map(v -> (Boolean) v).orElse(false);
    }

    /**
     * 判断是否应终止循环。
     *
     * @return true 如果 cycleCount >= maxCycles
     */
    public boolean shouldTerminate() {
        return cycleCount() >= maxCycles();
    }
}
