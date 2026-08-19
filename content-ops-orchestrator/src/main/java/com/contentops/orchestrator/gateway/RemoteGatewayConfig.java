package com.contentops.orchestrator.gateway;

import com.contentops.orchestrator.server.ServiceEndpointProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Phase3 Remote 网关配置：仅在 {@code CONTENTOPS_MODE=microservice} 时装配 RestTemplate。
 *
 * <p>超时配置来自 {@link ServiceEndpointProperties}：
 * <ul>
 *   <li>Worker：connectTimeout=5s / readTimeout=120s（LLM 推理慢，120s 兜底）</li>
 *   <li>Tools：connectTimeout=3s / readTimeout=30s（向量检索/趋势查询，30s 足够）</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "CONTENTOPS_MODE", havingValue = "microservice")
public class RemoteGatewayConfig {

    @Bean(name = "workerRestTemplate")
    public RestTemplate workerRestTemplate(RestTemplateBuilder builder,
                                           ServiceEndpointProperties props) {
        ServiceEndpointProperties.Endpoint e = props.getWorker();
        return builder
                .connectTimeout(Duration.ofMillis(e.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(e.getReadTimeoutMs()))
                .build();
    }

    @Bean(name = "toolsRestTemplate")
    public RestTemplate toolsRestTemplate(RestTemplateBuilder builder,
                                          ServiceEndpointProperties props) {
        ServiceEndpointProperties.Endpoint e = props.getTools();
        return builder
                .connectTimeout(Duration.ofMillis(e.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(e.getReadTimeoutMs()))
                .build();
    }
}
