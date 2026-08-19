package com.contentops.common.validation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 输出校验器配置。
 *
 * <p>对应 {@code contentops.validation.*} 配置块。
 *
 * <h3>降级策略（核心）</h3>
 * <ul>
 *   <li>第 1 次校验失败 → 同模型重生成（追加 validationFeedback 到 inputs）</li>
 *   <li>第 2 次校验失败 → 降级处理：标记 degraded=true，放行但记录警告</li>
 *   <li>即"2 次降级"= 最多 2 次重生成机会，第 3 次仍失败兜底放行（不阻断主流程）</li>
 * </ul>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.validation")
public class ValidationProperties {

    /** 总开关（默认 false，渐进开启） */
    private boolean enabled = false;

    /** 各类型校验器开关 */
    private Validators validators = new Validators();

    /** 降级策略配置 */
    private Degradation degradation = new Degradation();

    @Data
    public static class Validators {
        private boolean format = true;
        private boolean fact = true;
        private boolean consistency = true;
    }

    @Data
    public static class Degradation {
        /** 最大重生成次数（即"2 次降级"） */
        private int maxRegenerations = 2;

        /** 重试退避基础毫秒 */
        private long retryBackoffMs = 500;

        /** 退避倍率（指数退避） */
        private double retryBackoffMultiplier = 2.0;

        /** 达到上限后是否兜底放行（true=放行标记 degraded / false=阻断阶段失败） */
        private boolean fallbackPassThrough = true;
    }
}
