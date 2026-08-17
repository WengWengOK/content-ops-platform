package com.contentops.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a multi-turn discussion session for exploratory topic ideation.
 *
 * <p>Supports the "把TRAE当讨论对象" (use TRAE as discussion partner) pattern:
 * user provides a fuzzy idea → AI asks clarifying questions → user confirms
 * direction → AI decomposes into a structured topic plan.
 *
 * <p>The discussion has 4 phases:
 * <ol>
 *   <li>IDEATION — user provides fuzzy idea, AI asks clarifying questions</li>
 *   <li>CLARIFICATION — user answers, AI proposes directions</li>
 *   <li>CONFIRMATION — user confirms a direction, AI decomposes</li>
 *   <li>COMPLETED — discussion finished, TopicPlanResult available</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "讨论会话 — 多轮对话式选题探索会话。用户通过对话与 AI 讨论模糊创意，逐步明确方向后生成结构化选题方案")
public class DiscussionSession {

    @Schema(description = "会话唯一标识（与 workflowId 相同，用于流水线集成）", example = "sess-001")
    private String sessionId;

    @Schema(description = "会话归属用户 ID（contentops.security.enabled=true 时用于数据隔离）")
    private String ownerId;

    @Schema(description = "关联的工作流 ID")
    private String workflowId;

    @Schema(description = "当前讨论阶段", example = "IDEATION", allowableValues = {"IDEATION", "CLARIFICATION", "CONFIRMATION", "COMPLETED"})
    private DiscussionPhase phase;

    @Schema(description = "用户提供的原始模糊创意描述", example = "我想写一篇关于职场新人成长的系列文章")
    private String fuzzyIdea;

    @Schema(description = "关联的账号画像信息")
    private TaskContext.AccountProfile accountProfile;

    @Schema(description = "对话轮次列表")
    private List<DiscussionTurn> turns;

    @Schema(description = "AI 提出的澄清问题列表")
    private List<String> clarifyingQuestions;

    @Schema(description = "澄清后 AI 提议的方向列表")
    private List<String> proposedDirections;

    @Schema(description = "用户确认的方向")
    private String confirmedDirection;

    @Schema(description = "最终选题方案（讨论完成后可用）")
    private TopicPlanResult topicPlanResult;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;

    public enum DiscussionPhase {
        IDEATION,
        CLARIFICATION,
        CONFIRMATION,
        COMPLETED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "对话轮次 — 单轮讨论消息记录")
    public static class DiscussionTurn {
        @Schema(description = "消息角色", example = "user", allowableValues = {"user", "assistant"})
        private String role;
        @Schema(description = "消息内容")
        private String content;
        @Schema(description = "消息时间戳")
        private LocalDateTime timestamp;
    }
}
