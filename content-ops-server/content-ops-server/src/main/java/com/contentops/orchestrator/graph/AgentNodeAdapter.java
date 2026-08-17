package com.contentops.orchestrator.graph;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.orchestrator.gateway.AgentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 节点适配器：将 Agent 调用适配为 LangGraph4j 的 {@link org.bsc.langgraph4j.action.NodeAction}。
 *
 * <p>每个 Agent 阶段对应一个图节点，节点内部：
 * <ol>
 *   <li>从 {@link ContentOpsState} 构建 {@link AgentTaskRequest}</li>
 *   <li>通过 {@link AgentGateway} 调用 Agent（支持 mock / microservice 模式切换）</li>
 *   <li>将响应产物合并到状态中</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeAdapter {

    private final AgentGateway agentGateway;
    private final StateMapper stateMapper;

    /**
     * 创建同步图节点 action。
     *
     * <p>内部通过对应的 Feign client 调用 Agent 微服务，
     * 将响应产物合并到 accumulatedArtifacts 中。
     *
     * @param stageCode Agent 阶段代码（如 "topic-planning"）
     * @return NodeAction，可直接传给 {@link org.bsc.langgraph4j.StateGraph#addNode}
     */
    public org.bsc.langgraph4j.action.NodeAction<ContentOpsState> syncNode(String stageCode) {
        return state -> {
            String workflowId = state.workflowId();

            AgentTaskRequest request = buildRequest(state, stageCode);

            log.info("[Graph:{}] Executing node: {}", workflowId, stageCode);

            AgentResponse<Map<String, Object>> response = callAgent(stageCode, request);

            if (response.isSuccess()) {
                Map<String, Object> artifacts = new HashMap<>(state.accumulatedArtifacts());
                artifacts.put(stageCode, response.getData());

                log.info("[Graph:{}] Node {} completed successfully", workflowId, stageCode);

                return Map.of(
                    ContentOpsState.OUTPUTS, response.getData() != null ? response.getData() : Map.of(),
                    ContentOpsState.ACCUMULATED_ARTIFACTS, artifacts
                );
            } else {
                throw new RuntimeException(
                    "Agent " + stageCode + " failed: " + response.getError());
            }
        };
    }

    /**
     * 根据 stageCode 路由到对应的 AgentGateway 并执行调用。
     */
    private AgentResponse<Map<String, Object>> callAgent(String stageCode, AgentTaskRequest request) {
        return switch (stageCode) {
            case "topic-planning"  -> agentGateway.callTopic(request);
            case "content-creation"-> agentGateway.callContentDraft(request);
            case "image-design"    -> agentGateway.callImageGenerate(request);
            case "publishing"      -> agentGateway.callPublish(request);
            case "data-analysis"   -> agentGateway.callAnalysis(request);
            case "optimization"    -> agentGateway.callOptimize(request);
            default -> throw new IllegalArgumentException("Unknown stage code: " + stageCode);
        };
    }

    /**
     * 从 ContentOpsState 构建 AgentTaskRequest。
     */
    @SuppressWarnings("unchecked")
    private AgentTaskRequest buildRequest(ContentOpsState state, String stageCode) {
        TaskContext.AccountProfile profile = state.value(ContentOpsState.ACCOUNT_PROFILE)
                .map(v -> (TaskContext.AccountProfile) v)
                .orElse(null);
        Map<String, Object> inputs = new HashMap<>(state.inputs());
        Map<String, Object> artifacts = new HashMap<>(state.accumulatedArtifacts());
        String workflowId = state.workflowId();

        return AgentTaskRequest.of(workflowId, stageCode, profile, inputs, artifacts);
    }
}
