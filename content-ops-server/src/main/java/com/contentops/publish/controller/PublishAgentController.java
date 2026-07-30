package com.contentops.publish.controller;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.PublishResult;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.publish.agent.PublishingAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST entry point for the Publishing Agent.
 *
 * <p>Consumed by the orchestrator (via Feign) at {@code POST /api/v1/publish/execute}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/publish")
@RequiredArgsConstructor
public class PublishAgentController {

    private static final List<String> DEFAULT_PLATFORMS = List.of("公众号", "小红书", "头条");

    private final PublishingAgent publishingAgent;

    @PostMapping("/execute")
    public AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request) {
        log.info("Received publishing task: workflowId={}, taskId={}",
                request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.PUBLISHING.getCode(),
                        "Missing accountProfile in request");
            }

            // Article title and content typically come from the previous content-creation stage.
            String articleTitle = resolveInput(request, "articleTitle");
            if (articleTitle == null || articleTitle.isBlank()) {
                articleTitle = resolveInput(request, "topic");
            }
            if (articleTitle == null || articleTitle.isBlank()) {
                return AgentResponse.failure(AgentStage.PUBLISHING.getCode(),
                        "Missing required input 'articleTitle' (content creation stage must run first)");
            }

            String articleContent = resolveInput(request, "articleContent");
            if (articleContent == null || articleContent.isBlank()) {
                articleContent = resolveInput(request, "draftContent");
            }
            if (articleContent == null || articleContent.isBlank()) {
                return AgentResponse.failure(AgentStage.PUBLISHING.getCode(),
                        "Missing required input 'articleContent' (content creation stage must run first)");
            }

            // Cover image URL typically comes from the previous image-design stage.
            String coverImageUrl = resolveInput(request, "coverImageUrl");

            String tone = resolveInput(request, "tone");
            if (tone == null || tone.isBlank()) {
                tone = profile.getTone();
            }

            List<String> platforms = (profile.getPlatforms() == null || profile.getPlatforms().isEmpty())
                    ? DEFAULT_PLATFORMS
                    : profile.getPlatforms();

            PublishResult result = publishingAgent.formatAndPublish(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.PUBLISHING.getCode(), request.getWorkflowId()),
                    articleTitle,
                    articleContent,
                    platforms,
                    coverImageUrl,
                    tone
            );

            Map<String, Object> data = new HashMap<>();
            data.put("publications", result.getPublications());
            data.put("status", result.getStatus());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("articleTitle", articleTitle);
            metadata.put("platformCount",
                    result.getPublications() != null ? result.getPublications().size() : 0);
            metadata.put("overallStatus", result.getStatus());

            log.info("Publishing completed: workflowId={}, platformCount={}, status={}",
                    request.getWorkflowId(), metadata.get("platformCount"), result.getStatus());
            return AgentResponse.success(AgentStage.PUBLISHING.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("Publishing failed: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.PUBLISHING.getCode(), e.getMessage());
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
