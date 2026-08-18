package com.contentops.orchestrator.server;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase3 三服务地址配置（编排器视角）：
 * <p>第一阶段仅用于展示契约，后续 Agent 迁出后，编排器用此配置构造 RestClient 调用 worker。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.services")
public class ServiceEndpointProperties {

    private Endpoint worker = new Endpoint("http://127.0.0.1:8081", 5_000, 120_000);
    private Endpoint tools  = new Endpoint("http://127.0.0.1:8082", 3_000,  30_000);

    @Data
    public static class Endpoint {
        private String baseUrl;
        private int connectTimeoutMs;
        private int readTimeoutMs;

        public Endpoint() {}

        public Endpoint(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
            this.baseUrl = baseUrl;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
