package com.contentops.orchestrator.workflow;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The Pipeline Orchestrator — facade for the multi-agent pipeline.
 *
 * <p>Refactored (P2-13) from a monolithic 722-line class into a thin facade that
 * delegates to four single-responsibility collaborators:
 * <ul>
 *   <li>{@link StageExecutor} — single-stage execution logic</li>
 *   <li>{@link SubStageExecutor} — sub-stage (progressive generation) logic</li>
 *   <li>{@link CycleHandler} — cycle boundary control (OPTIMIZATION → TOPIC_PLANNING)</li>
 *   <li>{@link QualityEnricher} — quality assessment + human action checklist</li>
 * </ul>
 *
 * <p>Public API is preserved unchanged (WorkflowService depends on these methods):
 * <ul>
 *   <li>{@link #executeStage(TaskContext)}</li>
 *   <li>{@link #confirmAndProceedSubStage(TaskContext, Map)}</li>
 *   <li>{@link #checkAndHandleCycleBoundary(TaskContext, AgentStage, AgentStage)}</li>
 * </ul>
 *
 * <p>Implements a Sequential Pipeline pattern:
 *   Topic → Content → Image → Publish → Analysis → Optimize → (loop back to Topic)
 *
 * <p>Each stage:
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

    private final StageExecutor stageExecutor;
    private final SubStageExecutor subStageExecutor;
    private final CycleHandler cycleHandler;
    private final QualityEnricher qualityEnricher;

    /**
     * Execute the current stage for a workflow.
     *
     * <p>Delegates to {@link StageExecutor#executeStage(TaskContext)}.
     */
    public void executeStage(TaskContext context) {
        stageExecutor.executeStage(context);
    }

    /**
     * Confirm a sub-stage and proceed to the next sub-stage or the next AgentStage.
     *
     * <p>Delegates to {@link SubStageExecutor#confirmAndProceedSubStage(TaskContext, Map)}.
     *
     * @param context   the workflow context
     * @param feedback  optional feedback/modifications from the user (e.g., edited outline)
     */
    public void confirmAndProceedSubStage(TaskContext context, Map<String, Object> feedback) {
        subStageExecutor.confirmAndProceedSubStage(context, feedback);
    }

    /**
     * Check and handle the cycle boundary (OPTIMIZATION → TOPIC_PLANNING).
     *
     * <p>Delegates to
     * {@link CycleHandler#checkAndHandleCycleBoundary(TaskContext, AgentStage, AgentStage)}.
     *
     * @return true if a cycle boundary was handled (caller should NOT continue normal advance),
     *         false if it is not a cycle boundary
     */
    public boolean checkAndHandleCycleBoundary(TaskContext context,
                                                 AgentStage currentStage,
                                                 AgentStage nextStage) {
        return cycleHandler.checkAndHandleCycleBoundary(context, currentStage, nextStage);
    }
}
