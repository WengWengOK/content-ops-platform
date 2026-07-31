package com.contentops.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ActuatorSecurityFilter 单元测试 — 验证 P2 安全过滤器行为。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>安全关闭时放行所有请求</li>
 *   <li>安全开启时的鉴权逻辑（缺失/错误/正确 API Key）</li>
 *   <li>健康检查端点白名单</li>
 *   <li>非 Actuator 路径不拦截</li>
 * </ul>
 */
@DisplayName("ActuatorSecurityFilter 鉴权测试")
@ExtendWith(MockitoExtension.class)
class ActuatorSecurityFilterTest {

    private ActuatorSecurityProperties properties;
    private ActuatorSecurityFilter filter;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        properties = new ActuatorSecurityProperties();
        properties.setEnabled(true);
        properties.setApiKey("secret-key-12345");
        properties.setHeaderName("X-Actuator-Key");
        filter = new ActuatorSecurityFilter(properties);
    }

    @Test
    @DisplayName("安全关闭时应放行所有请求")
    void securityDisabled_shouldPassThrough() throws Exception {
        properties.setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("正确 API Key 应放行")
    void correctApiKey_shouldPassThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("X-Actuator-Key", "secret-key-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("缺失 API Key 应返回 401")
    void missingApiKey_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("Missing API key"));
    }

    @Test
    @DisplayName("错误 API Key 应返回 401")
    void wrongApiKey_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("X-Actuator-Key", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Invalid API key"));
    }

    @Test
    @DisplayName("健康检查端点应白名单放行")
    void healthEndpoint_shouldBeWhitelisted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("liveness probe 应白名单放行")
    void livenessProbe_shouldBeWhitelisted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health/liveness");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("readiness probe 应白名单放行")
    void readinessProbe_shouldBeWhitelisted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("非 Actuator 路径应跳过过滤器")
    void nonActuatorPath_shouldSkipFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/workflow");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("空白 API Key 应返回 401")
    void blankApiKey_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("X-Actuator-Key", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(401, response.getStatus());
    }
}
