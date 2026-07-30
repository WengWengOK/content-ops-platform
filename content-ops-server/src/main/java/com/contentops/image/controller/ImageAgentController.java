package com.contentops.image.controller;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.ImageDesignResult;
import com.contentops.common.dto.StyleDirectionResult;
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
 * <p><b>P1 渐进式生成：</b>暴露三个端点：
 * <ul>
 *   <li>{@code POST /styles}   — 阶段一：生成 3 个风格方向，供人工选择</li>
 *   <li>{@code POST /generate} — 阶段二：基于确认风格批量生成配图和封面</li>
 *   <li>{@code POST /execute}  — 兼容端点：一次性生成全部配图</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/image")
@RequiredArgsConstructor
public class ImageAgentController {

    private static final List<String> DEFAULT_PLATFORMS = List.of("公众号", "小红书", "头条");

    private final ImageDesignAgent imageDesignAgent;

    // ══════════════════ 阶段一：风格方向 ══════════════════

    /**
     * 阶段一：生成配图风格方向。
     * 分析文章内容，返回 3 个候选风格方向供人工选择。
     */
    @PostMapping("/styles")
    public AgentResponse<Map<String, Object>> generateStyleDirections(@RequestBody AgentTaskRequest request) {
        log.info("[阶段一-风格] workflowId={}, taskId={}", request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(), "Missing accountProfile");
            }

            String articleTitle = resolveInput(request, "articleTitle");
            if (articleTitle == null || articleTitle.isBlank()) articleTitle = resolveInput(request, "topic");
            if (articleTitle == null || articleTitle.isBlank()) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing 'articleTitle' (content creation stage must run first)");
            }

            String articleContent = resolveInput(request, "articleContent");
            if (articleContent == null || articleContent.isBlank()) articleContent = resolveInput(request, "draftContent");
            if (articleContent == null || articleContent.isBlank()) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing 'articleContent' (content creation stage must run first)");
            }

            String articleTone = resolveInput(request, "tone");
            if (articleTone == null || articleTone.isBlank()) articleTone = profile.getTone();

            List<String> platforms = (profile.getPlatforms() == null || profile.getPlatforms().isEmpty())
                    ? DEFAULT_PLATFORMS : profile.getPlatforms();

            StyleDirectionResult result = imageDesignAgent.generateStyleDirections(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.IMAGE_DESIGN.getCode(), request.getWorkflowId()),
                    articleTitle, articleContent, articleTone, platforms);

            Map<String, Object> data = new HashMap<>();
            data.put("styleDirections", result);
            data.put("articleTitle", articleTitle);
            data.put("stage", "styles");
            data.put("needsConfirmation", true);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("subStage", "styles");
            metadata.put("nextSubStage", "generate");
            metadata.put("directionCount",
                    result.getDirections() != null ? result.getDirections().size() : 0);
            metadata.put("message", "风格方向已生成，请选择后调用 /api/v1/image/generate 生成配图");

            log.info("[阶段一-风格] 完成: workflowId={}, directionCount={}",
                    request.getWorkflowId(), metadata.get("directionCount"));
            return AgentResponse.success(AgentStage.IMAGE_DESIGN.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("[阶段一-风格] 失败: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(), e.getMessage());
        }
    }

    // ══════════════════ 阶段二：批量生图 ══════════════════

    /**
     * 阶段二：基于确认的风格方向批量生成配图和封面。
     * 需要在 inputs 或 accumulatedArtifacts 中传入 confirmedStyle。
     */
    @PostMapping("/generate")
    public AgentResponse<Map<String, Object>> generateImages(@RequestBody AgentTaskRequest request) {
        log.info("[阶段二-生图] workflowId={}, taskId={}", request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(), "Missing accountProfile");
            }

            String articleTitle = resolveInput(request, "articleTitle");
            if (articleTitle == null || articleTitle.isBlank()) articleTitle = resolveInput(request, "topic");
            if (articleTitle == null || articleTitle.isBlank()) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing 'articleTitle'");
            }

            String articleContent = resolveInput(request, "articleContent");
            if (articleContent == null || articleContent.isBlank()) articleContent = resolveInput(request, "draftContent");
            if (articleContent == null || articleContent.isBlank()) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing 'articleContent'");
            }

            // 从 inputs 或 accumulatedArtifacts 中获取确认的风格方向
            String confirmedStyle = resolveInput(request, "confirmedStyle");
            if (confirmedStyle == null || confirmedStyle.isBlank()) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing 'confirmedStyle' (call /styles first, then pass the selected direction here)");
            }

            String articleTone = resolveInput(request, "tone");
            if (articleTone == null || articleTone.isBlank()) articleTone = profile.getTone();

            List<String> platforms = (profile.getPlatforms() == null || profile.getPlatforms().isEmpty())
                    ? DEFAULT_PLATFORMS : profile.getPlatforms();

            ImageDesignResult result = imageDesignAgent.generateImages(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.IMAGE_DESIGN.getCode(), request.getWorkflowId()),
                    confirmedStyle, articleTitle, articleContent, articleTone, platforms);

            Map<String, Object> data = new HashMap<>();
            data.put("images", result.getImages());
            data.put("covers", result.getCovers());
            data.put("stage", "generate");

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workflowId", request.getWorkflowId());
            metadata.put("taskId", request.getTaskId());
            metadata.put("subStage", "generate");
            metadata.put("imageCount",
                    result.getImages() != null ? result.getImages().size() : 0);
            metadata.put("coverCount",
                    result.getCovers() != null ? result.getCovers().size() : 0);

            log.info("[阶段二-生图] 完成: workflowId={}, imageCount={}, coverCount={}",
                    request.getWorkflowId(), metadata.get("imageCount"), metadata.get("coverCount"));
            return AgentResponse.success(AgentStage.IMAGE_DESIGN.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("[阶段二-生图] 失败: workflowId={}", request.getWorkflowId(), e);
            return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(), e.getMessage());
        }
    }

    // ══════════════════ 兼容端点：一次性生成 ══════════════════

    /**
     * 兼容端点：一次性生成全部配图和封面。
     * 新流程建议使用 /styles → /generate 两阶段方式。
     */
    @PostMapping("/execute")
    public AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request) {
        log.info("[兼容-一次性] workflowId={}, taskId={}", request.getWorkflowId(), request.getTaskId());
        try {
            AccountProfile profile = request.getAccountProfile();
            if (profile == null) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing accountProfile in request");
            }

            String articleTitle = resolveInput(request, "articleTitle");
            if (articleTitle == null || articleTitle.isBlank()) articleTitle = resolveInput(request, "topic");
            if (articleTitle == null || articleTitle.isBlank()) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing required input 'articleTitle' (content creation stage must run first)");
            }

            String articleContent = resolveInput(request, "articleContent");
            if (articleContent == null || articleContent.isBlank()) articleContent = resolveInput(request, "draftContent");
            if (articleContent == null || articleContent.isBlank()) {
                return AgentResponse.failure(AgentStage.IMAGE_DESIGN.getCode(),
                        "Missing required input 'articleContent' (content creation stage must run first)");
            }

            String articleTone = resolveInput(request, "tone");
            if (articleTone == null || articleTone.isBlank()) articleTone = profile.getTone();

            List<String> platforms = (profile.getPlatforms() == null || profile.getPlatforms().isEmpty())
                    ? DEFAULT_PLATFORMS : profile.getPlatforms();

            ImageDesignResult result = imageDesignAgent.designImages(
                    String.format(AgentConstants.MEMORY_ID_FORMAT,
                            AgentStage.IMAGE_DESIGN.getCode(), request.getWorkflowId()),
                    articleTitle, articleContent, articleTone, platforms);

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

            log.info("[兼容-一次性] 完成: workflowId={}, imageCount={}, coverCount={}",
                    request.getWorkflowId(), metadata.get("imageCount"), metadata.get("coverCount"));
            return AgentResponse.success(AgentStage.IMAGE_DESIGN.getCode(), data, metadata);
        } catch (Exception e) {
            log.error("[兼容-一次性] 失败: workflowId={}", request.getWorkflowId(), e);
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
