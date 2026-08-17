package com.contentops.common.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

/**
 * Agent 元数据描述（控制面）：声明 Agent 的能力、工具、阶段、人机协同与成本档位。
 * 大厂多 Agent 平台以注册表驱动编排，而非把 Agent 硬编码在流程里。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 元数据描述")
public class AgentDescriptor {

    @Schema(description = "Agent 编码，如 topic-planning")
    private String code;

    @Schema(description = "Agent 名称")
    private String name;

    @Schema(description = "职责说明")
    private String description;

    @Schema(description = "所属流水线阶段（topic-planning/content-creation/image-design/publishing/...）")
    private String stage;

    @Schema(description = "能力标签")
    private List<String> capabilities;

    @Schema(description = "可调用工具")
    private List<String> tools;

    @Schema(description = "模型档位：creative / formatting / streaming")
    private String modelTier;

    @Schema(description = "是否需要人工确认（human-in-the-loop）")
    private boolean humanInLoop;

    @Schema(description = "是否支持流式输出（SSE）")
    private boolean streaming;

    @Schema(description = "是否启用质量门禁（质量评分/清单）")
    private boolean qualityGate;

    @Schema(description = "独立服务模式可用（分析/优化拆分为独立服务）")
    private boolean standaloneService;
}
