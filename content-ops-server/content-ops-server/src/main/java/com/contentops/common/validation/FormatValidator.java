package com.contentops.common.validation;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 格式校验器 — 按 {@link AgentStage} 定义必填字段 schema，校验输出齐全性与类型。
 *
 * <h3>校验规则</h3>
 * <ul>
 *   <li><b>必填字段</b>：每个 stage 有约定的核心字段（如 topic-planning 必须有 topics 列表），
 *       缺失即 BLOCK</li>
 *   <li><b>类型校验</b>：字段类型是否符合约定（topics 必须是 List，title 必须是 String）</li>
 *   <li><b>非空校验</b>：String 字段不能为空白，List/Map 不能为空</li>
 * </ul>
 *
 * <p>schema 定义采用静态 Map（启动时初始化），避免运行时反射开销。
 * 缺失 schema 的 stage 默认通过（向后兼容）。
 */
@Slf4j
@Component
public class FormatValidator implements AgentOutputValidator {

    /** 各 stage 的必填字段 schema：字段名 → 期望类型（String/List/Map/Number/Boolean） */
    private static final Map<AgentStage, Map<String, Class<?>>> SCHEMA;

    static {
        SCHEMA = Map.of(
                AgentStage.TOPIC_PLANNING, Map.of(
                        "topics", List.class
                ),
                AgentStage.CONTENT_CREATION, Map.of(
                        "title", String.class,
                        "content", String.class
                ),
                AgentStage.IMAGE_DESIGN, Map.of(
                        "images", List.class
                ),
                AgentStage.PUBLISHING, Map.of(
                        "publishedPlatforms", List.class
                ),
                AgentStage.DATA_ANALYSIS, Map.of(
                        "metrics", Map.class
                ),
                AgentStage.OPTIMIZATION, Map.of(
                        "suggestions", List.class
                )
        );
    }

    @Override
    public ValidationType type() {
        return ValidationType.FORMAT;
    }

    @Override
    public ValidationResult validate(AgentStage stage, Map<String, Object> data, TaskContext context) {
        Map<String, Class<?>> schema = SCHEMA.get(stage);
        if (schema == null || schema.isEmpty()) {
            // 无 schema 约束的 stage 默认通过
            return ValidationResult.pass(ValidationType.FORMAT);
        }

        List<String> failures = new ArrayList<>();
        Set<String> requiredFields = schema.keySet();

        for (Map.Entry<String, Class<?>> entry : schema.entrySet()) {
            String field = entry.getKey();
            Class<?> expectedType = entry.getValue();

            // 1. 必填字段存在性
            if (!data.containsKey(field)) {
                failures.add("必填字段缺失：" + field);
                continue;
            }

            Object value = data.get(field);
            // 2. 非空校验
            if (value == null) {
                failures.add("字段为 null：" + field);
                continue;
            }
            if (value instanceof String s && s.isBlank()) {
                failures.add("字段为空白：" + field);
                continue;
            }
            if (value instanceof List<?> l && l.isEmpty()) {
                failures.add("列表为空：" + field);
                continue;
            }
            if (value instanceof Map<?, ?> m && m.isEmpty()) {
                failures.add("Map 为空：" + field);
                continue;
            }

            // 3. 类型校验（List/Map 用 instanceof，其他用 isAssignableFrom）
            if (!expectedType.isInstance(value)) {
                failures.add(String.format("字段类型不符：%s 期望 %s，实际 %s",
                        field, expectedType.getSimpleName(), value.getClass().getSimpleName()));
            }
        }

        if (failures.isEmpty()) {
            log.debug("[FormatValidator] stage={} 通过，校验字段={}", stage.getCode(), requiredFields);
            return ValidationResult.pass(ValidationType.FORMAT);
        }

        log.warn("[FormatValidator] stage={} 格式校验失败：{}", stage.getCode(), failures);
        return ValidationResult.block(ValidationType.FORMAT, failures);
    }
}
