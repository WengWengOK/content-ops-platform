package com.contentops.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Generic API response wrapper for all agent services.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一 API 响应包装 — 所有 Agent 服务的标准返回格式，包含成功标志、阶段标识、数据载荷、元信息和错误信息")
public class AgentResponse<T> {

    @Schema(description = "请求是否成功", example = "true")
    private boolean success;

    @Schema(description = "产生响应的阶段标识（Agent 编码）", example = "orchestrator")
    private String stage;

    @Schema(description = "响应消息描述", example = "Workflow started successfully")
    private String message;

    @Schema(description = "响应数据载荷（泛型）")
    private T data;

    @Schema(description = "元信息，如 token 用量、耗时等附加数据")
    private Map<String, Object> metadata;

    @Schema(description = "响应时间戳")
    private LocalDateTime timestamp;

    @Schema(description = "失败时的错误信息")
    private String error;

    public static <T> AgentResponse<T> success(String stage, T data) {
        return AgentResponse.<T>builder()
                .success(true)
                .stage(stage)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> AgentResponse<T> success(String stage, T data, Map<String, Object> metadata) {
        return AgentResponse.<T>builder()
                .success(true)
                .stage(stage)
                .data(data)
                .metadata(metadata)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> AgentResponse<T> failure(String stage, String error) {
        return AgentResponse.<T>builder()
                .success(false)
                .stage(stage)
                .error(error)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
