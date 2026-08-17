package com.contentops.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 业务 API 鉴权过滤器（仅 {@code contentops.security.enabled=true} 时注册）。
 *
 * <p>校验 {@code Authorization: Bearer <token>}，失败返回 401；
 * 登录/注册/健康检查/Actuator/Swagger 等公共路径放行。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "contentops.security.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AuthContext.AuthPrincipal> principal = extractBearer(request)
                .flatMap(jwtService::parse);
        if (principal.isEmpty()) {
            log.warn("[Auth] 请求未通过鉴权: method={}, path={}", request.getMethod(), path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"未登录或令牌无效\"}");
            return;
        }

        AuthContext.set(principal.get());
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private Optional<String> extractBearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = auth.substring(7).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private boolean isPublicPath(String path) {
        // 仅登录/注册公开；/me、/users 等其余 auth 接口需鉴权
        return path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.startsWith("/api/v1/health")
                || path.startsWith("/api/v1/files/")
                || path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger")
                || path.startsWith("/h2-console")
                || path.equals("/error");
    }
}
