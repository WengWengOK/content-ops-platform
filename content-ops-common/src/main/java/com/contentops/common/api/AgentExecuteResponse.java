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
 * Phase3 服务化：Agent Stage 执行响应（Worker → Orchestrator 内部接口契约）。
 * 等价于原 {@code AgentResponse}，但拆出 success/failure/statusCode 标准字段，
 * orchestrator 收到后继续推进工作流状态机。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentExecuteResponse implements Serializable {

    /** 执行成功/失败（注意：评测低分不代表 stage 失败，是否门禁阻断由 orchestrator gate 判断） */
    private boolean success;

    /** 对应请求的 stage code，方便 orchestrator 路由 */
    private String stageCode;

    /** 阶段产物数据（原 AgentResponse.getData()）：TopicPlanResult / ContentDraftResult / ImageDesignResult 等 */
    private Map<String, Object> data;

    /** 元信息：tokenUsage / latencyMs / adjustmentCount / promptVersion 等 */
    private Map<String, Object> metadata;

    /** 错误信息（success=false 时必填） */
    private String errorMessage;

    /** 响应时间 */
    private LocalDateTime finishedAt;

    // ── 便捷工厂 ──────────────────────────────────────────

    public static AgentExecuteResponse ok(String stageCode, Map<String, Object> data, Map<String, Object> metadata) {
        return AgentExecuteResponse.builder()
                .success(true)
                .stageCode(stageCode)
                .data(data)
                .metadata(metadata)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    public static AgentExecuteResponse fail(String stageCode, String errorMessage) {
        return AgentExecuteResponse.builder()
                .success(false)
                .stageCode(stageCode)
                .errorMessage(errorMessage)
                .finishedAt(LocalDateTime.now())
                .build();
    }
}
