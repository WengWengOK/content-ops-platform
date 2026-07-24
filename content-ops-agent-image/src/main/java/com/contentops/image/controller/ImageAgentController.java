package com.contentops.image.controller;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.ImageDesignResult;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.image.agent.ImageDesignAgent;
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
 * REST entry point for the Image Design Agent.
 *
 * <p>Consumed by the orchestrator (via Feign) at {@code POST /api/v1/image/execute}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/image")
@RequiredArgsConstructor
public class ImageAgentController {

    private static final List<String> DEFAULT_PLATFORMS = List.of("公众号", "小红书", "头条");

    private final ImageDesignAgent imageDesignAgent;

    @PostMapping("/execute")
    public AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request) {
        log.info("Received image design task: workflowId={}, taskId={}",
                request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing accountProfile in request");
            }

            // Article title and content typically come from the previous content-creation stage.
            String articleTitle = resolveInput(request, "articleTitle");
            if (articleTitle == null || articleTitle.isBlank()) {
                articleTitle = resolveInput(request, "topic");
            }
            if (articleTitle == null || articleTitle.isBlank()) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing required input 'articleTitle' (content creation stage must run first)");
            }

            String articleContent = resolveInput(request, "articleContent");
            if (articleContent == null || articleContent.isBlank()) {
                articleContent = resolveInput(request, "draftContent");
            }
            if (articleContent == null || articleContent.isBlank()) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing required input 'articleContent' (content creation stage must run first)");
            }

            String articleTone = resolveInput(request, "tone");
            if (articleTone == null || articleTone.isBlank()) {
                articleTone = profile.getTone();
            }

            List<String> platforms = (profile.getPlatforms() == null || profile.getPlatforms().isEmpty())
                    ? DEFAULT_PLATFORMS
                    : profile.getPlatforms();

            ImageDesignResult result = imageDesignAgent.designImages(
                    articleTitle,
                    articleContent,
                    articleTone,
                    platforms
            );

            Map<String, Object> data = new HashMap<>();
            data.put("images", result.getImages());
            data.put("covers", result.getCovers());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("articleTitle", articleTitle);
            metadata.put("imageCount",
                    result.getImages() != null ? result.getImages().size() : 0);
            metadata.put("coverCount",
                    result.getCovers() != null ? result.getCovers().size() : 0);

            log.info("Image design completed: workflowId={}, imageCount={}, coverCount={}",
                    request.getWorkflowId(), metadata.get("imageCount"), metadata.get("coverCount"));
            return AgentResponse.success(AgentStage.IMAGE_DESIGN.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("Image design failed: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(), e.getMessage());
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
