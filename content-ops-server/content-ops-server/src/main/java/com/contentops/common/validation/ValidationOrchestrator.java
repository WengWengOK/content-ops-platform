package com.contentops.common.validation;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 校验编排器 — 串联三类校验器（格式→事实→一致性），输出综合校验结果。
 *
 * <h3>执行顺序</h3>
 * <ol>
 *   <li>{@link FormatValidator} — 格式校验（最快，先跑）</li>
 *   <li>{@link FactValidator} — 事实校验（规则匹配）</li>
 *   <li>{@link ConsistencyValidator} — 一致性校验（跨阶段对齐）</li>
 * </ol>
 *
 * <h3>短路策略</h3>
 * <p>格式校验 BLOCK 时直接返回，不再跑后续校验（格式都不对，事实/一致性无意义）。
 * WARN 级失败不短路，继续后续校验。
 *
 * <h3>综合结果</h3>
 * <ul>
 *   <li>任一校验器返回 BLOCK → 综合 passed=false</li>
 *   <li>全部通过或仅 WARN → 综合 passed=true，failures 汇总所有 WARN 明细</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidationOrchestrator {

    private final FormatValidator formatValidator;
    private final FactValidator factValidator;
    private final ConsistencyValidator consistencyValidator;
    private final ValidationProperties properties;

    /**
     * 对 Agent 阶段输出执行全量校验。
     *
     * @param stage   当前阶段
     * @param data    Agent 输出数据
     * @param context 工作流上下文
     * @return 综合校验结果（null 表示校验未启用或数据为空，调用方应视为通过）
     */
    public ValidationResult validate(AgentStage stage, Map<String, Object> data, TaskContext context) {
        if (!properties.isEnabled()) {
            return null;
        }
        if (data == null || data.isEmpty()) {
            return null;
        }

        List<ValidationResult> results = new ArrayList<>(3);
        ValidationProperties.Validators vProps = properties.getValidators();

        // 1. 格式校验（短路：BLOCK 时直接返回）
        if (vProps.isFormat()) {
            ValidationResult r = safeValidate(formatValidator, stage, data, context);
            results.add(r);
            if (r != null && !r.isPassed() && "BLOCK".equals(r.getSeverity())) {
                return aggregate(results);
            }
        }

        // 2. 事实校验
        if (vProps.isFact()) {
            ValidationResult r = safeValidate(factValidator, stage, data, context);
            results.add(r);
            if (r != null && !r.isPassed() && "BLOCK".equals(r.getSeverity())) {
                return aggregate(results);
            }
        }

        // 3. 一致性校验
        if (vProps.isConsistency()) {
            ValidationResult r = safeValidate(consistencyValidator, stage, data, context);
            results.add(r);
        }

        return aggregate(results);
    }

    /** 安全调用校验器：捕获异常，避免单校验器故障阻断整个流程 */
    private ValidationResult safeValidate(AgentOutputValidator validator,
                                           AgentStage stage, Map<String, Object> data, TaskContext context) {
        try {
            return validator.validate(stage, data, context);
        } catch (Exception e) {
            log.warn("[ValidationOrchestrator] {} 校验异常，降级为 WARN：{}",
                    validator.type(), e.getMessage());
            return ValidationResult.warn(validator.type(),
                    List.of(validator.type() + " 校验器异常：" + e.getMessage()));
        }
    }

    /** 汇总多个校验结果为综合结果 */
    private ValidationResult aggregate(List<ValidationResult> results) {
        List<String> allFailures = new ArrayList<>();
        boolean anyBlocked = false;
        List<String> typeStatus = new ArrayList<>();

        for (ValidationResult r : results) {
            if (r == null) continue;
            if (!r.isPassed()) {
                anyBlocked = true;
                allFailures.addAll(r.getFailures());
            } else if (r.getFailures() != null && !r.getFailures().isEmpty()) {
                // WARN 级失败也收集（passed=true 但 failures 非空）
                allFailures.addAll(r.getFailures());
            }
            typeStatus.add(r.getType() + ":" + ("BLOCK".equals(r.getSeverity()) ? "FAIL" :
                    "WARN".equals(r.getSeverity()) ? "WARN" : "PASS"));
        }

        return ValidationResult.builder()
                .type(null) // 综合结果，无单一类型
                .passed(!anyBlocked)
                .severity(anyBlocked ? "BLOCK" : (allFailures.isEmpty() ? "NONE" : "WARN"))
                .failures(allFailures)
                .summary("综合校验：" + String.join(", ", typeStatus) +
                        (anyBlocked ? "（阻断）" : (allFailures.isEmpty() ? "（通过）" : "（警告）")))
                .build();
    }
}
