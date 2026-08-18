package com.contentops.orchestrator.graph;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.knowledge.AgentOutputIngester;
import com.contentops.common.knowledge.RagRetrievalEnhancer;
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
 *
 * <p><b>长期记忆与上下文工程：</b>节点执行成功后，输出回流知识库
 * （{@link AgentOutputIngester}）；构建请求前按 stage 注入 RAG 历史上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeAdapter {

    private final AgentGateway agentGateway;
    private final StateMapper stateMapper;
    private final AgentOutputIngester agentOutputIngester;
    private final RagRetrievalEnhancer ragRetrievalEnhancer;

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

                // 长期记忆 P0：输出回流知识库 + 落盘审计（失败不阻断图执行）
                try {
                    AgentStage stage = AgentStage.fromCode(stageCode);
                    TaskContext.AccountProfile profile = state.value(ContentOpsState.ACCOUNT_PROFILE)
                            .map(v -> (TaskContext.AccountProfile) v).orElse(null);
                    String accountId = profile != null ? profile.getAccountId() : null;
                    String niche = profile != null ? profile.getNiche() : null;
                    agentOutputIngester.ingest(stage, response.getData(), accountId, niche, workflowId);
                } catch (Exception e) {
                    log.warn("[Graph:{}] 输出回流知识库失败 stage={}: {}",
                            workflowId, stageCode, e.getMessage());
                }

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
     *
     * <p>长期记忆 P1：构建请求前，按 stage 判断是否注入 RAG 历史上下文到 inputs。
     */
    @SuppressWarnings("unchecked")
    private AgentTaskRequest buildRequest(ContentOpsState state, String stageCode) {
        TaskContext.AccountProfile profile = state.value(ContentOpsState.ACCOUNT_PROFILE)
                .map(v -> (TaskContext.AccountProfile) v)
                .orElse(null);
        Map<String, Object> inputs = new HashMap<>(state.inputs());
        Map<String, Object> artifacts = new HashMap<>(state.accumulatedArtifacts());
        String workflowId = state.workflowId();

        // RAG 上下文注入（按 contentops.rag.context-injection.* 开关控制）
        injectRagContextIfNeeded(state, stageCode, inputs, profile, workflowId);

        return AgentTaskRequest.of(workflowId, stageCode, profile, inputs, artifacts);
    }

    /**
     * 若该 stage 启用了 RAG 上下文注入，检索历史相似内容并塞入 inputs["ragContext"]。
     * 失败时只记日志，不影响请求构建。
     */
    private void injectRagContextIfNeeded(ContentOpsState state, String stageCode,
                                          Map<String, Object> inputs,
                                          TaskContext.AccountProfile profile,
                                          String workflowId) {
        if (!ragRetrievalEnhancer.shouldInjectContext(stageCode)) {
            return;
        }
        try {
            String query = buildRagQuery(inputs, state, stageCode);
            String niche = profile != null ? profile.getNiche() : null;
            String ragContext = ragRetrievalEnhancer.retrieveHistoricalContext(query, niche, 0);
            if (ragContext != null && !ragContext.isBlank()) {
                inputs.put("ragContext", ragContext);
                log.debug("[Graph:{}] RAG 上下文已注入 stage={}, chars={}",
                        workflowId, stageCode, ragContext.length());
            }
        } catch (Exception e) {
            log.warn("[Graph:{}] RAG 上下文注入失败 stage={}: {}",
                    workflowId, stageCode, e.getMessage());
        }
    }

    /**
     * 根据 stage 与上下文构建 RAG 检索查询。
     */
    private String buildRagQuery(Map<String, Object> inputs, ContentOpsState state, String stageCode) {
        Object topic = inputs.get("topic");
        if (topic == null) {
            topic = inputs.get("topicHint");
        }
        if (topic == null) {
            Map<String, Object> outputs = (Map<String, Object>) state.value(ContentOpsState.OUTPUTS)
                    .orElse(null);
            if (outputs != null) {
                topic = outputs.get("topic");
            }
        }
        return topic != null ? String.valueOf(topic) : stageCode;
    }
}
