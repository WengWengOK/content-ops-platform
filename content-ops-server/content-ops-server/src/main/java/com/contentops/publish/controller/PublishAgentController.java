package com.contentops.publish.controller;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.PublishResult;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.PublishMode;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.util.RequestInputResolver;
import com.contentops.publish.agent.PublishingAgent;
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
    public AgentResponse<Map<String, Object>> execute(@Valid @RequestBody AgentTaskRequest request) {
        log.info("Received publishing task: workflowId={}, taskId={}",
                request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.PUBLISHING.getCode(),
                        "Missing accountProfile in request");
            }

            // Article title and content typically come from the previous content-creation stage.
            String articleTitle = RequestInputResolver.resolve(request, "articleTitle");
            if (articleTitle == null || articleTitle.isBlank()) {
                articleTitle = RequestInputResolver.resolve(request, "topic");
            }
            if (articleTitle == null || articleTitle.isBlank()) {
                articleTitle = RequestInputResolver.resolveFromStage(request, "topic-planning", "topic");
            }
            if (articleTitle == null || articleTitle.isBlank()) {
                return AgentResponse.failure(AgentStage.PUBLISHING.getCode(),
                        "Missing required input 'articleTitle' (content creation stage must run first)");
            }

            String articleContent = RequestInputResolver.resolve(request, "articleContent");
            if (articleContent == null || articleContent.isBlank()) {
                articleContent = RequestInputResolver.resolve(request, "draftContent");
            }
            if (articleContent == null || articleContent.isBlank()) {
                articleContent = RequestInputResolver.resolveFromStage(request, "content-creation:draft", "draftContent");
            }
            if (articleContent == null || articleContent.isBlank()) {
                return AgentResponse.failure(AgentStage.PUBLISHING.getCode(),
                        "Missing required input 'articleContent' (content creation stage must run first)");
            }

            // Cover image URL typically comes from the previous image-design stage.
            String coverImageUrl = RequestInputResolver.resolve(request, "coverImageUrl");
            if (coverImageUrl == null) {
                coverImageUrl = "";
            }

            String tone = RequestInputResolver.resolve(request, "tone");
            if (tone == null || tone.isBlank()) {
                tone = profile.getTone();
            }
            if (tone == null) {
                tone = "";
            }

            List<String> platforms = (profile.getPlatforms() == null || profile.getPlatforms().isEmpty())
                    ? DEFAULT_PLATFORMS
                    : profile.getPlatforms();

            // 发布作品模式：text-cover / image-text / full-image（来自创建工作流时的选择）
            String publishModeCode = RequestInputResolver.resolve(request, "publishMode");
            PublishMode publishMode = PublishMode.fromCode(publishModeCode);

            // 正文配图（来自 image-design 阶段），用于图文混排 / 全图卡片模式的下载与预览
            java.util.List<Map<String, Object>> images = new java.util.ArrayList<>();
            Object rawImages = request.getAccumulatedArtifacts() != null
                    ? request.getAccumulatedArtifacts().get("image-design:images")
                    : null;
            if (rawImages == null && request.getAccumulatedArtifacts() != null) {
                Object imageArtifact = request.getAccumulatedArtifacts().get(AgentStage.IMAGE_DESIGN.getCode());
                if (imageArtifact instanceof Map<?, ?> imageMap) {
                    rawImages = imageMap.get("images");
                }
            }
            if (rawImages instanceof java.util.List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> image = (Map<String, Object>) map;
                        images.add(image);
                    }
                }
            }

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
            data.put("publishMode", publishMode.getCode());
            data.put("publishModeName", publishMode.getNameCn());
            data.put("articleTitle", articleTitle);
            data.put("articleContent", articleContent);
            data.put("coverImageUrl", coverImageUrl);
            data.put("images", images);

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
}
