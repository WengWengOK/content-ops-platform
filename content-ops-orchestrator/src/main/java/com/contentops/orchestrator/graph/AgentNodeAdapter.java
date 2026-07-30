package com.contentops.orchestrator.graph;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.orchestrator.resilience.ResilientAgentClient;
import com.contentops.orchestrator.service.AgentFeignClients.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 节点适配器：将 Feign 调用适配为 LangGraph4j 的 {@link org.bsc.langgraph4j.action.NodeAction}。
 *
 * <p>每个 Agent 阶段对应一个图节点，节点内部：
 * <ol>
 *   <li>从 {@link ContentOpsState} 构建 {@link AgentTaskRequest}</li>
 *   <li>通过 {@link ResilientAgentClient} 调用 Feign（保留熔断/重试）</li>
 *   <li>将响应产物合并到状态中</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeAdapter {

    private final ResilientAgentClient resilientClient;
    private final StateMapper stateMapper;

    // Feign clients (injected via ResilientAgentClient or directly)
    private final TopicAgentClient topicClient;
    private final ContentAgentClient contentClient;
    private final ImageAgentClient imageClient;
    private final PublishAgentClient publishClient;
    private final AnalysisAgentClient analysisClient;
    private final OptimizeAgentClient optimizeClient;

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
     * 根据 stageCode 路由到对应的 Feign client 并执行调用。
     */
    private AgentResponse<Map<String, Object>> callAgent(String stageCode, AgentTaskRequest request) {
        return switch (stageCode) {
            case "topic-planning"  -> resilientClient.callTopic(request);
            case "content-creation"-> resilientClient.callContentDraft(request);
            case "image-design"    -> resilientClient.callImageGenerate(request);
            case "publishing"      -> resilientClient.callPublish(request);
            case "data-analysis"   -> resilientClient.callAnalysis(request);
            case "optimization"    -> resilientClient.callOptimize(request);
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
