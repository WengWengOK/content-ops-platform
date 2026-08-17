package com.contentops.common.dto;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "工作流任务上下文 — 贯穿整个流水线的核心状态对象，包含工作流 ID、当前阶段、账号画像、输入输出、累积产物、循环控制等信息")
public class TaskContext {

    @Schema(description = "工作流唯一标识（UUID 格式）", example = "550e8400-e29b-41d4-a716-446655440000")
    private String workflowId;

    @Schema(description = "工作流归属用户 ID（contentops.security.enabled=true 时用于数据隔离，未登录/关闭鉴权时为 null）")
    private String ownerId;

    @Schema(description = "产生该工作流的讨论会话 ID（用于作品与对话的归属关系，用户可基于该会话继续聊天修改作品）")
    private String discussionSessionId;

    @Schema(description = "当前流水线阶段编码，对应 AgentStage 枚举", example = "TOPIC_PLANNING", allowableValues = {"TOPIC_PLANNING", "CONTENT_CREATION", "IMAGE_DESIGN", "PUBLISHING", "ANALYSIS", "OPTIMIZATION"})
    private String currentStage;

    @Schema(description = "当前子阶段（渐进式生成），如 'outline' / 'draft' / 'styles' / 'generate'。为 null 表示无子阶段或已完成。参见 SubStage 枚举", example = "outline")
    private String currentSubStage;

    @Schema(description = "账号画像信息，包含账号名称、定位领域、目标受众、语气风格、发布平台等")
    private AccountProfile accountProfile;

    @Schema(description = "当前阶段的输入参数，键值对形式")
    private Map<String, Object> inputs;

    @Schema(description = "当前阶段的输出产物，键值对形式")
    private Map<String, Object> outputs;

    @Schema(description = "前序阶段累积的产物，key 为阶段编码，value 为该阶段的产出")
    private Map<String, Object> accumulatedArtifacts;

    @Schema(description = "任务状态", example = "PENDING", allowableValues = {"PENDING", "PROCESSING", "WAITING_FOR_REVIEW", "COMPLETED", "FAILED"})
    private String status;

    @Schema(description = "失败时的错误信息")
    private String errorMessage;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "是否需要人工审核 — 为 true 时每个阶段完成后暂停等待审批", example = "false")
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
    @Schema(description = "当前循环轮次（从1开始）。表示正在执行第几轮全流程优化。达到 maxCycles 后工作流标记为 COMPLETED。", example = "1", minimum = "0")
    private int cycleCount;

    /**
     * 最大循环次数（默认3轮）。
     *
     * <p>达到此值后，OPTIMIZATION 阶段不再自动回到 TOPIC_PLANNING，
     * 工作流标记为 COMPLETED。
     */
    @Schema(description = "最大循环次数（默认3轮）。达到此值后工作流标记为 COMPLETED，不再自动回到选题阶段。", example = "3", defaultValue = "3", minimum = "1")
    @Builder.Default
    private int maxCycles = 3;

    /**
     * 每轮循环的产物快照列表。
     *
     * <p>key 为 "cycle-{N}"（如 "cycle-1"），value 为该轮结束时 accumulatedArtifacts 的深拷贝。
     * 用于跨周期产物隔离，防止第 N 轮覆盖第 N-1 轮数据，
     * 同时让 AnalysisAgent 和 OptimizeAgent 能访问历史轮次的产出进行对比分析。
     */
    @Schema(description = "每轮循环的产物快照列表。key 为 'cycle-{N}'（如 'cycle-1'），value 为该轮结束时 accumulatedArtifacts 的深拷贝。用于跨周期产物隔离和历史对比分析。")
    private Map<String, Object> cycleHistory;

    /**
     * 上一轮优化反馈，注入到下一轮的 TOPIC_PLANNING 输入中。
     *
     * <p>由 OptimizeAgent 产出，包含对选题方向、内容策略、配图风格等的改进建议。
     * 新一轮开始时，此字段会被合并到 {@link #inputs} 中供各 Agent 读取。
     */
    @Schema(description = "上一轮优化反馈，由 OptimizeAgent 产出。新一轮开始时会被合并到 inputs 中供各 Agent 读取。包含对选题方向、内容策略、配图风格等的改进建议。")
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
    @Schema(description = "账号画像 — 定义账号的定位、目标受众、语气风格和发布平台")
    public static class AccountProfile {
        @Schema(description = "账号 ID", example = "acc-001")
        private String accountId;
        @Schema(description = "账号名称", example = "成长观察室", required = true)
        @jakarta.validation.constraints.NotBlank(message = "accountName 不能为空")
        private String accountName;
        @Schema(description = "定位领域/赛道", example = "个人成长")
        private String niche;
        @Schema(description = "目标受众描述", example = "20-30岁年轻人")
        private String targetAudience;
        @Schema(description = "语气风格描述", example = "轻松、不要太说教")
        private String tone;
        @Schema(description = "发布平台列表", example = "[\"公众号\", \"小红书\", \"头条\"]")
        private java.util.List<String> platforms;

        /**
         * 个人经历/真实素材注入位（P1: Prompt 工程深度优化）。
         *
         * <p>创作者可在请求中传入自己的真实经历、数据或案例，ContentAgent 会将其
         * 注入到 {{personalExperience}} 模板变量中，使生成内容更具个人色彩和真实感，
         * 而非空泛说教。为 null 时表示不注入个人经历。
         */
        @Schema(description = "个人经历/真实素材注入位。创作者可传入真实经历、数据或案例，注入到 {{personalExperience}} 模板变量中，使生成内容更具个人色彩。为 null 时不注入。")
        private String personalExperience;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "对话消息 — 单条聊天记录")
    public static class ChatMessage {
        @Schema(description = "消息角色", example = "user", allowableValues = {"system", "user", "assistant"})
        private String role;
        @Schema(description = "消息内容")
        private String content;
    }
}
