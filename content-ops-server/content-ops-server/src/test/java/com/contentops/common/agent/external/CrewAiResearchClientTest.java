package com.contentops.common.agent.external;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CrewAI 客户端测试：请求体拼装、响应解析、不可达降级（用 JDK 内置 HttpServer 做桩，无外部依赖）。
 */
class CrewAiResearchClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private int startStub(AtomicReference<String> capturedBody, String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/research", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\",\"crewaiAvailable\":true,\"executor\":\"crewai\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    private ExternalAgentProperties propertiesFor(int port) {
        ExternalAgentProperties properties = new ExternalAgentProperties();
        properties.getCrewai().setEnabled(true);
        properties.getCrewai().setBaseUrl("http://127.0.0.1:" + port);
        return properties;
    }

    @Test
    @DisplayName("研究请求：带上 topic/niche/platforms，并解析返回结构")
    void postsResearchRequest() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        int port = startStub(captured, "{\"executor\":\"crewai\",\"topicCandidates\":[{\"title\":\"T1\"}]}");
        CrewAiResearchClient client = new CrewAiResearchClient(propertiesFor(port));

        Map<String, Object> result = client.research("AI 提效", "效率工具",
                List.of("XHS", "WECHAT"), 3, "crewai");

        assertThat(result.get("executor")).isEqualTo("crewai");
        assertThat(captured.get()).contains("AI 提效").contains("效率工具")
                .contains("XHS").contains("\"maxTopics\":3").contains("\"executor\":\"crewai\"");
        assertThat(client.researchEndpoint()).endsWith("/research");
    }

    @Test
    @DisplayName("可选项为空时不下发对应字段")
    void omitsBlankOptions() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        int port = startStub(captured, "{\"executor\":\"lite\"}");
        CrewAiResearchClient client = new CrewAiResearchClient(propertiesFor(port));

        client.research("单主题", null, List.of(), null, null);

        assertThat(captured.get()).contains("单主题")
                .doesNotContain("niche").doesNotContain("platforms").doesNotContain("maxTopics");
    }

    @Test
    @DisplayName("探活成功：解析 health 字段")
    void healthOk() throws IOException {
        int port = startStub(new AtomicReference<>(), "{}");
        CrewAiResearchClient client = new CrewAiResearchClient(propertiesFor(port));

        Map<String, Object> health = client.health();

        assertThat(health).containsEntry("status", "ok").containsEntry("crewaiAvailable", true);
    }

    @Test
    @DisplayName("服务不可达：探活降级为 unreachable，不抛异常")
    void healthUnreachable() throws IOException {
        int port = startStub(new AtomicReference<>(), "{}");
        server.stop(0);
        server = null;
        CrewAiResearchClient client = new CrewAiResearchClient(propertiesFor(port));

        Map<String, Object> health = client.health();

        assertThat(health).containsEntry("status", "unreachable");
    }
}
