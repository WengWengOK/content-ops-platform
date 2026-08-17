package com.contentops.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import lombok.RequiredArgsConstructor;

/**
 * 角色权限拦截器：校验 {@link RequireRole} 注解。
 *
 * <p>规则：ADMIN 可访问任意标注接口；未登录/鉴权关闭（role=null）时放行（开发模式）；
 * 角色不满足返回 403。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    private final SecurityProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }
        // 鉴权关闭：放行（开发模式）
        if (!properties.isEnabled()) {
            return true;
        }
        String role = AuthContext.currentUserRole();
        if (role == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            try {
                response.getWriter().write("{\"success\":false,\"message\":\"未登录或令牌无效\"}");
            } catch (Exception ignored) {
                // ignore
            }
            return false;
        }
        if (UserRole.ADMIN.name().equals(role)) {
            return true;
        }
        if (requireRole.value().name().equals(role)) {
            return true;
        }
        log.warn("[RBAC] 权限不足: path={}, required={}, actual={}",
                request.getRequestURI(), requireRole.value(), role);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write("{\"success\":false,\"message\":\"权限不足，需要角色 "
                    + requireRole.value() + "\"}");
        } catch (Exception ignored) {
            // ignore
        }
        return false;
    }
}
