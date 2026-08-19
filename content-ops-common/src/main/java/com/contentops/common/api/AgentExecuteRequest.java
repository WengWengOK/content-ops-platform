package com.contentops.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Phase3 服务化：Agent Stage 执行请求（Orchestrator → Worker 内部接口契约）。
 * 等价于原 {@code AgentTaskRequest}，但显式化为 DTO 便于 REST / Kafka 双协议传输。
 *
 * <p>字段与 {@code AgentTaskRequest} 完全对齐（v2 扩展）：
 * 新增 accountProfile / accumulatedArtifacts / requireHumanReview / timestamp 四个字段，
 * 保证 RemoteAgentGateway HTTP 调用时 Worker 侧能重建完整的 AgentTaskRequest，零信息丢失。
 *
 * <p>设计原则：
 * <ol>
 *   <li>与 legacy Server 的 AgentTaskRequest 1:1 对应，Worker 收到后直接 builder 重建</li>
 *   <li>全字段可 JSON 序列化（accountProfile 是 POJO，Jackson 默认序列化）</li>
 *   <li>traceId 透传字段保留，OTel 跨服务 span 关联</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentExecuteRequest implements Serializable {

    /** 工作流 ID（跨服务关联主键） */
    private String workflowId;

    /** 任务 ID（同一工作流下可多次执行不同阶段） */
    private String taskId;

    /** 要执行的阶段 code，取值见 {@code AgentStage#getCode()} */
    private String stageCode;

    /** 阶段输入：原 AgentRequest inputs Map（prompt / accountId / niche / ragContext / projectMemory 等） */
    private Map<String, Object> inputs;

    /** traceId：跨服务链路追踪（Orchestrator → Worker → Tools 三跳贯通） */
    private String traceId;

    /** 请求发起时间（用于幂等/重试超时判定） */
    private LocalDateTime createdAt;

    // ── v2 扩展字段（与 AgentTaskRequest 对齐，避免 HTTP 传输丢字段） ──

    /** 账号画像（@JsonProperty 默认序列化 POJO 即可，Worker 侧反序列化为 AccountProfile） */
    private Map<String, Object> accountProfile;

    /** 前序阶段累积产物（topics / content / images / metrics 等） */
    private Map<String, Object> accumulatedArtifacts;

    /** 是否需要人工审核后才能进入下一阶段 */
    private boolean requireHumanReview;

    /** 任务时间戳（原 AgentTaskRequest.timestamp） */
    private LocalDateTime timestamp;
}
