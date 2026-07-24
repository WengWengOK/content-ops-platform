package com.contentops.common.dto;

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
public class AgentResponse<T> {

    private boolean success;
    private String stage;
    private String message;
    private T data;
    private Map<String, Object> metadata;
    private LocalDateTime timestamp;
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
