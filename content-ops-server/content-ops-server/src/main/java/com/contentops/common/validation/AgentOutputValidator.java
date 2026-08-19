package com.contentops.common.validation;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;

import java.util.Map;

/**
 * Agent 输出校验器接口 — 三类校验器（格式/事实/一致性）的统一契约。
 *
 * <p>每个实现类负责一个 {@link ValidationType}，由 {@link ValidationOrchestrator}
 * 按顺序串联调用。校验器应保持无状态、幂等、快速返回（避免阻塞主流程）。
 *
 * <h3>实现要求</h3>
 * <ol>
 *   <li><b>不抛异常</b>：内部异常应捕获并返回 {@link ValidationResult#warn}，
 *       避免单校验器故障阻断整个流程</li>
 *   <li><b>快速失败</b>：发现阻断级问题立即返回，不要继续校验后续字段</li>
 *   <li><b>可观测</b>：失败明细要具体到字段名和问题描述，供 Agent 重生成时参考</li>
 * </ol>
 */
public interface AgentOutputValidator {

    /**
     * 获取该校验器负责的校验类型。
     *
     * @return 校验类型（FORMAT / FACT / CONSISTENCY）
     */
    ValidationType type();

    /**
     * 校验 Agent 阶段输出。
     *
     * @param stage   当前阶段
     * @param data    Agent 输出数据（非 null）
     * @param context 工作流上下文（用于一致性校验时获取前序阶段产物）
     * @return 校验结果
     */
    ValidationResult validate(AgentStage stage, Map<String, Object> data, TaskContext context);
}
