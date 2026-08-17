package com.contentops.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户鉴权配置（P0，默认关闭以兼容无登录的前后端联调）。
 *
 * <p>开启方式：{@code CONTENTOPS_SECURITY_ENABLED=true}（或 {@code contentops.security.enabled=true}），
 * 生产环境必须通过环境变量注入 {@code CONTENTOPS_JWT_SECRET}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.security")
public class SecurityProperties {

    /** 是否启用业务 API 鉴权（false = 所有业务 API 开放，保持现状） */
    private boolean enabled = false;

    /** JWT HMAC-SHA256 签名密钥（生产必须更换并通过环境变量注入） */
    private String jwtSecret = "dev-only-secret-please-change-0123456789abcdef";

    /** Token 有效期（分钟） */
    private int jwtExpireMinutes = 120;
}
