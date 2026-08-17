package com.contentops.common.event;

import lombok.*;
import java.time.LocalDateTime;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to execute a specific agent stage.
 * Sent from orchestrator to agent via Kafka or REST.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskRequest {

    /** Unique task ID within a workflow */
    private String taskId;

    /** Workflow ID — 必填 */
    @NotBlank(message = "workflowId 不能为空")
    private String workflowId;

    /** Agent stage code — 必填 */
    @NotBlank(message = "stageCode 不能为空")
    private String stageCode;

    /** Account profile */
    private com.contentops.common.dto.TaskContext.AccountProfile accountProfile;

    /** Input parameters */
    private Map<String, Object> inputs;

    /** Accumulated artifacts from previous stages */
    private Map<String, Object> accumulatedArtifacts;

    /** Whether human review is required before next stage */
    private boolean requireHumanReview;

    /** Timestamp */
    private LocalDateTime timestamp;

    public static AgentTaskRequest of(String workflowId, String stageCode,
                                       com.contentops.common.dto.TaskContext.AccountProfile profile,
                                       Map<String, Object> inputs,
                                       Map<String, Object> artifacts) {
        return AgentTaskRequest.builder()
                .taskId(java.util.UUID.randomUUID().toString())
                .workflowId(workflowId)
                .stageCode(stageCode)
                .accountProfile(profile)
                .inputs(inputs)
                .accumulatedArtifacts(artifacts)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
