package com.contentops.common.dto;

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
public class DiscussionResponse {

    /** Session ID (same as workflowId for pipeline integration) */
    private String sessionId;

    /** Current discussion phase */
    private DiscussionSession.DiscussionPhase phase;

    /** The AI's natural-language reply */
    private String message;

    /** Clarifying questions raised by the AI (if in CLARIFICATION phase) */
    private List<String> clarifyingQuestions;

    /** Proposed directions (if in CONFIRMATION phase) */
    private List<String> proposedDirections;

    /** Whether the user can now call /finalize to get a TopicPlanResult */
    private boolean canFinalize;

    /** Total conversation turns so far */
    private int turnCount;
}
