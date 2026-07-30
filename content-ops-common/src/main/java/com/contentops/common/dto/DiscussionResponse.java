package com.contentops.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.util.List;

/**
 * Response from the DiscussionAgent during multi-turn topic ideation.
 *
 * <p>Wraps the AI's natural-language reply with session metadata so the
 * client can track the discussion phase and decide whether to finalize.
 *
 * <p>This supports the "把TRAE当讨论对象" (use TRAE as discussion partner) pattern:
 * the user chats iteratively, and the AI gradually moves from clarifying
 * questions to proposed directions to a structured topic plan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "讨论响应 — DiscussionAgent 的回复，包含 AI 回复文本、当前讨论阶段、澄清问题和建议方向")
public class DiscussionResponse {

    @Schema(description = "会话 ID（与 workflowId 相同，用于流水线集成）", example = "sess-001")
    private String sessionId;

    @Schema(description = "当前讨论阶段", example = "CLARIFICATION", allowableValues = {"IDEATION", "CLARIFICATION", "CONFIRMATION", "COMPLETED"})
    private DiscussionSession.DiscussionPhase phase;

    @Schema(description = "AI 的自然语言回复内容", example = "明白了，你想聚焦在职场新人成长。为了更精准地选题，请问你的目标受众年龄段是？")
    private String message;

    @Schema(description = "AI 提出的澄清问题列表（CLARIFICATION 阶段）")
    private List<String> clarifyingQuestions;

    @Schema(description = "建议的选题方向列表（CONFIRMATION 阶段）")
    private List<String> proposedDirections;

    @Schema(description = "是否可以调用 /finalize 结束讨论并获取 TopicPlanResult", example = "false")
    private boolean canFinalize;

    @Schema(description = "当前对话总轮次", example = "3")
    private int turnCount;
}
