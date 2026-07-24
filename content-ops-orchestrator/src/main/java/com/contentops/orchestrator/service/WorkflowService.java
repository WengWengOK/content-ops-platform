package com.contentops.orchestrator.service;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.workflow.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowStateManager stateManager;
    private final PipelineOrchestrator orchestrator;

    /**
     * Start a new workflow by executing the first stage.
     */
    public void startWorkflow(TaskContext context) {
        stateManager.saveWorkflowState(context.getWorkflowId(), context);
        orchestrator.executeStage(context);
    }

    /**
     * Get current workflow status from Redis.
     */
    public TaskContext getWorkflowStatus(String workflowId) {
        return stateManager.loadWorkflowState(workflowId).orElse(null);
    }

    /**
     * Approve current stage and proceed to the next.
     * This is called after human review.
     */
    public void approveAndProceed(String workflowId, Map<String, Object> feedback) {
        TaskContext context = stateManager.loadWorkflowState(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowId));

        if (!TaskStatus.AWAITING_HUMAN.name().equals(context.getStatus())) {
            throw new RuntimeException("Workflow is not awaiting human review. Current status: " 
                    + context.getStatus());
        }

        // If feedback provided, merge it into inputs
        if (feedback != null && !feedback.isEmpty()) {
            if (context.getInputs() == null) {
                context.setInputs(new java.util.HashMap<>());
            }
            context.getInputs().put("humanFeedback", feedback);
        }

        // Advance to next stage
        com.contentops.common.enums.AgentStage currentStage = 
                com.contentops.common.enums.AgentStage.fromCode(context.getCurrentStage());
        com.contentops.common.enums.AgentStage nextStage = currentStage.next();
        context.setCurrentStage(nextStage.getCode());
        context.setStatus(TaskStatus.PENDING.name());

        log.info("[Workflow:{}] Human approved. Advancing {} → {}", 
                workflowId, currentStage.getCode(), nextStage.getCode());

        orchestrator.executeStage(context);
    }

    /**
     * Retry the current stage after a failure.
     */
    public void retryStage(String workflowId) {
        TaskContext context = stateManager.loadWorkflowState(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found: " + workflowId));

        context.setStatus(TaskStatus.PENDING.name());
        context.setErrorMessage(null);

        log.info("[Workflow:{}] Retrying stage: {}", workflowId, context.getCurrentStage());
        orchestrator.executeStage(context);
    }
}
