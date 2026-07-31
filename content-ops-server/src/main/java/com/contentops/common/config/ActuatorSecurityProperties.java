package com.contentops.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Actuator 端点安全配置属性。
 *
 * <p>P2 安全加固：生产环境下 Actuator 端点运行在独立端口（9090），
 * 但仍需鉴权防止未授权访问。本属性控制 Actuator 端点的 API Key 认证行为。
 *
 * <p>配置示例（application-prod.yml）：
 * <pre>{@code
 * contentops:
 *   actuator:
 *     security:
 *       enabled: true
 *       api-key: ${ACTUATOR_API_KEY:?ACTUATOR_API_KEY is required}
 *       header-name: X-Actuator-Key
 * }</pre>
 *
 * <p>禁用时（开发/测试环境），Actuator 端点无需鉴权即可访问。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.actuator.security")
public class ActuatorSecurityProperties {

    /** 是否启用 Actuator 端点鉴权（生产环境必须为 true） */
    private boolean enabled = false;

    /** Actuator API Key（生产环境必须通过环境变量注入，不得硬编码） */
    private String apiKey;

    /** API Key 传递的 HTTP Header 名称 */
    private String headerName = "X-Actuator-Key";
}
