package com.contentops.content.controller;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.ContentDraftResult;
import com.contentops.common.dto.OutlineResult;
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
 * <p><b>P1 渐进式生成：</b>暴露三个端点：
 * <ul>
 *   <li>{@code POST /outline} — 阶段一：生成大纲，供人工确认</li>
 *   <li>{@code POST /draft}  — 阶段二：基于确认大纲生成完整初稿</li>
 *   <li>{@code POST /execute} — 兼容端点：一次性生成完整结果</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/content")
@RequiredArgsConstructor
public class ContentAgentController {

    private final ContentCreationAgent contentCreationAgent;

    // ══════════════════ 阶段一：大纲生成 ══════════════════

    /**
     * 阶段一：生成文章框架大纲。
     * 调用 generateOutline()，返回大纲结构供人工确认。
     */
    @PostMapping("/outline")
    public AgentResponse<Map<String, Object>> generateOutline(@RequestBody AgentTaskRequest request) {
        log.info("[阶段一-大纲] workflowId={}, taskId={}", request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(), "Missing accountProfile");
            }

            String topic = resolveInput(request, "topic");
            if (topic == null || topic.isBlank()) topic = resolveInput(request, "selectedTopic");
            if (topic == null || topic.isBlank()) {
                return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(),
                        "Missing 'topic' (topic planning stage must run first)");
            }

            String angle = resolveInput(request, "angle");
            String additionalContext = resolveInput(request, "additionalContext");
            // P1: 提取个人经历/真实素材（优先从 inputs 取，其次从 AccountProfile 取）
            String personalExperience = resolveInput(request, "personalExperience");
            if ((personalExperience == null || personalExperience.isBlank())
                    && profile.getPersonalExperience() != null) {
                personalExperience = profile.getPersonalExperience();
            }

            OutlineResult result = contentCreationAgent.generateOutline(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.CONTENT_CREATION.getCode(), request.getWorkflowId()),
                    topic, angle, profile.getNiche(), profile.getTargetAudience(),
                    profile.getTone(), additionalContext, personalExperience);

            Map<String, Object> data = new HashMap<>();
            data.put("outline", result);
            data.put("topic", topic);
            data.put("stage", "outline");
            data.put("needsConfirmation", true);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("subStage", "outline");
            metadata.put("nextSubStage", "draft");
            metadata.put("message", "大纲已生成，请确认后调用 /api/v1/content/draft 生成初稿");

            log.info("[阶段一-大纲] 完成: workflowId={}", request.getWorkflowId());
            return AgentResponse.success(AgentStage.CONTENT_CREATION.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("[阶段一-大纲] 失败: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(), e.getMessage());
        }
    }

    // ══════════════════ 阶段二：初稿生成 ══════════════════

    /**
     * 阶段二：基于确认的大纲生成完整初稿。
     * 需要在 inputs 或 accumulatedArtifacts 中传入 confirmedOutline。
     */
    @PostMapping("/draft")
    public AgentResponse<Map<String, Object>> generateDraft(@RequestBody AgentTaskRequest request) {
        log.info("[阶段二-初稿] workflowId={}, taskId={}", request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(), "Missing accountProfile");
            }

            String topic = resolveInput(request, "topic");
            if (topic == null || topic.isBlank()) topic = resolveInput(request, "selectedTopic");
            if (topic == null || topic.isBlank()) {
                return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(),
                        "Missing 'topic'");
            }

            // 从 inputs 或 accumulatedArtifacts 中获取确认的大纲
            String confirmedOutline = resolveInput(request, "confirmedOutline");
            if (confirmedOutline == null || confirmedOutline.isBlank()) {
                return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(),
                        "Missing 'confirmedOutline' (call /outline first, then pass the result here)");
            }

            // P1: 提取个人经历/真实素材（优先从 inputs 取，其次从 AccountProfile 取）
            String personalExperience = resolveInput(request, "personalExperience");
            if ((personalExperience == null || personalExperience.isBlank())
                    && profile.getPersonalExperience() != null) {
                personalExperience = profile.getPersonalExperience();
            }

            ContentDraftResult result = contentCreationAgent.generateDraft(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.CONTENT_CREATION.getCode(), request.getWorkflowId()),
                    confirmedOutline, topic, profile.getNiche(), profile.getTone(),
                    profile.getNiche(), request.getWorkflowId(), personalExperience);

            Map<String, Object> data = new HashMap<>();
            data.put("outline", result.getOutline());
            data.put("draftContent", result.getDraftContent());
            data.put("wordCount", result.getWordCount());
            data.put("titleVariations", result.getTitleVariations());
            data.put("tags", result.getTags());
            data.put("summary", result.getSummary());
            data.put("stage", "draft");

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("subStage", "draft");
            metadata.put("wordCount", result.getWordCount());
            metadata.put("titleCount",
                    result.getTitleVariations() != null ? result.getTitleVariations().size() : 0);

            log.info("[阶段二-初稿] 完成: workflowId={}, wordCount={}",
                    request.getWorkflowId(), result.getWordCount());
            return AgentResponse.success(AgentStage.CONTENT_CREATION.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("[阶段二-初稿] 失败: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(), e.getMessage());
        }
    }

    // ══════════════════ 兼容端点：一次性生成 ══════════════════

    /**
     * 兼容端点：一次性生成完整文章初稿（大纲+正文+标题+标签）。
     * 新流程建议使用 /outline → /draft 两阶段方式。
     */
    @PostMapping("/execute")
    public AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request) {
        log.info("[兼容-一次性] workflowId={}, taskId={}", request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(),
                        "Missing accountProfile in request");
            }

            String topic = resolveInput(request, "topic");
            if (topic == null || topic.isBlank()) topic = resolveInput(request, "selectedTopic");
            if (topic == null || topic.isBlank()) {
                return AgentResponse.failure(AgentStage.CONTENT_CREATION.getCode(),
                        "Missing required input 'topic' (topic planning stage must run first)");
            }

            String angle = resolveInput(request, "angle");
            String outline = resolveInput(request, "outline");
            String additionalContext = resolveInput(request, "additionalContext");
            // P1: 提取个人经历/真实素材（优先从 inputs 取，其次从 AccountProfile 取）
            String personalExperience = resolveInput(request, "personalExperience");
            if ((personalExperience == null || personalExperience.isBlank())
                    && profile.getPersonalExperience() != null) {
                personalExperience = profile.getPersonalExperience();
            }

            ContentDraftResult result = contentCreationAgent.createDraft(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.CONTENT_CREATION.getCode(), request.getWorkflowId()),
                    topic, angle, profile.getNiche(), profile.getTargetAudience(),
                    profile.getTone(), outline, additionalContext, personalExperience);

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

            log.info("[兼容-一次性] 完成: workflowId={}, wordCount={}",
                    request.getWorkflowId(), result.getWordCount());
            return AgentResponse.success(AgentStage.CONTENT_CREATION.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("[兼容-一次性] 失败: workflowId={}", request.getWorkflowId(), e);
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
