package com.contentops.common.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A2A（Agent-to-Agent）消息信封：Agent 间通信的统一契约。
 *
 * <p>当前单体模式下 Agent 进程内直调；本信封定义标准事件结构，
 * 迁移到消息队列/Kafka 或独立 Agent 服务时，按此契约透传即可。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A2A Agent 消息信封")
public class AgentMessageEnvelope {

    @Schema(description = "消息 ID（幂等去重）")
    private String messageId;

    @Schema(description = "工作流 ID")
    private String workflowId;

    @Schema(description = "来源 Agent（如 topic-planning）")
    private String fromAgent;

    @Schema(description = "目标 Agent（如 content-creation；广播时为 *）")
    private String toAgent;

    @Schema(description = "事件类型：STAGE_STARTED/STAGE_COMPLETED/STAGE_FAILED/TOOL_CALLED/HUMAN_REVIEW_REQUESTED")
    private String eventType;

    @Schema(description = "业务载荷（阶段产物摘要 / 工具参数 / 审批请求等）")
    private Map<String, Object> payload;

    @Schema(description = "链路追踪 ID（关联 LLM trace）")
    private String traceId;

    @Schema(description = "时间戳")
    private LocalDateTime timestamp;
}
