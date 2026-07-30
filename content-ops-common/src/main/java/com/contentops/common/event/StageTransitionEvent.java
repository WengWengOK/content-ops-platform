package com.contentops.common.event;

import lombok.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Event published to Kafka when a stage transitions.
 * Consumed by the orchestrator to trigger the next agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StageTransitionEvent {

    /** Workflow ID */
    private String workflowId;

    /** Stage that completed */
    private String fromStage;

    /** Next stage to execute */
    private String toStage;

    /** Event type: STAGE_STARTED, STAGE_COMPLETED, STAGE_FAILED */
    private String eventType;

    /** Serialized artifact summary (not full data - full data in Redis) */
    private Map<String, Object> artifactSummary;

    /** Timestamp */
    private LocalDateTime timestamp;

    /** Error message if failed */
    private String errorMessage;

    public static StageTransitionEvent started(String workflowId, String stage) {
        return StageTransitionEvent.builder()
                .workflowId(workflowId)
                .toStage(stage)
                .eventType("STAGE_STARTED")
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static StageTransitionEvent completed(String workflowId, String fromStage, String toStage,
                                                  Map<String, Object> artifacts) {
        return StageTransitionEvent.builder()
                .workflowId(workflowId)
                .fromStage(fromStage)
                .toStage(toStage)
                .eventType("STAGE_COMPLETED")
                .artifactSummary(artifacts)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static StageTransitionEvent failed(String workflowId, String stage, String error) {
        return StageTransitionEvent.builder()
                .workflowId(workflowId)
                .fromStage(stage)
                .eventType("STAGE_FAILED")
                .errorMessage(error)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
