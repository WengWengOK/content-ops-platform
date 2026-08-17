package com.contentops.topic.controller;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.dto.TopicPlanResult;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.util.RequestInputResolver;
import com.contentops.topic.agent.TopicPlanningAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST entry point for the Topic Planning Agent.
 *
 * <p>Consumed by the orchestrator (via Feign) at {@code POST /api/v1/topic/execute}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/topic")
@RequiredArgsConstructor
public class TopicAgentController {

    private static final List<String> DEFAULT_PLATFORMS = List.of("公众号", "小红书", "头条");

    private final TopicPlanningAgent topicPlanningAgent;

    @PostMapping("/execute")
    public AgentResponse<Map<String, Object>> execute(@Valid @RequestBody AgentTaskRequest request) {
        log.info("Received topic planning task: workflowId={}, taskId={}",
                request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.TOPIC_PLANNING.getCode(),
                        "Missing accountProfile in request");
            }

            List<String> platforms = (profile.getPlatforms() == null || profile.getPlatforms().isEmpty())
                    ? DEFAULT_PLATFORMS
                    : profile.getPlatforms();
            String additionalContext = RequestInputResolver.resolve(request, "additionalContext");
            if (additionalContext == null) {
                additionalContext = "";
            }

            TopicPlanResult result = topicPlanningAgent.planTopics(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.TOPIC_PLANNING.getCode(), request.getWorkflowId()),
                    profile.getNiche(),
                    profile.getTargetAudience(),
                    profile.getTone(),
                    platforms,
                    additionalContext
            );

            Map<String, Object> data = new HashMap<>();
            data.put("topics", result.getTopics());
            data.put("trendingKeywords", result.getTrendingKeywords());
            data.put("competitiveAnalysis", result.getCompetitiveAnalysis());
            data.put("recommendedDirection", result.getRecommendedDirection());

            // 为下游内容创作提供顶层 topic/angle（供 RequestInputResolver 直接读取）
            if (result.getTopics() != null && !result.getTopics().isEmpty()) {
                TopicPlanResult.TopicCandidate first = result.getTopics().get(0);
                data.put("topic", first.getTitle());
                data.put("selectedTopic", first.getTitle());
                data.put("angle", first.getAngle());
            }
            if (data.get("topic") == null && result.getRecommendedDirection() != null) {
                data.put("topic", result.getRecommendedDirection());
                data.put("selectedTopic", result.getRecommendedDirection());
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("candidateCount",
                    result.getTopics() != null ? result.getTopics().size() : 0);

            log.info("Topic planning completed: workflowId={}, candidateCount={}",
                    request.getWorkflowId(), metadata.get("candidateCount"));
            return AgentResponse.success(AgentStage.TOPIC_PLANNING.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("Topic planning failed: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.TOPIC_PLANNING.getCode(), e.getMessage());
        }
    }
}
