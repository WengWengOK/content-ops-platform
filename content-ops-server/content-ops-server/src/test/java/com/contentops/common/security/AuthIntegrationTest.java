package com.contentops.common.security;

import com.contentops.server.ContentOpsServerApplication;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.topic.service.DiscussionSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 鉴权集成测试（P0：contentops.security.enabled=true）。
 *
 * <p>验证：未登录 401；注册/登录后携带 Bearer Token 可访问业务 API；
 * 工作流按 owner 数据隔离（A 看不到 B 的工作流，B 访问 A 的工作流返回 403）。
 */
@SpringBootTest(
        classes = ContentOpsServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "CONTENTOPS_MODE=mock",
                "contentops.security.enabled=true",
                "contentops.security.jwt-secret=test-secret-0123456789abcdef",
                "spring.datasource.url=jdbc:h2:mem:itauth;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver"
        }
)
class AuthIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DiscussionSessionService discussionSessionService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    private final Map<String, String> redisStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        // 4xx/5xx 不抛异常，便于断言状态码
        restTemplate.getRestTemplate().setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });

        redisStore.clear();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> redisStore.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenAnswer(inv -> cursorOf(new ArrayList<>(redisStore.keySet())));
        when(redisTemplate.delete(anyString()))
                .thenAnswer(inv -> redisStore.remove(inv.getArgument(0)) != null);
    }

    @Test
    @DisplayName("未登录访问业务 API 返回 401")
    void unauthenticated_shouldReturn401() {
        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/v1/workflow", Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("注册登录后携带 Token 可访问，且工作流按用户隔离")
    @SuppressWarnings("unchecked")
    void authenticatedFlow_withOwnerIsolation() {
        AuthCredentials alice = registerAndLogin("alice_01", "password123");
        AuthCredentials bob = registerAndLogin("bob_02", "password456");

        // A 启动一个工作流
        Map<String, Object> payload = Map.of(
                "accountProfile", Map.of(
                        "accountId", "acc-a",
                        "accountName", "账号A",
                        "niche", "个人成长",
                        "targetAudience", "职场人",
                        "tone", "专业",
                        "platforms", List.of("wechat")),
                "inputs", Map.of("topic", "A 的选题"),
                "requireHumanReview", false);
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(alice.token());
        ResponseEntity<Map> startResp = restTemplate.postForEntity(
                "/api/v1/workflow/start", new HttpEntity<>(payload, headersA), Map.class);
        assertEquals(HttpStatus.OK, startResp.getStatusCode());
        String workflowId = (String) ((Map) startResp.getBody().get("data")).get("workflowId");
        assertNotNull(workflowId);

        // A 的列表有 1 条
        ResponseEntity<Map> listA = restTemplate.exchange(
                "/api/v1/workflow", HttpMethod.GET, new HttpEntity<>(headersA), Map.class);
        Map<String, Object> dataA = (Map<String, Object>) listA.getBody().get("data");
        assertEquals(1, ((Number) dataA.get("total")).intValue());

        // B 的列表为空（数据隔离）
        HttpHeaders headersB = new HttpHeaders();
        headersB.setBearerAuth(bob.token());
        ResponseEntity<Map> listB = restTemplate.exchange(
                "/api/v1/workflow", HttpMethod.GET, new HttpEntity<>(headersB), Map.class);
        Map<String, Object> dataB = (Map<String, Object>) listB.getBody().get("data");
        assertEquals(0, ((Number) dataB.get("total")).intValue());

        // A 可查看自己工作流状态；B 访问 A 的工作流返回 403
        ResponseEntity<Map> statusA = restTemplate.exchange(
                "/api/v1/workflow/" + workflowId + "/status",
                HttpMethod.GET, new HttpEntity<>(headersA), Map.class);
        assertEquals(HttpStatus.OK, statusA.getStatusCode());

        ResponseEntity<Map> statusB = restTemplate.exchange(
                "/api/v1/workflow/" + workflowId + "/status",
                HttpMethod.GET, new HttpEntity<>(headersB), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, statusB.getStatusCode());
    }

    @Test
    @DisplayName("讨论会话按 owner 隔离（越权访问被拒绝）")
    void discussionSession_ownerIsolation() {
        AuthCredentials alice = registerAndLogin("alice_03", "password123");
        AuthCredentials bob = registerAndLogin("bob_04", "password456");

        // 直接创建会话并归属 alice（绕过 LLM 调用）
        DiscussionSession session = discussionSessionService.createSession("模糊创意", null);
        session.setOwnerId(alice.userId());
        discussionSessionService.saveSession(session);

        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(alice.token());
        ResponseEntity<Map> getA = restTemplate.exchange(
                "/api/v1/discussion/" + session.getSessionId(),
                HttpMethod.GET, new HttpEntity<>(headersA), Map.class);
        assertEquals(Boolean.TRUE, getA.getBody().get("success"), "本人应可查看讨论会话");

        HttpHeaders headersB = new HttpHeaders();
        headersB.setBearerAuth(bob.token());
        ResponseEntity<Map> getB = restTemplate.exchange(
                "/api/v1/discussion/" + session.getSessionId(),
                HttpMethod.GET, new HttpEntity<>(headersB), Map.class);
        assertEquals(Boolean.FALSE, getB.getBody().get("success"), "他人访问讨论会话应被拒绝");
    }

    private AuthCredentials registerAndLogin(String username, String password) {
        ResponseEntity<Map> regResp = restTemplate.postForEntity(
                "/api/v1/auth/register", Map.of("username", username, "password", password), Map.class);
        assertEquals(HttpStatus.OK, regResp.getStatusCode());
        assertTrue((Boolean) regResp.getBody().get("success"), "注册应成功");

        ResponseEntity<Map> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("username", username, "password", password), Map.class);
        assertEquals(HttpStatus.OK, loginResp.getStatusCode());
        assertTrue((Boolean) loginResp.getBody().get("success"), "登录应成功");
        Map<String, Object> data = (Map<String, Object>) loginResp.getBody().get("data");
        return new AuthCredentials((String) data.get("token"), (String) data.get("userId"));
    }

    private record AuthCredentials(String token, String userId) {
    }

    private static org.springframework.data.redis.core.Cursor<String> cursorOf(List<String> keys) {
        Iterator<String> iterator = keys.iterator();
        return new org.springframework.data.redis.core.Cursor<>() {
            @Override
            public CursorId getId() {
                return null;
            }

            @Override
            public long getCursorId() {
                return 0;
            }

            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public long getPosition() {
                return 0;
            }

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public String next() {
                return iterator.next();
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }
}
