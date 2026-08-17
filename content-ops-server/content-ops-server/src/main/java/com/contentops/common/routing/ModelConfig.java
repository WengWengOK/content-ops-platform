package com.contentops.common.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型配置 DTO —— 描述某个 Agent 阶段应使用的 LLM 模型参数。
 *
 * <p>由 {@link ModelRoutingService#getModelConfig} 返回，供各 Agent 模块在
 * 通过 {@code AiServices.builder()} 构建 AI Service 时读取，以决定使用哪个
 * 模型名称、采样温度和最大 token 数。
 *
 * <p><b>路由策略：</b>
 * <ul>
 *   <li>创意类任务（选题策划、内容创作、配图设计）→ 高温度大模型（gpt-4o, temp 0.8+）</li>
 *   <li>格式化类任务（排版发布、数据分析、优化迭代）→ 低温度模型（gpt-4o-mini, temp 0.3-）</li>
 * </ul>
 *
 * @see ModelRoutingService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig {

    /** 模型名称（如 gpt-4o、gpt-4o-mini） */
    private String modelName;

    /** 采样温度（0.0 - 2.0），越高越有创造性，越低越确定 */
    private double temperature;

    /** 最大输出 token 数 */
    private int maxTokens;

    /** 是否为创意类任务（true 时使用高温度，false 时使用低温度） */
    private boolean creative;

    /** 模型提供商预留字段（如 openai、azure-openai），便于后续扩展多供应商路由 */
    private String provider;
}
