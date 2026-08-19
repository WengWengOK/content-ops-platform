package com.contentops.orchestrator.gateway;

import com.contentops.common.api.AgentExecuteRequest;
import com.contentops.common.api.AgentExecuteResponse;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.DiscussionResponse;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.TopicPlanResult;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.orchestrator.server.ServiceEndpointProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase3 微服务模式 Agent 网关 — HTTP 远程调用 Worker 服务。
 *
 * <p>激活条件：{@code CONTENTOPS_MODE=microservice}（不匹配 monolithic/mock 时激活）。
 *
 * <p>核心价值（服务化拆分 #1 理由：故障域隔离）：
 * <ul>
 *   <li>Agent 的 LLM 调用是网络 I/O 密集型，延迟 2-30s 不可控；单体下线程池被慢响应打满
 *       会拖垮编排器状态机推进，工作流"假死"。</li>
 *   <li>本网关把 Agent 调用迁到独立 Worker 进程（端口 8081），编排器线程池只做状态机推进，
 *       LLM 慢响应爆炸半径限定在 Worker 进程内。</li>
 *   <li>Worker 可独立水平扩容（K8s HPA 按 LLM 调用 QPS 扩容），不影响编排器实例数。</li>
 * </ul>
 *
 * <p>调用链：Orchestrator.StageExecutor → RemoteAgentGateway.callXxx →
 *           HTTP POST worker:8081/internal/api/agent/execute →
 *           Worker.WorkerInternalContractController → 本地 AgentGateway → Agent Controller。
 *
 * <p>讨论模式 5 个方法（startDiscussion/chatDiscussion/finalizeDiscussion/get/clear）暂走 HTTP
 * 的独立端点（Worker 侧待补；当前 throw UnsupportedOperationException 提示用户切换 monolithic）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "CONTENTOPS_MODE", havingValue = "microservice")
public class RemoteAgentGateway implements AgentGateway {

    private static final String EXECUTE_PATH = "/internal/api/agent/execute";

    private final ServiceEndpointProperties endpointProperties;
    private final RestTemplate restTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RemoteAgentGateway(ServiceEndpointProperties endpointProperties,
                              @org.springframework.beans.factory.annotation.Qualifier("workerRestTemplate") RestTemplate restTemplate,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.endpointProperties = endpointProperties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    // ─── 同步调用（10 个 stageCode 统一走 /execute，Worker 侧按 stageCode 路由） ───

    @Override
    public AgentResponse<Map<String, Object>> callTopic(AgentTaskRequest request) {
        return callRemote("topic-planning", request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callContentExecute(AgentTaskRequest request) {
        return callRemote("content-creation", request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callContentOutline(AgentTaskRequest request) {
        return callRemote("content-outline", request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callContentDraft(AgentTaskRequest request) {
        return callRemote("content-draft", request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callImageExecute(AgentTaskRequest request) {
        return callRemote("image-design", request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callImageStyles(AgentTaskRequest request) {
        return callRemote("image-styles", request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callImageGenerate(AgentTaskRequest request) {
        return callRemote("image-generate", request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callPublish(AgentTaskRequest request) {
        return callRemote("publishing", request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callAnalysis(AgentTaskRequest request) {
        return callRemote("data-analysis", request);
    }

    @Override
    public AgentResponse<Map<String, Object>> callOptimize(AgentTaskRequest request) {
        return callRemote("optimization", request);
    }

    // ─── 讨论模式（暂未走 HTTP，待 Worker 补端点后实现） ───

    @Override
    public AgentResponse<DiscussionResponse> startDiscussion(Map<String, Object> request) {
        throw new UnsupportedOperationException(
                "讨论模式暂不支持 microservice 模式，请使用 CONTENTOPS_MODE=monolithic");
    }

    @Override
    public AgentResponse<DiscussionResponse> chatDiscussion(String sessionId, Map<String, Object> request) {
        throw new UnsupportedOperationException(
                "讨论模式暂不支持 microservice 模式，请使用 CONTENTOPS_MODE=monolithic");
    }

    @Override
    public AgentResponse<TopicPlanResult> finalizeDiscussion(String sessionId) {
        throw new UnsupportedOperationException(
                "讨论模式暂不支持 microservice 模式，请使用 CONTENTOPS_MODE=monolithic");
    }

    @Override
    public AgentResponse<DiscussionSession> getDiscussionSession(String sessionId) {
        throw new UnsupportedOperationException(
                "讨论模式暂不支持 microservice 模式，请使用 CONTENTOPS_MODE=monolithic");
    }

    @Override
    public AgentResponse<Void> clearDiscussionSession(String sessionId) {
        throw new UnsupportedOperationException(
                "讨论模式暂不支持 microservice 模式，请使用 CONTENTOPS_MODE=monolithic");
    }

    // ────────────────────── 内部：HTTP 调用封装 ──────────────────────

    /**
     * 统一 HTTP 调用入口：把 AgentTaskRequest 转 AgentExecuteRequest，POST 到 Worker。
     *
     * <p>注意：accountProfile 是 POJO，直接塞 Map 会在 Jackson 序列化时丢失类型信息；
     * 这里用嵌套 Map 透传（Worker 侧如需强类型可自行 convertValue）。
     */
    @SuppressWarnings("unchecked")
    private AgentResponse<Map<String, Object>> callRemote(String stageCode, AgentTaskRequest request) {
        long start = System.currentTimeMillis();
        String url = endpointProperties.getWorker().getBaseUrl() + EXECUTE_PATH;
        log.debug("[Remote] HTTP 调用 Worker: url={}, stage={}, workflowId={}",
                url, stageCode, request.getWorkflowId());

        AgentExecuteRequest execReq = AgentExecuteRequest.builder()
                .workflowId(request.getWorkflowId())
                .taskId(request.getTaskId())
                .stageCode(stageCode)
                .inputs(request.getInputs())
                .createdAt(LocalDateTime.now())
                .accumulatedArtifacts(request.getAccumulatedArtifacts())
                .requireHumanReview(request.isRequireHumanReview())
                .timestamp(request.getTimestamp() == null ? LocalDateTime.now() : request.getTimestamp())
                .build();
        // accountProfile 透传（POJO → Map，避免 common 依赖 dto 模块）
        if (request.getAccountProfile() != null) {
            try {
                execReq.setAccountProfile(objectMapper.convertValue(request.getAccountProfile(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.debug("[Remote] accountProfile 转 Map 失败，跳过: {}", e.getMessage());
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            org.springframework.http.ResponseEntity<AgentExecuteResponse> resp =
                    restTemplate.postForEntity(url, new HttpEntity<>(execReq, headers), AgentExecuteResponse.class);
            long elapsed = System.currentTimeMillis() - start;

            if (resp.getBody() == null) {
                log.warn("[Remote] Worker 返回空 body: stage={}, workflowId={}, elapsed={}ms",
                        stageCode, request.getWorkflowId(), elapsed);
                return AgentResponse.<Map<String, Object>>builder()
                        .success(false)
                        .error("Worker 返回空 body")
                        .build();
            }

            AgentExecuteResponse body = resp.getBody();
            if (!body.isSuccess()) {
                log.warn("[Remote] Worker 执行失败: stage={}, err={}, elapsed={}ms",
                        stageCode, body.getErrorMessage(), elapsed);
                return AgentResponse.<Map<String, Object>>builder()
                        .success(false)
                        .error(body.getErrorMessage())
                        .metadata(body.getMetadata())
                        .build();
            }

            log.info("[Remote] Worker 执行成功: stage={}, workflowId={}, elapsed={}ms",
                    stageCode, request.getWorkflowId(), elapsed);
            return AgentResponse.<Map<String, Object>>builder()
                    .success(true)
                    .data(body.getData())
                    .metadata(mergeElapsed(body.getMetadata(), elapsed))
                    .build();
        } catch (org.springframework.web.client.RestClientException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[Remote] Worker HTTP 调用异常: stage={}, url={}, elapsed={}ms, err={}",
                    stageCode, url, elapsed, e.getMessage(), e);
            return AgentResponse.<Map<String, Object>>builder()
                    .success(false)
                    .error("Worker 调用失败: " + e.getMessage())
                    .build();
        }
    }

    /** 把 elapsedMs 注入 metadata，方便观测 Remote 调用耗时 */
    private Map<String, Object> mergeElapsed(Map<String, Object> meta, long elapsedMs) {
        Map<String, Object> result = meta == null ? new HashMap<>() : new HashMap<>(meta);
        result.put("remoteElapsedMs", elapsedMs);
        return result;
    }
}
