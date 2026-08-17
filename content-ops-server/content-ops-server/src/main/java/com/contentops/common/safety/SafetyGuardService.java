package com.contentops.common.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 综合安全守护服务。
 *
 * <p>整合 {@link PromptInjectionDetector}、{@link ContentSafetyFilter} 与 {@link OutputGuardrail}，
 * 提供两层防护，贯穿 LLM 调用的输入与输出：
 *
 * <h3>两层防护</h3>
 * <ul>
 *   <li><b>{@link #inputGuard(String)}</b>：在调用 LLM <b>前</b>检查用户输入，
 *       执行 Prompt 注入检测 + 内容安全过滤，阻止恶意/违规输入进入模型。</li>
 *   <li><b>{@link #outputGuard(String)}</b>：在返回结果<b>前</b>检查 LLM 输出，
 *       执行输出护栏（敏感泄露/有害建议/版权/幻觉/格式）+ 内容安全过滤（PII 脱敏），
 *       阻止不当内容触达用户。</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <p>当检测组件发生异常时，依据 {@link SafetyProperties#isFailOpen()} 决定行为：
 * <ul>
 *   <li>{@code failOpen=true}（放行）：可用性优先，记录告警后放行原始内容。</li>
 *   <li>{@code failOpen=false}（阻断，默认）：安全优先，判定不通过并阻断。</li>
 * </ul>
 *
 * <h3>风险分级</h3>
 * <p>{@link RiskLevel} 综合各子检测结果评定：
 * <ul>
 *   <li>{@link RiskLevel#CRITICAL}：高置信度 Prompt 注入或输出有害建议</li>
 *   <li>{@link RiskLevel#HIGH}：Prompt 注入或内容过滤未通过（敏感/有害）</li>
 *   <li>{@link RiskLevel#MEDIUM}：PII 泄露、版权风险、幻觉告警</li>
 *   <li>{@link RiskLevel#LOW}：无违规或仅轻微告警</li>
 * </ul>
 *
 * @see SafetyProperties
 * @see PromptInjectionDetector
 * @see ContentSafetyFilter
 * @see OutputGuardrail
 */
@Slf4j
@Service
public class SafetyGuardService {

    /** 注入置信度达到此值时升级为 CRITICAL。 */
    private static final double CRITICAL_INJECTION_CONFIDENCE = 0.8;

    private final SafetyProperties properties;
    private final PromptInjectionDetector injectionDetector;
    private final ContentSafetyFilter contentFilter;
    private final OutputGuardrail outputGuardrail;

    public SafetyGuardService(SafetyProperties properties,
                              PromptInjectionDetector injectionDetector,
                              ContentSafetyFilter contentFilter,
                              OutputGuardrail outputGuardrail) {
        this.properties = properties;
        this.injectionDetector = injectionDetector;
        this.contentFilter = contentFilter;
        this.outputGuardrail = outputGuardrail;
    }

    // ──────────────── 输入防护 ────────────────

    /**
     * 输入防护：在调用 LLM 前检查用户输入。
     *
     * <p>依次执行：
     * <ol>
     *   <li>Prompt 注入检测 → 若恶意则净化输入并记录违规</li>
     *   <li>内容安全过滤 → 对净化后输入执行敏感词/PII/有害内容检测</li>
     * </ol>
     *
     * @param input 用户原始输入
     * @return 安全检测结果（是否通过、违规列表、净化后内容、风险级别）
     */
    public SafetyResult inputGuard(String input) {
        if (!properties.isEnabled()) {
            return SafetyResult.passed(input);
        }

        List<String> violations = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        RiskLevel riskLevel = RiskLevel.LOW;
        String current = input == null ? "" : input;

        // 1. Prompt 注入检测
        if (properties.getInjection().isEnabled()) {
            try {
                PromptInjectionDetector.InjectionDetectionResult injection =
                        injectionDetector.detect(current);
                details.put("injection", injection);
                if (injection.isMalicious()) {
                    violations.add(String.format("Prompt注入(%s): 置信度%.0f%%, 命中%s",
                            injection.attackType(), injection.confidence() * 100,
                            injection.matchedPatterns()));
                    riskLevel = RiskLevel.max(riskLevel,
                            injection.confidence() >= CRITICAL_INJECTION_CONFIDENCE
                                    ? RiskLevel.CRITICAL : RiskLevel.HIGH);
                    current = injection.sanitizedContent();
                } else if (injection.hasSuspicion()) {
                    riskLevel = RiskLevel.max(riskLevel, RiskLevel.MEDIUM);
                }
            } catch (Exception e) {
                log.error("[SafetyGuard] 输入注入检测异常: {}", e.getMessage(), e);
                details.put("injectionError", e.getMessage());
                if (!properties.isFailOpen()) {
                    violations.add("输入注入检测异常(failClosed阻断)");
                    riskLevel = RiskLevel.HIGH;
                } else {
                    log.warn("[SafetyGuard] failOpen: 注入检测异常，放行输入");
                }
            }
        }

        // 2. 内容安全过滤
        if (properties.getContentFilter().isEnabled()) {
            try {
                ContentSafetyFilter.ContentFilterResult filterResult = contentFilter.filter(current);
                details.put("contentFilter", filterResult);
                if (!filterResult.passed()) {
                    violations.addAll(filterResult.violations());
                    riskLevel = RiskLevel.max(riskLevel, filterResult.riskScore() >= 50
                            ? RiskLevel.HIGH : RiskLevel.MEDIUM);
                } else if (filterResult.hasViolations()) {
                    riskLevel = RiskLevel.max(riskLevel, RiskLevel.LOW);
                }
                current = filterResult.sanitizedContent();
            } catch (Exception e) {
                log.error("[SafetyGuard] 输入内容过滤异常: {}", e.getMessage(), e);
                details.put("contentFilterError", e.getMessage());
                if (!properties.isFailOpen()) {
                    violations.add("输入内容过滤异常(failClosed阻断)");
                    riskLevel = RiskLevel.max(riskLevel, RiskLevel.HIGH);
                }
            }
        }

        boolean passed = violations.isEmpty();
        if (!passed && properties.isLogViolations()) {
            log.warn("[SafetyGuard] 输入未通过安全检查 riskLevel={}, violations={}", riskLevel, violations);
        }
        return new SafetyResult(passed, List.copyOf(violations), current, riskLevel, Map.copyOf(details));
    }

    // ──────────────── 输出防护 ────────────────

    /**
     * 输出防护：在返回结果前检查 LLM 输出。
     *
     * <p>依次执行：
     * <ol>
     *   <li>输出护栏 → 敏感泄露/有害建议/版权/幻觉/格式检查，净化泄露内容</li>
     *   <li>内容安全过滤 → 对输出执行 PII 脱敏与敏感词检测</li>
     * </ol>
     *
     * @param output LLM 原始输出
     * @return 安全检测结果（是否通过、违规列表、净化后内容、风险级别）
     */
    public SafetyResult outputGuard(String output) {
        if (!properties.isEnabled()) {
            return SafetyResult.passed(output);
        }

        List<String> violations = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        RiskLevel riskLevel = RiskLevel.LOW;
        String current = output == null ? "" : output;

        // 1. 输出护栏
        if (properties.getOutputGuard().isEnabled()) {
            try {
                OutputGuardrail.OutputGuardResult guardResult = outputGuardrail.check(current);
                details.put("outputGuard", guardResult);
                if (!guardResult.passed()) {
                    violations.addAll(guardResult.violations());
                    // 有害建议 → CRITICAL，其余 → MEDIUM/HIGH
                    if (guardResult.checks().getOrDefault("harmfulAdvice", true) == false) {
                        riskLevel = RiskLevel.max(riskLevel, RiskLevel.CRITICAL);
                    } else if (guardResult.checks().getOrDefault("sensitiveLeak", true) == false) {
                        riskLevel = RiskLevel.max(riskLevel, RiskLevel.HIGH);
                    } else {
                        riskLevel = RiskLevel.max(riskLevel, RiskLevel.MEDIUM);
                    }
                    current = guardResult.sanitizedContent();
                }
            } catch (Exception e) {
                log.error("[SafetyGuard] 输出护栏检查异常: {}", e.getMessage(), e);
                details.put("outputGuardError", e.getMessage());
                if (!properties.isFailOpen()) {
                    violations.add("输出护栏检查异常(failClosed阻断)");
                    riskLevel = RiskLevel.max(riskLevel, RiskLevel.HIGH);
                }
            }
        }

        // 2. 内容安全过滤（侧重 PII 脱敏）
        if (properties.getContentFilter().isEnabled()) {
            try {
                ContentSafetyFilter.ContentFilterResult filterResult = contentFilter.filter(current);
                details.put("contentFilter", filterResult);
                if (!filterResult.passed()) {
                    violations.addAll(filterResult.violations());
                    riskLevel = RiskLevel.max(riskLevel, filterResult.riskScore() >= 50
                            ? RiskLevel.HIGH : RiskLevel.MEDIUM);
                }
                current = filterResult.sanitizedContent();
            } catch (Exception e) {
                log.error("[SafetyGuard] 输出内容过滤异常: {}", e.getMessage(), e);
                details.put("contentFilterError", e.getMessage());
                if (!properties.isFailOpen()) {
                    violations.add("输出内容过滤异常(failClosed阻断)");
                    riskLevel = RiskLevel.max(riskLevel, RiskLevel.HIGH);
                }
            }
        }

        boolean passed = violations.isEmpty();
        if (!passed && properties.isLogViolations()) {
            log.warn("[SafetyGuard] 输出未通过安全检查 riskLevel={}, violations={}", riskLevel, violations);
        }
        return new SafetyResult(passed, List.copyOf(violations), current, riskLevel, Map.copyOf(details));
    }

    // ──────────────── 风险级别 ────────────────

    /** 风险级别（由低到高）。 */
    public enum RiskLevel {
        /** 低风险：无违规或仅轻微告警 */
        LOW(1),
        /** 中风险：PII 泄露、版权风险、幻觉告警 */
        MEDIUM(2),
        /** 高风险：注入攻击、敏感/有害内容 */
        HIGH(3),
        /** 严重风险：高置信度注入、有害建议 */
        CRITICAL(4);

        private final int severity;

        RiskLevel(int severity) {
            this.severity = severity;
        }

        /** 取两个风险级别中较高者。 */
        public static RiskLevel max(RiskLevel a, RiskLevel b) {
            if (a == null) {
                return b == null ? LOW : b;
            }
            if (b == null) {
                return a;
            }
            return a.severity >= b.severity ? a : b;
        }
    }

    // ──────────────── 结果类型 ────────────────

    /**
     * 综合安全检测结果。
     *
     * @param passed           是否通过安全检查（true 表示可放行）
     * @param violations       违规描述列表
     * @param sanitizedContent 净化后内容（注入净化 / PII 脱敏 / 敏感词打码）
     * @param riskLevel        综合风险级别
     * @param details          各子检测组件的详细结果（用于审计与调试）
     */
    public record SafetyResult(
            boolean passed,
            List<String> violations,
            String sanitizedContent,
            RiskLevel riskLevel,
            Map<String, Object> details
    ) {
        public SafetyResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
            riskLevel = riskLevel == null ? RiskLevel.LOW : riskLevel;
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        /** 快速构造「通过」结果（安全框架关闭或无违规时使用）。 */
        public static SafetyResult passed(String content) {
            return new SafetyResult(true, List.of(), content == null ? "" : content,
                    RiskLevel.LOW, Map.of());
        }

        /** 是否存在违规（即便最终通过）。 */
        public boolean hasViolations() {
            return !violations.isEmpty();
        }

        /** 是否为严重风险（需要阻断）。 */
        public boolean isCritical() {
            return riskLevel == RiskLevel.CRITICAL;
        }
    }
}
