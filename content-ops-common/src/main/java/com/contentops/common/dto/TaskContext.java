package com.contentops.common.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Represents a single content operations workflow task.
 * This object flows through the pipeline: Orchestrator → Agent → Orchestrator → next Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskContext {

    /** Unique workflow ID */
    private String workflowId;

    /** Current agent stage */
    private String currentStage;

    /**
     * 当前子阶段（渐进式生成）。
     *
     * <p>当 {@code currentStage} 是有子阶段的 AgentStage（如 CONTENT_CREATION、IMAGE_DESIGN）时，
     * 此字段标识当前执行到哪个子步骤，如 "outline" / "draft" / "styles" / "generate"。
     * 为 null 表示该阶段没有子阶段，或子阶段已全部完成。
     *
     * <p>参见 {@link com.contentops.common.enums.SubStage}
     */
    private String currentSubStage;

    /** Account/brand profile for the content */
    private AccountProfile accountProfile;

    /** Input parameters for the current stage */
    private Map<String, Object> inputs;

    /** Output artifacts from the current stage */
    private Map<String, Object> outputs;

    /** Artifacts accumulated from previous stages */
    private Map<String, Object> accumulatedArtifacts;

    /** Task status */
    private String status;

    /** Error message if failed */
    private String errorMessage;

    /** Timestamps */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Human review flag - if true, pause for human approval before next stage */
    private boolean requireHumanReview;

    /**
     * Conversation history for multi-turn agent interactions.
     *
     * <p><b>Note (P0):</b> This field is now backed by Redis ChatMemory at runtime.
     * LangChain4j's {@code @MemoryId} mechanism automatically loads and stores
     * conversation history in Redis (key: {@code contentops:chat-memory:{agentCode}:{workflowId}}).
     * This field is retained for:
     * <ul>
     *   <li>Initial seeding of conversation context when starting a workflow</li>
     *   <li>Audit/snapshot purposes (a copy of the conversation can be stored here)</li>
     *   <li>Backward compatibility with existing pipeline code</li>
     * </ul>
     * Do NOT manually append to this list during agent execution — ChatMemory handles that.
     */
    private java.util.List<ChatMessage> conversationHistory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountProfile {
        private String accountId;
        private String accountName;
        private String niche;          // e.g., "个人成长", "科技", "生活感悟"
        private String targetAudience; // e.g., "20-30岁年轻人"
        private String tone;           // e.g., "轻松、不要太说教"
        private java.util.List<String> platforms; // e.g., ["公众号", "小红书", "头条"]
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;    // "system", "user", "assistant"
        private String content;
    }
}
