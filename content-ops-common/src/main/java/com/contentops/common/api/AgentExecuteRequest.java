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
 * 等价于原 {@code StageTransitionEvent(STAGE_STARTED)}，但显式化为 DTO 便于 REST / Kafka 双协议传输。
 *
 * <p>设计原则：
 * <ol>
 *   <li>与 legacy Server 的事件模型 1:1 对应，迁移时 Worker 可直接复用现有 AgentController</li>
 *   <li>全字段可 JSON 序列化（无 Spring/LangChain4j 强依赖），common 模块零运行时额外开销</li>
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
}
