package com.contentops.optimize.controller;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.OptimizationResult;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.util.RequestInputResolver;
import com.contentops.optimize.agent.OptimizationAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.HashMap;
import java.util.Map;

/**
 * REST entry point for the Optimization Agent.
 *
 * <p>Consumed by the orchestrator (via Feign) at {@code POST /api/v1/optimize/execute}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/optimize")
@RequiredArgsConstructor
public class OptimizeAgentController {

    private final OptimizationAgent optimizationAgent;

    @PostMapping("/execute")
    public AgentResponse<Map<String, Object>> execute(@Valid @RequestBody AgentTaskRequest request) {
        log.info("Received optimization task: workflowId={}, taskId={}",
                request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.OPTIMIZATION.getCode(),
                        "Missing accountProfile in request");
            }

            String accountNiche = profile.getNiche();
            String analysisSummary = RequestInputResolver.resolve(request, "analysisSummary");
            String currentStrategy = RequestInputResolver.resolve(request, "currentStrategy");
            String historicalPerformance = RequestInputResolver.resolve(request, "historicalPerformance");

            OptimizationResult result = optimizationAgent.optimizeStrategy(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.OPTIMIZATION.getCode(), request.getWorkflowId()),
                    accountNiche,
                    analysisSummary,
                    currentStrategy,
                    historicalPerformance
            );

            Map<String, Object> data = new HashMap<>();
            data.put("strategyAdjustments", result.getStrategyAdjustments());
            data.put("recommendedTopics", result.getRecommendedTopics());
            data.put("learnings", result.getLearnings());
            data.put("healthScore", result.getHealthScore());
            data.put("cycleSummary", result.getCycleSummary());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("healthScore", result.getHealthScore());
            metadata.put("adjustmentCount",
                    result.getStrategyAdjustments() != null ? result.getStrategyAdjustments().size() : 0);
            metadata.put("recommendedTopicCount",
                    result.getRecommendedTopics() != null ? result.getRecommendedTopics().size() : 0);

            log.info("Optimization completed: workflowId={}, healthScore={}, adjustmentCount={}",
                    request.getWorkflowId(), result.getHealthScore(), metadata.get("adjustmentCount"));
            return AgentResponse.success(AgentStage.OPTIMIZATION.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("Optimization failed: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.OPTIMIZATION.getCode(), e.getMessage());
        }
    }
}
