package com.contentops.content.controller;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.ContentDraftResult;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.content.agent.ContentCreationAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST entry point for the Content Creation Agent.
 *
 * <p>Consumed by the orchestrator (via Feign) at {@code POST /api/v1/content/execute}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/content")
@RequiredArgsConstructor
public class ContentAgentController {

    private final ContentCreationAgent contentCreationAgent;

    @PostMapping("/execute")
    public AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request) {
        log.info("Received content creation task: workflowId={}, taskId={}",
                request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(),
                        "Missing accountProfile in request");
            }

            // Topic and angle typically come from the previous topic-planning stage.
            String topic = resolveInput(request, "topic");
            if (topic == null || topic.isBlank()) {
                topic = resolveInput(request, "selectedTopic");
            }
            if (topic == null || topic.isBlank()) {
                return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(),
                        "Missing required input 'topic' (topic planning stage must run first)");
            }

            String angle = resolveInput(request, "angle");
            String outline = resolveInput(request, "outline");
            String additionalContext = resolveInput(request, "additionalContext");

            ContentDraftResult result = contentCreationAgent.createDraft(
                    topic,
                    angle,
                    profile.getNiche(),
                    profile.getTargetAudience(),
                    profile.getTone(),
                    outline,
                    additionalContext
            );

            Map<String, Object> data = new HashMap<>();
            data.put("outline", result.getOutline());
            data.put("draftContent", result.getDraftContent());
            data.put("wordCount", result.getWordCount());
            data.put("titleVariations", result.getTitleVariations());
            data.put("tags", result.getTags());
            data.put("summary", result.getSummary());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("topic", topic);
            metadata.put("titleCount",
                    result.getTitleVariations() != null ? result.getTitleVariations().size() : 0);

            log.info("Content creation completed: workflowId={}, wordCount={}, titleCount={}",
                    request.getWorkflowId(), result.getWordCount(), metadata.get("titleCount"));
            return AgentResponse.success(AgentStage.CONTENT_CREATION.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("Content creation failed: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(), e.getMessage());
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
