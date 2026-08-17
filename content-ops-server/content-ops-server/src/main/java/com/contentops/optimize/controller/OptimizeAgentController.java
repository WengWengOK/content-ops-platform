package com.contentops.optimize.controller;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.OptimizationResult;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.profile.audience.ProfileEnricher;
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
 *
 * <p><b>P1 集成：</b>通过 {@link ProfileEnricher} 在调用 OptimizationAgent 前将内容画像与历史表现
 * 注入到 historicalPerformance 中，使策略优化能基于真实的历史数据展开；无画像时原样返回，不影响执行。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/optimize")
@RequiredArgsConstructor
public class OptimizeAgentController {

    private final OptimizationAgent optimizationAgent;
    private final ProfileEnricher profileEnricher;

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
            if (analysisSummary == null) {
                analysisSummary = "";
            }
            if (currentStrategy == null) {
                currentStrategy = "";
            }
            if (historicalPerformance == null) {
                historicalPerformance = "";
            }

            // P1 集成：注入内容画像与历史表现到优化 Prompt（无画像时原样返回）
            String accountId = profile.getAccountId();
            String enrichedHistoricalPerformance = profileEnricher.enrichOptimizationPrompt(accountId, historicalPerformance);
            log.debug("Enriched optimization prompt with content profile: accountId={}", accountId);

            OptimizationResult result = optimizationAgent.optimizeStrategy(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.OPTIMIZATION.getCode(), request.getWorkflowId()),
                    accountNiche,
                    analysisSummary,
                    currentStrategy,
                    enrichedHistoricalPerformance
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
