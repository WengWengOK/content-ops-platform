package com.contentops.common.quality;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 质量阈值配置属性（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>通过 {@code contentops.quality.*} 在 application.yml 中绑定，控制质量评估的
 * 最低阈值和自动重试行为。
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>{@code
 * contentops:
 *   quality:
 *     enabled: true
 *     min-score: 60
 *     auto-retry: true
 *     max-retries: 2
 * }</pre>
 *
 * @see QualityAssessmentService
 * @see AutoRetryService
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.quality")
public class QualityThresholdProperties {

    /** 是否启用质量评估（关闭时跳过质量评分检查） */
    private boolean enabled = true;

    /** 最低质量阈值（0-100），总分低于此值时触发自动重试 */
    private int minScore = 60;

    /** 是否启用低分自动重试 */
    private boolean autoRetry = true;

    /** 自动重试最大次数（不含首次调用，即总共最多调用 maxRetries+1 次） */
    private int maxRetries = 2;
}
