package com.contentops.common.security;

import java.util.Optional;

/**
 * 当前请求的认证上下文（由 {@link AuthFilter} 填充，请求结束清除）。
 */
public final class AuthContext {

    private static final ThreadLocal<AuthPrincipal> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 当前登录用户（鉴权关闭或未登录时为 empty）。 */
    public record AuthPrincipal(String userId, String username, String role) {
    }

    public static void set(AuthPrincipal principal) {
        HOLDER.set(principal);
    }

    public static Optional<AuthPrincipal> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    /** 当前用户 ID；未登录/鉴权关闭时为 null。 */
    public static String currentUserId() {
        return current().map(AuthPrincipal::userId).orElse(null);
    }

    /** 当前用户角色；鉴权关闭/未登录时为 null（null 视为最高权限，便于开发模式） */
    public static String currentUserRole() {
        return current().map(AuthPrincipal::role).orElse(null);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
