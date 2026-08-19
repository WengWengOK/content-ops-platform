package com.contentops.orchestrator.gateway;

import com.contentops.common.api.ToolsContracts.*;
import com.contentops.orchestrator.server.ServiceEndpointProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Phase3 Remote Tools 网关 — HTTP 远程调用 Tools 服务。
 *
 * <p>激活条件：{@code CONTENTOPS_MODE=microservice}。
 *
 * <p>当前定位（Phase 3.1）：**契约预留 + 部分启用**。
 * <ul>
 *   <li>Agent 调用已通过 {@link RemoteAgentGateway} 真迁出到 Worker 进程（核心价值已交付）。</li>
 *   <li>Tools 调用（RAG 检索/趋势查询/Agent 输出 ingest）仍保留在 Orchestrator 进程内本地执行，
 *       因为 {@code KnowledgeBaseService} 是具体类而非接口，全量抽接口工作量大且违反渐进原则。</li>
 *   <li>本类提供完整的 HTTP 调用能力，供未来 Phase 3.2 改造 {@code RagRetrievalEnhancer} /
 *       {@code AgentOutputIngester} 时注入使用（届时把 {@code KnowledgeBaseService} 抽接口 + 提供 Remote 实现）。</li>
 *   <li>也可供编排器侧新代码直接使用（如新增的 admin/调试接口需要调 tools 时）。</li>
 * </ul>
 *
 * <p>核心方法：
 * <ul>
 *   <li>{@link #ragSearch} → POST tools:8082/internal/api/tools/rag/search</li>
 *   <li>{@link #trends} → GET tools:8082/internal/api/tools/trends</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "CONTENTOPS_MODE", havingValue = "microservice")
public class RemoteToolsGateway {

    private static final String RAG_SEARCH_PATH = "/internal/api/tools/rag/search";
    private static final String RAG_INGEST_PATH = "/internal/api/tools/rag/ingest";
    private static final String TRENDS_PATH = "/internal/api/tools/trends";

    private final ServiceEndpointProperties endpointProperties;
    private final RestTemplate restTemplate;

    public RemoteToolsGateway(ServiceEndpointProperties endpointProperties,
                              @org.springframework.beans.factory.annotation.Qualifier("toolsRestTemplate") RestTemplate restTemplate) {
        this.endpointProperties = endpointProperties;
        this.restTemplate = restTemplate;
    }

    /** RAG 向量检索：调 Tools 服务的 /internal/api/tools/rag/search */
    public RagSearchResponse ragSearch(String query, String accountId, String niche, int topK, double minScore) {
        String url = endpointProperties.getTools().getBaseUrl() + RAG_SEARCH_PATH;
        RagSearchRequest req = RagSearchRequest.builder()
                .query(query)
                .accountId(accountId)
                .niche(niche)
                .topK(topK)
                .minScore(minScore)
                .build();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            org.springframework.http.ResponseEntity<RagSearchResponse> resp =
                    restTemplate.postForEntity(url, new HttpEntity<>(req, headers), RagSearchResponse.class);
            return resp.getBody() != null ? resp.getBody() :
                    RagSearchResponse.builder().success(false).errorMessage("Tools 返回空 body").build();
        } catch (Exception e) {
            log.warn("[Remote-Tools] RAG 检索失败: query={}, err={}", query, e.getMessage());
            return RagSearchResponse.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    /** 趋势热点查询：调 Tools 服务的 /internal/api/tools/trends */
    public TrendsResponse trends(String platform, String niche, int limit) {
        String url = String.format("%s%s?platform=%s&niche=%s&limit=%d",
                endpointProperties.getTools().getBaseUrl(), TRENDS_PATH,
                platform == null ? "" : platform,
                niche == null ? "" : niche,
                limit);
        try {
            org.springframework.http.ResponseEntity<TrendsResponse> resp =
                    restTemplate.getForEntity(url, TrendsResponse.class);
            return resp.getBody() != null ? resp.getBody() :
                    TrendsResponse.builder().success(false).errorMessage("Tools 返回空 body").build();
        } catch (Exception e) {
            log.warn("[Remote-Tools] 趋势查询失败: platform={}, err={}", platform, e.getMessage());
            return TrendsResponse.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    /** 便捷方法：返回检索结果列表（失败返回空 List） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> ragSearchAsList(String query, String accountId, String niche, int topK, double minScore) {
        RagSearchResponse resp = ragSearch(query, accountId, niche, topK, minScore);
        return resp.isSuccess() && resp.getResults() != null ? resp.getResults() : List.of();
    }

    /** Agent 输出 → 知识库写入：调 Tools 服务的 /internal/api/tools/rag/ingest */
    public RagIngestResponse ragIngest(String content, String type, String agent,
                                       String niche, String workflowId, String accountId) {
        String url = endpointProperties.getTools().getBaseUrl() + RAG_INGEST_PATH;
        RagIngestRequest req = RagIngestRequest.builder()
                .content(content)
                .type(type)
                .agent(agent)
                .niche(niche)
                .workflowId(workflowId)
                .accountId(accountId)
                .build();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            org.springframework.http.ResponseEntity<RagIngestResponse> resp =
                    restTemplate.postForEntity(url, new HttpEntity<>(req, headers), RagIngestResponse.class);
            return resp.getBody() != null ? resp.getBody() :
                    RagIngestResponse.builder().success(false).errorMessage("Tools 返回空 body").build();
        } catch (Exception e) {
            log.warn("[Remote-Tools] RAG 写入失败: type={}, err={}", type, e.getMessage());
            return RagIngestResponse.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }
}
