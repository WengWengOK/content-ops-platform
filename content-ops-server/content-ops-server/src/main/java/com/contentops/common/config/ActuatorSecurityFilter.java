package com.contentops.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Actuator 端点安全过滤器 — 基于预共享 API Key 的轻量鉴权。
 *
 * <p>P2 安全加固：在不引入 spring-boot-starter-security（避免全局安全链复杂度）的前提下，
 * 为 Actuator 端点提供最小化鉴权能力。
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>仅拦截 {@code /actuator/**} 路径的请求（通过 {@link #shouldNotFilter} 排除非 Actuator 请求）</li>
 *   <li>当 {@code contentops.actuator.security.enabled=true} 时，校验请求头中的 API Key</li>
 *   <li>校验通过 → 放行；校验失败 → 返回 401 Unauthorized</li>
 * </ol>
 *
 * <h3>部署架构</h3>
 * <p>生产环境下 Actuator 运行在独立端口 9090，配合本过滤器形成双重防护：
 * <ul>
 *   <li>网络层：防火墙仅允许内网访问 9090 端口</li>
 *   <li>应用层：本过滤器校验 API Key</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActuatorSecurityFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PATH_PREFIX = "/actuator";
    private static final Set<String> WHITELIST_PATHS = Set.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    );

    private final ActuatorSecurityProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                      HttpServletResponse response,
                                      FilterChain filterChain) throws ServletException, IOException {
        String requestPath = request.getRequestURI();

        // 健康检查端点白名单（K8s liveness/readiness probe 无需鉴权）
        if (isWhitelisted(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(properties.getHeaderName());

        // 兼容标准 Authorization: Bearer <key>（Prometheus scrape_configs 不支持自定义请求头，
        // 通过 authorization.type=Bearer 抓取 Actuator 指标时需要此兼容）。
        if (providedKey == null || providedKey.isBlank()) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
                providedKey = authHeader.substring(7).trim();
            }
        }

        if (providedKey == null || providedKey.isBlank()) {
            log.warn("[ActuatorSecurity] Missing API key header '{}' for path: {}",
                    properties.getHeaderName(), requestPath);
            sendUnauthorized(response, "Missing API key");
            return;
        }

        if (!providedKey.equals(properties.getApiKey())) {
            log.warn("[ActuatorSecurity] Invalid API key for path: {}", requestPath);
            sendUnauthorized(response, "Invalid API key");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 仅拦截 Actuator 端点请求，不影响业务 API。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(ACTUATOR_PATH_PREFIX);
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST_PATHS.contains(path);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"error\":\"" + message + "\"}");
    }
}
