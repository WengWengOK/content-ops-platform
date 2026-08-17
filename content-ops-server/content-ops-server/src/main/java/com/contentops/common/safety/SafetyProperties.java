package com.contentops.common.safety;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 安全与合规框架配置属性。
 *
 * <p>通过 {@code contentops.safety.*} 在 application.yml 中绑定，统一控制
 * Prompt 注入检测、内容安全过滤、输出护栏与 PII 检测脱敏的行为参数。
 *
 * <h3>设计理念</h3>
 * <ul>
 *   <li><b>failOpen / failClosed</b>：检测组件发生异常时，{@code failOpen=true} 放行（可用性优先），
 *       {@code failOpen=false} 阻断（安全优先）。默认 failClosed（安全优先）。</li>
 *   <li><b>分级过滤</b>：内容安全过滤支持 STRICT / MODERATE / LENIENT 三级，控制敏感词与有害内容检测的严格程度。</li>
 *   <li><b>可配置开关与阈值</b>：每个检查项均可独立开关并配置阈值，便于灰度与调优。</li>
 * </ul>
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>{@code
 * contentops:
 *   safety:
 *     enabled: true
 *     fail-open: false              # 检测异常时是否放行（true=放行/可用性优先，false=阻断/安全优先）
 *     log-violations: true          # 是否记录违规日志
 *     injection:
 *       enabled: true
 *       confidence-threshold: 0.6   # 置信度阈值，高于此值判定为恶意注入
 *       detect-encoded: true        # 是否检测编码绕过
 *     content-filter:
 *       enabled: true
 *       level: MODERATE             # STRICT / MODERATE / LENIENT
 *       redact-pii: true            # 是否对 PII 进行脱敏
 *       custom-sensitive-words:     # 自定义敏感词库（按分类）
 *         政治: ["xxx"]
 *         广告: ["加微信"]
 *     output-guard:
 *       enabled: true
 *       check-sensitive-leak: true
 *       check-harmful-advice: true
 *       check-copyright: true
 *       copyright-similarity-threshold: 0.8
 *       check-hallucination: true
 *       check-format: true
 *     pii:
 *       enabled: true
 *       detect-phone: true
 *       detect-id-card: true
 *       detect-email: true
 *       detect-bank-card: true
 *       detect-ip: true
 *       detect-wechat: true
 * }</pre>
 *
 * @see PromptInjectionDetector
 * @see ContentSafetyFilter
 * @see OutputGuardrail
 * @see PiiDetector
 * @see SafetyGuardService
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.safety")
public class SafetyProperties {

    /** 是否启用整个安全框架（关闭时所有检测放行）。 */
    private boolean enabled = true;

    /**
     * 检测失败（组件异常）时的降级策略。
     * <p>{@code true} = failOpen（放行，可用性优先）；{@code false} = failClosed（阻断，安全优先）。
     * 默认 {@code false}，即安全优先。
     */
    private boolean failOpen = false;

    /** 是否记录违规检测日志（含违规内容摘要，生产环境注意脱敏）。 */
    private boolean logViolations = true;

    /** Prompt 注入检测配置。 */
    private InjectionConfig injection = new InjectionConfig();

    /** 内容安全过滤配置。 */
    private ContentFilterConfig contentFilter = new ContentFilterConfig();

    /** 输出护栏配置。 */
    private OutputGuardConfig outputGuard = new OutputGuardConfig();

    /** PII 检测与脱敏配置。 */
    private PiiConfig pii = new PiiConfig();

    /**
     * Prompt 注入检测配置。
     */
    @Data
    public static class InjectionConfig {
        /** 是否启用 Prompt 注入检测。 */
        private boolean enabled = true;

        /** 置信度阈值（0.0-1.0），综合命中数与模式严重度计算，高于此值判定为恶意注入。 */
        private double confidenceThreshold = 0.6;

        /** 是否检测编码绕过（Base64 / Unicode / URL 编码）。 */
        private boolean detectEncoded = true;

        /** 是否对检测到的注入内容进行净化（移除/中和攻击模式）。 */
        private boolean sanitize = true;
    }

    /**
     * 内容安全过滤配置。
     */
    @Data
    public static class ContentFilterConfig {
        /** 是否启用内容安全过滤。 */
        private boolean enabled = true;

        /** 过滤级别：STRICT（最严格）、MODERATE（适中，默认）、LENIENT（宽松）。 */
        private String level = "MODERATE";

        /** 是否对检测到的 PII 进行脱敏。 */
        private boolean redactPii = true;

        /** 是否检测敏感词（政治、色情、暴力、广告）。 */
        private boolean checkSensitiveWords = true;

        /** 是否检测有害内容（自我伤害、仇恨言论、非法活动）。 */
        private boolean checkHarmfulContent = true;

        /**
         * 自定义敏感词库，按分类组织。
         * <p>key 为分类名（如「政治」「色情」「暴力」「广告」），value 为该分类的敏感词列表。
         * 自定义词会与内置默认词库合并。
         */
        private Map<String, List<String>> customSensitiveWords = new HashMap<>();

        /**
         * 自定义有害内容关键词，按分类组织。
         * <p>key 为分类名（如「自我伤害」「仇恨言论」「非法活动」），value 为关键词列表。
         */
        private Map<String, List<String>> customHarmfulWords = new HashMap<>();

        /** 敏感词命中数量达到此阈值时判定为违规（级别越严格阈值越低）。 */
        private int sensitiveWordThreshold = 1;
    }

    /**
     * 输出护栏配置（每项检查可独立开关并配置阈值）。
     */
    @Data
    public static class OutputGuardConfig {
        /** 是否启用输出护栏。 */
        private boolean enabled = true;

        /** 是否检查敏感信息泄露（PII、密钥、系统提示词泄露）。 */
        private boolean checkSensitiveLeak = true;

        /** 是否检查有害建议（武器、毒品、非法活动指导）。 */
        private boolean checkHarmfulAdvice = true;

        /** 是否检查版权风险（与已知受版权保护文本的相似度）。 */
        private boolean checkCopyright = true;

        /** 版权相似度阈值（0.0-1.0），高于此值判定为版权风险。 */
        private double copyrightSimilarityThreshold = 0.8;

        /** 是否检查幻觉（无依据的事实声明）。 */
        private boolean checkHallucination = true;

        /** 幻觉检测：无来源引用的事实声明数量阈值，超过即告警。 */
        private int hallucinationClaimThreshold = 3;

        /** 是否检查输出格式（是否符合预期格式）。 */
        private boolean checkFormat = true;

        /** 预期输出格式：json / markdown / plain / none（不校验）。 */
        private String expectedFormat = "none";

        /** 已知受版权保护文本片段列表，用于版权相似度比对。 */
        private List<String> copyrightedTexts = new ArrayList<>();
    }

    /**
     * PII 检测与脱敏配置。
     */
    @Data
    public static class PiiConfig {
        /** 是否启用 PII 检测。 */
        private boolean enabled = true;

        /** 是否检测中国手机号。 */
        private boolean detectPhone = true;

        /** 是否检测身份证号（18 位，含校验位验证）。 */
        private boolean detectIdCard = true;

        /** 是否检测邮箱地址。 */
        private boolean detectEmail = true;

        /** 是否检测银行卡号（16-19 位）。 */
        private boolean detectBankCard = true;

        /** 是否检测 IP 地址。 */
        private boolean detectIp = true;

        /** 是否检测微信号。 */
        private boolean detectWechat = true;

        /** 脱敏掩码字符。 */
        private String maskChar = "*";
    }
}
