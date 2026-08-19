package com.contentops.common.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次校验结果 DTO。
 *
 * <p>由 {@link AgentOutputValidator#validate} 返回，描述某一维度（格式/事实/一致性）
 * 的校验结论：通过与否、失败明细、严重等级。
 *
 * <h3>严重等级（severity）</h3>
 * <ul>
 *   <li><b>BLOCK</b>：阻断级，必须打回重生成（如必填字段缺失、事实明显错误）</li>
 *   <li><b>WARN</b>：警告级，记录但不阻断（如可选字段缺失、轻度不一致）</li>
 * </ul>
 *
 * <p>降级策略只对 BLOCK 级失败触发重生成；WARN 级失败仅记录到上下文供后续参考。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult implements Serializable {

    /** 校验类型 */
    private ValidationType type;

    /** 是否通过（BLOCK 级失败 → false；WARN 级失败 → true 但 failures 非空） */
    private boolean passed;

    /** 严重等级：BLOCK / WARN */
    private String severity;

    /** 失败明细（每条一句话描述，供 Agent 重生成时参考） */
    @Builder.Default
    private List<String> failures = new ArrayList<>();

    /** 通过/失败的简要摘要（用于日志和上下文） */
    private String summary;

    /** 快速构造 BLOCK 级失败结果 */
    public static ValidationResult block(ValidationType type, List<String> failures) {
        return ValidationResult.builder()
                .type(type)
                .passed(false)
                .severity("BLOCK")
                .failures(failures)
                .summary(type + " 校验失败（阻断）：" + String.join("; ", failures))
                .build();
    }

    /** 快速构造 WARN 级失败结果（passed=true，不阻断） */
    public static ValidationResult warn(ValidationType type, List<String> warnings) {
        return ValidationResult.builder()
                .type(type)
                .passed(true)
                .severity("WARN")
                .failures(warnings)
                .summary(type + " 校验警告：" + String.join("; ", warnings))
                .build();
    }

    /** 快速构造通过结果 */
    public static ValidationResult pass(ValidationType type) {
        return ValidationResult.builder()
                .type(type)
                .passed(true)
                .severity("NONE")
                .summary(type + " 校验通过")
                .build();
    }
}
