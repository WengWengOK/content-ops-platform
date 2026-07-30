package com.contentops.common.event;

import lombok.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 异步任务事件（P1: 弹性与可观测性 — Kafka 异步模式）。
 *
 * <p>当编排器需要调用长耗时的 Agent（内容创作、配图生成）时，通过 Kafka 发送此事件
 * 而非同步 Feign HTTP 调用，避免 Feign 超时阻塞整个流水线。
 *
 * <p>流程：
 * <ol>
 *   <li>编排器发送 {@link AsyncTaskRequest} 到 Kafka topic</li>
 *   <li>目标 Agent 消费消息，执行 LLM 调用</li>
 *   <li>Agent 完成后发送 {@link AsyncTaskResult} 到结果 topic</li>
 *   <li>编排器消费结果，合并产物并推进到下一阶段</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTaskEvent {

    /** 请求部分 — 编排器发送给 Agent */
    private AsyncTaskRequest request;

    /** 结果部分 — Agent 完成后回传给编排器 */
    private AsyncTaskResult result;

    /**
     * 异步任务请求。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AsyncTaskRequest {
        /** 任务 ID（唯一标识一次异步调用） */
        private String taskId;

        /** 工作流 ID */
        private String workflowId;

        /** Agent 阶段代码（如 "content-creation"、"image-design"） */
        private String agentStage;

        /** 子阶段代码（如 "outline"、"draft"、"styles"、"generate"），可为空 */
        private String subStage;

        /** 账号画像 JSON */
        private com.contentops.common.dto.TaskContext.AccountProfile accountProfile;

        /** 输入参数 */
        private Map<String, Object> inputs;

        /** 累积产物 */
        private Map<String, Object> accumulatedArtifacts;

        /** 时间戳 */
        private LocalDateTime timestamp;
    }

    /**
     * 异步任务结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AsyncTaskResult {
        /** 任务 ID（与请求对应） */
        private String taskId;

        /** 工作流 ID */
        private String workflowId;

        /** Agent 阶段代码 */
        private String agentStage;

        /** 子阶段代码 */
        private String subStage;

        /** 是否成功 */
        private boolean success;

        /** 返回数据 */
        private Map<String, Object> data;

        /** 错误信息 */
        private String error;

        /** Token 消耗（输入） */
        private int inputTokens;

        /** Token 消耗（输出） */
        private int outputTokens;

        /** 执行耗时（毫秒） */
        private long durationMs;

        /** 完成时间戳 */
        private LocalDateTime completedAt;
    }
}
