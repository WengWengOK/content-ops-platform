package com.contentops.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色权限注解：标注在 Controller 方法/类上，由 {@link RoleAuthorizationInterceptor}
 * 在请求进入前校验。鉴权关闭（无 principal）时放行，便于开发模式。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    UserRole value();
}
