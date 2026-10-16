package com.contentops.common.agent.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CrewAI 研究服务客户端。
 *
 * <p>只做两件事：探活（{@code /health}）和发起研究（{@code /research}）。
 * 异常统一转成「不可用」结果，避免外部服务抖动把平台接口带崩。
 */
@Slf4j
@Component
public class CrewAiResearchClient {

    private final ExternalAgentProperties properties;
    private final RestClient restClient;

    public CrewAiResearchClient(ExternalAgentProperties properties) {
        this.properties = properties;
        Duration timeout = Duration.ofMillis(Math.max(1000, properties.getCrewai().getTimeoutMs()));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 是否已启用（总开关 + crewai 开关都开）。 */
    public boolean enabled() {
        return properties.isEnabled() && properties.getCrewai().isEnabled();
    }

    /** 研究接口地址（测试用，便于断言拼接结果）。 */
    public String researchEndpoint() {
        return baseUrl() + "/research";
    }

    /** 探活：crewai 是否可用、执行器、LLM 是否就绪。 */
    public Map<String, Object> health() {
        try {
            Map<String, Object> body = restClient.get()
                    .uri(baseUrl() + "/health")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return body == null ? Map.of("status", "empty") : body;
        } catch (Exception e) {
            log.warn("[ExternalAgent] crew 服务探活失败: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "unreachable");
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 发起一次研究。
     *
     * @param topic      研究主题
     * @param niche      账号定位/领域
     * @param platforms  目标平台代号
     * @param maxTopics  选题候选数量（为空用 crew 侧配置）
     * @param executor   crewai / lite / null（按 crew 服务配置）
     * @return crew 返回的结构化结果（含 reportMarkdown）
     */
    public Map<String, Object> research(String topic, String niche, List<String> platforms,
                                        Integer maxTopics, String executor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("topic", topic);
        if (niche != null && !niche.isBlank()) {
            payload.put("niche", niche);
        }
        if (platforms != null && !platforms.isEmpty()) {
            payload.put("platforms", platforms);
        }
        if (maxTopics != null && maxTopics > 0) {
            payload.put("maxTopics", maxTopics);
        }
        if (executor != null && !executor.isBlank()) {
            payload.put("executor", executor);
        }
        Map<String, Object> body = restClient.post()
                .uri(researchEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
        if (body == null) {
            throw new IllegalStateException("crew 服务返回空响应");
        }
        return body;
    }

    private String baseUrl() {
        String url = properties.getCrewai().getBaseUrl();
        return url == null || url.isBlank() ? "http://localhost:8000" : url.replaceAll("/+$", "");
    }
}
