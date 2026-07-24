package com.contentops.orchestrator.controller;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.orchestrator.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    /**
     * Start a new content operations workflow.
     * This kicks off the pipeline from the Topic Planning stage.
     */
    @PostMapping("/start")
    public ResponseEntity<AgentResponse<Map<String, Object>>> startWorkflow(
            @RequestBody StartWorkflowRequest request) {

        log.info("Starting new content operations workflow for account: {}", 
                request.getAccountProfile().getAccountName());

        String workflowId = UUID.randomUUID().toString();
        TaskContext context = TaskContext.builder()
                .workflowId(workflowId)
                .currentStage(AgentStage.TOPIC_PLANNING.getCode())
                .accountProfile(request.getAccountProfile())
                .inputs(request.getInputs())
                .accumulatedArtifacts(new java.util.HashMap<>())
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .requireHumanReview(request.isRequireHumanReview())
                .build();

        workflowService.startWorkflow(context);

        return ResponseEntity.ok(AgentResponse.success(
                "orchestrator",
                Map.of(
                        "workflowId", workflowId,
                        "currentStage", AgentStage.TOPIC_PLANNING.getCode(),
                        "message", "Workflow started. Topic Planning Agent is now executing."
                )
        ));
    }

    /**
     * Get the current status of a workflow.
     */
    @GetMapping("/{workflowId}/status")
    public ResponseEntity<AgentResponse<TaskContext>> getWorkflowStatus(
            @PathVariable String workflowId) {

        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.ok(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        return ResponseEntity.ok(AgentResponse.success("orchestrator", context));
    }

    /**
     * Approve the current stage and proceed to the next (for human-in-the-loop).
     */
    @PostMapping("/{workflowId}/approve")
    public ResponseEntity<AgentResponse<Map<String, Object>>> approveStage(
            @PathVariable String workflowId,
            @RequestParam(required = false) Map<String, Object> feedback) {

        workflowService.approveAndProceed(workflowId, feedback);

        return ResponseEntity.ok(AgentResponse.success(
                "orchestrator",
                Map.of("message", "Stage approved. Proceeding to next stage.")
        ));
    }

    /**
     * Get all pipeline stages.
     */
    @GetMapping("/stages")
    public ResponseEntity<AgentResponse<List<StageInfo>>> getStages() {
        List<StageInfo> stages = java.util.Arrays.stream(AgentStage.values())
                .map(s -> new StageInfo(s.getOrder(), s.getCode(), s.getNameCn(), s.getDescription()))
                .toList();
        return ResponseEntity.ok(AgentResponse.success("orchestrator", stages));
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class StartWorkflowRequest {
        private TaskContext.AccountProfile accountProfile;
        private Map<String, Object> inputs;
        private boolean requireHumanReview;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class StageInfo {
        private int order;
        private String code;
        private String name;
        private String description;
    }
}
