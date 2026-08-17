package com.contentops.common.observability;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 一次真实 LLM 调用的追踪记录（token / 延迟 / 状态）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "LLM 调用追踪")
public class LlmTrace {

    @Schema(description = "追踪 ID")
    private String traceId;

    @Schema(description = "工作流 ID（非工作流内调用可为空）")
    private String workflowId;

    @Schema(description = "阶段编码，如 topic-planning / publishing / trend-analysis")
    private String stage;

    @Schema(description = "Agent 编码")
    private String agent;

    @Schema(description = "模型名，如 deepseek-chat")
    private String model;

    @Schema(description = "输入 token 数")
    private Long tokensIn;

    @Schema(description = "输出 token 数")
    private Long tokensOut;

    @Schema(description = "输入文本字符数（估算）")
    private Integer promptChars;

    @Schema(description = "输出文本字符数（估算）")
    private Integer outputChars;

    @Schema(description = "调用耗时（毫秒）")
    private Long latencyMs;

    @Schema(description = "状态：success / error")
    private String status;

    @Schema(description = "错误信息（成功时为空）")
    private String errorMessage;

    @Schema(description = "调用时间")
    private LocalDateTime createdAt;

    @Schema(description = "OpenTelemetry Trace ID（关联分布式追踪）")
    private String otelTraceId;

    @Schema(description = "OpenTelemetry Span ID（本次 LLM 调用 span）")
    private String otelSpanId;
}
