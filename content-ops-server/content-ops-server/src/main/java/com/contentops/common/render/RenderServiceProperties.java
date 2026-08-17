package com.contentops.common.render;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HTML→PNG 渲染服务配置（独立小服务，Playwright + Chromium 容器）。
 *
 * <p>开启后 {@code /workflow/{id}/download} 会把卡片/封面 HTML 批量渲染成 PNG 打进 ZIP；
 * 服务不可用或超时时自动降级为纯 HTML ZIP。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.render-service")
public class RenderServiceProperties {

    /** 是否启用 PNG 渲染（默认关闭，保持纯 HTML 导出兼容） */
    private boolean enabled = false;

    /** 渲染服务地址，如 http://localhost:3000 */
    private String url = "http://localhost:3000";

    /** 单次渲染请求超时（毫秒） */
    private long timeoutMs = 20000;
}
