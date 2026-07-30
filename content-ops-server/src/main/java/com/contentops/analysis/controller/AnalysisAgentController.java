package com.contentops.analysis.controller;

import com.contentops.analysis.agent.DataAnalysisAgent;
import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.AnalysisReport;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.methodology.TrendAggregationEnforcer;
import com.contentops.common.profile.audience.ProfileEnricher;
import com.contentops.common.util.RequestInputResolver;
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
 * REST entry point for the Data Analysis Agent.
 *
 * <p>Consumed by the orchestrator (via Feign) at {@code POST /api/v1/analysis/execute}.
 *
 * <p><b>P1 集成：</b>通过 {@link ProfileEnricher} 在调用 DataAnalysisAgent 前将受众画像注入到
 * rawData 中，使分析能基于真实的粉丝属性与行为偏好展开；无画像时原样返回，不影响执行。
 *
 * <p><b>P2 集成：</b>通过 {@link TrendAggregationEnforcer} 强制月度趋势聚合，
 * 确保"趋势而非单篇"方法论在代码层面被执行。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisAgentController {

    private final DataAnalysisAgent dataAnalysisAgent;
    private final TrendAggregationEnforcer trendEnforcer;
    private final ProfileEnricher profileEnricher;

    @PostMapping("/execute")
    public AgentResponse<Map<String, Object>> execute(@Valid @RequestBody AgentTaskRequest request) {
        log.info("Received data analysis task: workflowId={}, taskId={}",
                request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.DATA_ANALYSIS.getCode(),
                        "Missing accountProfile in request");
            }

            String accountNiche = profile.getNiche();
            String rawData = RequestInputResolver.resolve(request, "rawData");
            String timeRange = RequestInputResolver.resolve(request, "timeRange");
            String previousAnalysisSummary = RequestInputResolver.resolve(request, "previousAnalysisSummary");

            // P1 集成：注入受众画像到分析 Prompt（无画像时原样返回）
            String accountId = profile.getAccountId();
            String enrichedRawData = profileEnricher.enrichAnalysisPrompt(accountId, rawData);
            log.debug("Enriched analysis prompt with audience profile: accountId={}", accountId);

            AnalysisReport result = dataAnalysisAgent.analyzePerformance(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.DATA_ANALYSIS.getCode(), request.getWorkflowId()),
                    accountNiche,
                    enrichedRawData,
                    timeRange,
                    previousAnalysisSummary
            );

            // P2 集成：强制月度趋势聚合（"趋势而非单篇"方法论）
            trendEnforcer.enforceMonthlyAggregation(result, rawData != null ? rawData : "");
            TrendAggregationEnforcer.ValidationResult validation =
                    trendEnforcer.validateTrendCoverage(result);
            if (!validation.valid()) {
                log.warn("[Workflow:{}] Trend coverage validation: {}",
                        request.getWorkflowId(), validation.message());
            }

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
            metadata.put("trendValidation", validation.valid());

            log.info("Data analysis completed: workflowId={}, insightCount={}, recommendationCount={}, trendValid={}",
                    request.getWorkflowId(), metadata.get("insightCount"),
                    metadata.get("recommendationCount"), validation.valid());
            return AgentResponse.success(AgentStage.DATA_ANALYSIS.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("Data analysis failed: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.DATA_ANALYSIS.getCode(), e.getMessage());
        }
    }
}
