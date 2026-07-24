package com.contentops.common.dto;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
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

    // ==================== 循环优化控制字段（A计划） ====================

    /**
     * 当前循环轮次（从1开始）。
     *
     * <p>表示当前正在执行第几轮「选题→内容→配图→发布→分析→优化」全流程。
     * 当 OPTIMIZATION 阶段完成后，如果未达到 {@link #maxCycles}，
     * 则 cycleCount++ 并回到 TOPIC_PLANNING 开始新一轮。
     * 值为 0 表示尚未开始循环（初始状态）。
     */
    private int cycleCount;

    /**
     * 最大循环次数（默认3轮）。
     *
     * <p>达到此值后，OPTIMIZATION 阶段不再自动回到 TOPIC_PLANNING，
     * 工作流标记为 COMPLETED。
     */
    private int maxCycles = 3;

    /**
     * 每轮循环的产物快照列表。
     *
     * <p>key 为 "cycle-{N}"（如 "cycle-1"），value 为该轮结束时 accumulatedArtifacts 的深拷贝。
     * 用于跨周期产物隔离，防止第 N 轮覆盖第 N-1 轮数据，
     * 同时让 AnalysisAgent 和 OptimizeAgent 能访问历史轮次的产出进行对比分析。
     */
    private Map<String, Object> cycleHistory;

    /**
     * 上一轮优化反馈，注入到下一轮的 TOPIC_PLANNING 输入中。
     *
     * <p>由 OptimizeAgent 产出，包含对选题方向、内容策略、配图风格等的改进建议。
     * 新一轮开始时，此字段会被合并到 {@link #inputs} 中供各 Agent 读取。
     */
    private String lastOptimizationFeedback;

    // ==================== 循环控制辅助方法（A计划） ====================

    /**
     * 判断是否还有剩余循环次数。
     *
     * @return true 如果当前 cycleCount < maxCycles，可以继续下一轮
     */
    public boolean hasRemainingCycles() {
        return cycleCount < maxCycles;
    }

    /**
     * 快照当前轮次的 accumulatedArtifacts 到 cycleHistory。
     *
     * <p>在 OPTIMIZATION 阶段完成后、新一轮开始前调用，
     * 将当前轮次的全部产物保存到 "cycle-{N}" 键下，实现跨周期产物隔离。
     */
    @SuppressWarnings("unchecked")
    public void snapshotCurrentCycle() {
        if (cycleHistory == null) {
            cycleHistory = new java.util.HashMap<>();
        }
        String cycleKey = "cycle-" + cycleCount;
        if (accumulatedArtifacts != null) {
            // 深拷贝当前轮次的产物
            cycleHistory.put(cycleKey, new java.util.HashMap<>(accumulatedArtifacts));
        }
    }

    /**
     * 开始新一轮循环：递增 cycleCount，重置阶段状态，注入优化反馈。
     *
     * <p>调用此方法前应先调用 {@link #snapshotCurrentCycle()} 保存当前轮次产物。
     */
    public void startNewCycle() {
        snapshotCurrentCycle();
        cycleCount++;
        currentStage = AgentStage.TOPIC_PLANNING.getCode();
        currentSubStage = null;
        status = TaskStatus.PENDING.name();
        updatedAt = LocalDateTime.now();

        // 将上一轮优化反馈注入到 inputs 中
        if (lastOptimizationFeedback != null && !lastOptimizationFeedback.isBlank()) {
            if (inputs == null) {
                inputs = new java.util.HashMap<>();
            }
            inputs.put("optimizationFeedback", lastOptimizationFeedback);
            inputs.put("cycleNumber", cycleCount);
        }
    }

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

        /**
         * 个人经历/真实素材注入位（P1: Prompt 工程深度优化）。
         *
         * <p>创作者可在请求中传入自己的真实经历、数据或案例，ContentAgent 会将其
         * 注入到 {{personalExperience}} 模板变量中，使生成内容更具个人色彩和真实感，
         * 而非空泛说教。为 null 时表示不注入个人经历。
         */
        private String personalExperience;
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
