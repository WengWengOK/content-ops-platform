package com.contentops.comment;

/**
 * 票据健康判定：把桥/平台返回的错误文案映射为「笔记票据过期」或「账号令牌失效」。
 *
 * <p>用途：采集失败时给监控项/凭据打标，前端据此提示「需要更新 xsec_token / AUTH_TOKEN」，
 * 而不是只丢一句看不懂的错误。
 */
public final class CommentTokenHealth {

    private CommentTokenHealth() {
    }

    /** 笔记级票据（xsec_token）问题：过期、笔记不可见、被删除等。 */
    public static boolean isNoteTokenExpired(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase();
        if (m.contains("xsec")) {
            return true;
        }
        return containsAny(m, "票据过期", "票据失效", "票据无效", "笔记不存在", "笔记已删除",
                "note not exist", "note_not_exist", "not found", "expired", "过期");
    }

    /** 账号级令牌（AUTH_TOKEN）问题：未授权、登录失效。 */
    public static boolean isAccountTokenInvalid(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase();
        return containsAny(m, "未授权", "鉴权失败", "unauthorized", "401", "invalid access token",
                "登录已失效", "not logged in", "未登录");
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}