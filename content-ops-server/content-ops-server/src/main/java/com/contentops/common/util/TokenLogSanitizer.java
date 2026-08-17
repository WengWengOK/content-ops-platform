package com.contentops.common.util;

import java.util.regex.Pattern;

/**
 * Token 日志脱敏工具 — 防止 access_token / api_key 等敏感信息泄露到日志中。
 *
 * <p><b>P0 安全修复：</b>微信、快手等平台 API 要求 access_token 作为 URL 查询参数传递
 * （平台 API 限制），本工具在日志输出层面对 token 进行脱敏处理。
 *
 * <p>脱敏策略：
 * <ul>
 *   <li>{@code access_token=xxxxx} → {@code access_token=***}</li>
 *   <li>{@code api_key=xxxxx} → {@code api_key=***}</li>
 *   <li>{@code Authorization: Bearer xxxxx} → {@code Authorization: Bearer ***}</li>
 * </ul>
 */
public final class TokenLogSanitizer {

    private TokenLogSanitizer() {}

    /** 匹配 URL 查询参数中的 access_token / api_key / secret / password */
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(access_token|api_key|api-key|secret|password|app_secret|upload_token)" +
            "=([^&\\s\"']+)"
    );

    /** 匹配 Authorization Header 中的 Bearer token */
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)(Authorization:\\s*Bearer\\s+)([^\\s\"']+)"
    );

    /**
     * 对日志消息中的敏感 token 进行脱敏。
     *
     * @param message 原始日志消息
     * @return 脱敏后的消息，token 值被替换为 ***
     */
    public static String sanitize(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String result = TOKEN_PATTERN.matcher(message).replaceAll("$1=***");
        result = BEARER_PATTERN.matcher(result).replaceAll("$1***");
        return result;
    }
}
