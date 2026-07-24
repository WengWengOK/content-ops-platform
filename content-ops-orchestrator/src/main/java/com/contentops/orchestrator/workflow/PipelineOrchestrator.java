package com.contentops.orchestrator.workflow;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.event.StageTransitionEvent;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.orchestrator.service.AgentFeignClients.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The Pipeline Orchestrator — the heart of the multi-agent system.
 *
 * Implements a Sequential Pipeline pattern:
 *   Topic → Content → Image → Publish → Analysis → Optimize → (loop back to Topic)
 *
 * Each stage:
 *   1. Loads accumulated artifacts from Redis
 *   2. Calls the appropriate agent microservice via Feign
 *   3. Merges new artifacts back into Redis
 *   4. If human review required, pauses; otherwise advances to next stage
 *   5. Publishes a StageTransitionEvent to Kafka for observability
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final WorkflowStateManager stateManager;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Feign clients for each agent
    private final TopicAgentClient topicAgentClient;
    private final ContentAgentClient contentAgentClient;
    private final ImageAgentClient imageAgentClient;
    private final PublishAgentClient publishAgentClient;
    private final AnalysisAgentClient analysisAgentClient;
    private final OptimizeAgentClient optimizeAgentClient;

    /**
     * Execute the current stage for a workflow.
     */
    public void executeStage(TaskContext context) {
        AgentStage stage = AgentStage.fromCode(context.getCurrentStage());
        log.info("[Workflow:{}] Executing stage: {} ({})", 
                context.getWorkflowId(), stage.getCode(), stage.getNameCn());

        context.setStatus(TaskStatus.IN_PROGRESS.name());
        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        // Publish start event
        publishEvent(StageTransitionEvent.started(context.getWorkflowId(), stage.getCode()));

        try {
            // Build request from context
            AgentTaskRequest request = AgentTaskRequest.of(
                    context.getWorkflowId(),
                    stage.getCode(),
                    context.getAccountProfile(),
                    context.getInputs(),
                    context.getAccumulatedArtifacts()
            );
            request.setRequireHumanReview(context.isRequireHumanReview());

            // Route to the appropriate agent
            AgentResponse<Map<String, Object>> response = routeToAgent(stage, request);

            if (response.isSuccess()) {
                handleStageSuccess(context, stage, response);
            } else {
                handleStageFailure(context, stage, response.getError());
            }
        } catch (Exception e) {
            log.error("[Workflow:{}] Stage {} failed with exception", 
                    context.getWorkflowId(), stage.getCode(), e);
            handleStageFailure(context, stage, e.getMessage());
        }
    }

    /**
     * Route the task to the appropriate agent microservice.
     */
    private AgentResponse<Map<String, Object>> routeToAgent(AgentStage stage, AgentTaskRequest request) {
        return switch (stage) {
            case TOPIC_PLANNING -> topicAgentClient.execute(request);
            case CONTENT_CREATION -> contentAgentClient.execute(request);
            case IMAGE_DESIGN -> imageAgentClient.execute(request);
            case PUBLISHING -> publishAgentClient.execute(request);
            case DATA_ANALYSIS -> analysisAgentClient.execute(request);
            case OPTIMIZATION -> optimizeAgentClient.execute(request);
        };
    }

    /**
     * Handle successful stage execution.
     */
    private void handleStageSuccess(TaskContext context, AgentStage stage,
                                     AgentResponse<Map<String, Object>> response) {
        // Merge artifacts
        if (response.getData() != null) {
            context.setOutputs(response.getData());
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            context.getAccumulatedArtifacts().put(stage.getCode(), response.getData());
        }

        // Check for human review
        if (context.isRequireHumanReview()) {
            context.setStatus(TaskStatus.AWAITING_HUMAN.name());
            log.info("[Workflow:{}] Stage {} completed. Awaiting human approval.", 
                    context.getWorkflowId(), stage.getCode());
        } else {
            // Auto-advance to next stage
            AgentStage nextStage = stage.next();
            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());
            log.info("[Workflow:{}] Stage {} → {} (auto-advance)", 
                    context.getWorkflowId(), stage.getCode(), nextStage.getCode());
        }

        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        // Publish completion event
        publishEvent(StageTransitionEvent.completed(
                context.getWorkflowId(),
                stage.getCode(),
                context.getCurrentStage(),
                response.getData()
        ));
    }

    /**
     * Handle failed stage execution.
     */
    private void handleStageFailure(TaskContext context, AgentStage stage, String error) {
        context.setStatus(TaskStatus.FAILED.name());
        context.setErrorMessage(error);
        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        publishEvent(StageTransitionEvent.failed(
                context.getWorkflowId(), stage.getCode(), error
        ));

        log.error("[Workflow:{}] Stage {} FAILED: {}", 
                context.getWorkflowId(), stage.getCode(), error);
    }

    /**
     * Publish stage transition event to Kafka.
     */
    private void publishEvent(StageTransitionEvent event) {
        try {
            kafkaTemplate.send(AgentConstants.TASK_EVENT_TOPIC, event.getWorkflowId(), event);
        } catch (Exception e) {
            log.warn("Failed to publish Kafka event: {}", e.getMessage());
        }
    }
}
