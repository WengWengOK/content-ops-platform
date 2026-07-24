package com.contentops.common.dto;

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
public class DiscussionSession {

    /** Unique session ID (same as workflowId for pipeline integration) */
    private String sessionId;

    /** Associated workflow ID */
    private String workflowId;

    /** Current discussion phase */
    private DiscussionPhase phase;

    /** The original fuzzy idea from the user */
    private String fuzzyIdea;

    /** Account profile for context */
    private TaskContext.AccountProfile accountProfile;

    /** Conversation turns */
    private List<DiscussionTurn> turns;

    /** Clarifying questions raised by AI */
    private List<String> clarifyingQuestions;

    /** Proposed directions after clarification */
    private List<String> proposedDirections;

    /** User-confirmed direction */
    private String confirmedDirection;

    /** Final topic plan result (when COMPLETED) */
    private TopicPlanResult topicPlanResult;

    /** Timestamps */
    private LocalDateTime createdAt;
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
    public static class DiscussionTurn {
        private String role;       // "user" or "assistant"
        private String content;
        private LocalDateTime timestamp;
    }
}
