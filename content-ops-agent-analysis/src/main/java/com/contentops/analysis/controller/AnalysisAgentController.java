package com.contentops.analysis.controller;

import com.contentops.analysis.agent.DataAnalysisAgent;
import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.AnalysisReport;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST entry point for the Data Analysis Agent.
 *
 * <p>Consumed by the orchestrator (via Feign) at {@code POST /api/v1/analysis/execute}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisAgentController {

    private final DataAnalysisAgent dataAnalysisAgent;

    @PostMapping("/execute")
    public AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request) {
        log.info("Received data analysis task: workflowId={}, taskId={}",
                request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.DATA_ANALYSIS.getCode(),
                        "Missing accountProfile in request");
            }

            String accountNiche = profile.getNiche();
            String rawData = resolveInput(request, "rawData");
            String timeRange = resolveInput(request, "timeRange");
            String previousAnalysisSummary = resolveInput(request, "previousAnalysisSummary");

            AnalysisReport result = dataAnalysisAgent.analyzePerformance(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.DATA_ANALYSIS.getCode(), request.getWorkflowId()),
                    accountNiche,
                    rawData,
                    timeRange,
                    previousAnalysisSummary
            );

            Map<String, Object> data = new HashMap<>();
            data.put("keyMetrics", result.getKeyMetrics());
            data.put("categoryPerformance", result.getCategoryPerformance());
            data.put("timeSlotPerformance", result.getTimeSlotPerformance());
            data.put("insights", result.getInsights());
            data.put("recommendations", result.getRecommendations());
            data.put("chartData", result.getChartData());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("insightCount",
                    result.getInsights() != null ? result.getInsights().size() : 0);
            metadata.put("recommendationCount",
                    result.getRecommendations() != null ? result.getRecommendations().size() : 0);

            log.info("Data analysis completed: workflowId={}, insightCount={}, recommendationCount={}",
                    request.getWorkflowId(), metadata.get("insightCount"),
                    metadata.get("recommendationCount"));
            return AgentResponse.success(AgentStage.DATA_ANALYSIS.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("Data analysis failed: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.DATA_ANALYSIS.getCode(), e.getMessage());
        }
    }

    /**
     * Resolves a string input, preferring {@code inputs} and falling back to
     * {@code accumulatedArtifacts} carried over from previous pipeline stages.
     */
    private String resolveInput(AgentTaskRequest request, String key) {
        Map<String, Object> inputs = request.getInputs();
        if (inputs != null && inputs.containsKey(key)) {
            Object value = inputs.get(key);
            return value == null ? null : String.valueOf(value);
        }
        Map<String, Object> artifacts = request.getAccumulatedArtifacts();
        if (artifacts != null && artifacts.containsKey(key)) {
            Object value = artifacts.get(key);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }
}
