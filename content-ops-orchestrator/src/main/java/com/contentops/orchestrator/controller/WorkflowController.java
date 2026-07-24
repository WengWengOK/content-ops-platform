package com.contentops.orchestrator.controller;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.DiscussionResponse;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.dto.TopicPlanResult;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.orchestrator.service.AgentFeignClients.DiscussionAgentClient;
import com.contentops.orchestrator.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final DiscussionAgentClient discussionClient;

    /**
     * Start a new content operations workflow.
     * This kicks off the pipeline from the Topic Planning stage.
     */
    @PostMapping("/start")
    public ResponseEntity<AgentResponse<Map<String, Object>>> startWorkflow(
            @RequestBody StartWorkflowRequest request) {

        log.info("Starting new content operations workflow for account: {}", 
                request.getAccountProfile().getAccountName());

        String workflowId = UUID.randomUUID().toString();
        TaskContext context = TaskContext.builder()
                .workflowId(workflowId)
                .currentStage(AgentStage.TOPIC_PLANNING.getCode())
                .accountProfile(request.getAccountProfile())
                .inputs(request.getInputs())
                .accumulatedArtifacts(new java.util.HashMap<>())
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .requireHumanReview(request.isRequireHumanReview())
                .build();

        workflowService.startWorkflow(context);

        return ResponseEntity.ok(AgentResponse.success(
                "orchestrator",
                Map.of(
                        "workflowId", workflowId,
                        "currentStage", AgentStage.TOPIC_PLANNING.getCode(),
                        "message", "Workflow started. Topic Planning Agent is now executing."
                )
        ));
    }

    /**
     * Get the current status of a workflow.
     */
    @GetMapping("/{workflowId}/status")
    public ResponseEntity<AgentResponse<TaskContext>> getWorkflowStatus(
            @PathVariable String workflowId) {

        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.ok(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        return ResponseEntity.ok(AgentResponse.success("orchestrator", context));
    }

    /**
     * Approve the current stage and proceed to the next (for human-in-the-loop).
     */
    @PostMapping("/{workflowId}/approve")
    public ResponseEntity<AgentResponse<Map<String, Object>>> approveStage(
            @PathVariable String workflowId,
            @RequestParam(required = false) Map<String, Object> feedback) {

        workflowService.approveAndProceed(workflowId, feedback);

        return ResponseEntity.ok(AgentResponse.success(
                "orchestrator",
                Map.of("message", "Stage approved. Proceeding to next stage.")
        ));
    }

    /**
     * 确认当前子阶段并推进到下一个子阶段（渐进式生成）。
     *
     * <p>当 CONTENT_CREATION 执行完大纲生成（outline）后，工作流会暂停等待人工确认。
     * 用户确认大纲后调用此端点，编排器会推进到初稿生成（draft）子阶段。
     *
     * <p>同理，IMAGE_DESIGN 执行完风格方向（styles）后，用户确认后推进到批量生图（generate）。
     *
     * @param workflowId 工作流 ID
     * @param body      可选的请求体，包含确认内容：
     *                  <ul>
     *                    <li>{@code confirmedOutline}：确认的大纲（可修改）</li>
     *                    <li>{@code confirmedStyle}：选择的风格方向</li>
     *                  </ul>
     */
    @PostMapping("/{workflowId}/confirm-substage")
    public ResponseEntity<AgentResponse<Map<String, Object>>> confirmSubStage(
            @PathVariable String workflowId,
            @RequestBody(required = false) Map<String, Object> body) {

        log.info("[Workflow:{}] Confirming sub-stage", workflowId);
        workflowService.confirmSubStage(workflowId, body);

        return ResponseEntity.ok(AgentResponse.success(
                "orchestrator",
                Map.of("message", "Sub-stage confirmed. Proceeding to next sub-stage.")
        ));
    }

    /**
     * Get all pipeline stages.
     */
    @GetMapping("/stages")
    public ResponseEntity<AgentResponse<List<StageInfo>>> getStages() {
        List<StageInfo> stages = java.util.Arrays.stream(AgentStage.values())
                .map(s -> new StageInfo(s.getOrder(), s.getCode(), s.getNameCn(), s.getDescription()))
                .toList();
        return ResponseEntity.ok(AgentResponse.success("orchestrator", stages));
    }

    // ──────────────────── Discussion Endpoints ────────────────────

    /**
     * Start a multi-turn discussion session ("把TRAE当讨论对象").
     *
     * <p>Instead of directly running the pipeline, the user discusses their
     * fuzzy idea with the AI to clarify direction before generating a plan.
     */
    @PostMapping("/discuss/start")
    public ResponseEntity<AgentResponse<DiscussionResponse>> startDiscussion(
            @RequestBody DiscussStartRequest request) {

        log.info("Starting discussion session via orchestrator");

        Map<String, Object> feignRequest = new HashMap<>();
        feignRequest.put("fuzzyIdea", request.getFuzzyIdea());
        feignRequest.put("accountProfile", request.getAccountProfile());

        AgentResponse<DiscussionResponse> response = discussionClient.startDiscussion(feignRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Continue the discussion with a new user message.
     */
    @PostMapping("/discuss/{sessionId}/chat")
    public ResponseEntity<AgentResponse<DiscussionResponse>> chat(
            @PathVariable String sessionId,
            @RequestBody DiscussChatRequest request) {

        log.info("Discussion chat via orchestrator: sessionId={}", sessionId);

        Map<String, Object> feignRequest = new HashMap<>();
        feignRequest.put("message", request.getMessage());

        AgentResponse<DiscussionResponse> response = discussionClient.chat(sessionId, feignRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Finalize the discussion and optionally start the pipeline from Content Creation.
     *
     * <p>The discussion result (TopicPlanResult) is used as the input for the
     * Content Creation stage, skipping the Topic Planning stage (since the
     * discussion already produced a topic plan).
     *
     * @param sessionId    the discussion session ID
     * @param startPipeline if true, automatically start the pipeline from CONTENT_CREATION
     */
    @PostMapping("/discuss/{sessionId}/finalize")
    public ResponseEntity<AgentResponse<Map<String, Object>>> finalizeDiscussion(
            @PathVariable String sessionId,
            @RequestParam(required = false, defaultValue = "true") boolean startPipeline) {

        log.info("Finalizing discussion: sessionId={}, startPipeline={}", sessionId, startPipeline);

        AgentResponse<TopicPlanResult> finalizeResponse = discussionClient.finalize(sessionId);

        if (!finalizeResponse.isSuccess()) {
            return ResponseEntity.ok(AgentResponse.failure("orchestrator",
                    "Discussion finalization failed: " + finalizeResponse.getError()));
        }

        TopicPlanResult topicPlan = finalizeResponse.getData();
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("topicPlan", topicPlan);

        if (startPipeline && topicPlan != null) {
            // Start the pipeline from CONTENT_CREATION, using the discussion result
            String workflowId = sessionId; // use sessionId as workflowId for traceability
            TaskContext context = TaskContext.builder()
                    .workflowId(workflowId)
                    .currentStage(AgentStage.CONTENT_CREATION.getCode())
                    .accountProfile(null) // populated below if available
                    .inputs(new HashMap<>())
                    .accumulatedArtifacts(new HashMap<>())
                    .status(TaskStatus.PENDING.name())
                    .createdAt(LocalDateTime.now())
                    .requireHumanReview(false)
                    .build();

            // Store the topic plan as an accumulated artifact from the "topic-planning" stage
            Map<String, Object> topicArtifacts = new HashMap<>();
            if (topicPlan.getTopics() != null && !topicPlan.getTopics().isEmpty()) {
                TopicPlanResult.TopicCandidate firstTopic = topicPlan.getTopics().get(0);
                topicArtifacts.put("topic", firstTopic.getTitle());
                topicArtifacts.put("angle", firstTopic.getAngle());
            }
            topicArtifacts.put("trendingKeywords", topicPlan.getTrendingKeywords());
            topicArtifacts.put("competitiveAnalysis", topicPlan.getCompetitiveAnalysis());
            topicArtifacts.put("recommendedDirection", topicPlan.getRecommendedDirection());
            topicArtifacts.put("topicPlan", topicPlan);

            context.getAccumulatedArtifacts().put(AgentStage.TOPIC_PLANNING.getCode(), topicArtifacts);

            // Retrieve account profile from the discussion session if available
            AgentResponse<DiscussionSession> sessionResponse = discussionClient.getSession(sessionId);
            if (sessionResponse.isSuccess() && sessionResponse.getData() != null) {
                context.setAccountProfile(sessionResponse.getData().getAccountProfile());
            }

            workflowService.startWorkflow(context);

            result.put("workflowId", workflowId);
            result.put("currentStage", AgentStage.CONTENT_CREATION.getCode());
            result.put("message", "Discussion finalized. Pipeline started from Content Creation stage.");
            log.info("Pipeline started from discussion: sessionId={}, workflowId={}", sessionId, workflowId);
        } else {
            result.put("message", "Discussion finalized. Call /start to begin the pipeline manually.");
        }

        return ResponseEntity.ok(AgentResponse.success("orchestrator", result));
    }

    /**
     * Get the current discussion session state.
     */
    @GetMapping("/discuss/{sessionId}")
    public ResponseEntity<AgentResponse<DiscussionSession>> getDiscussionSession(
            @PathVariable String sessionId) {
        AgentResponse<DiscussionSession> response = discussionClient.getSession(sessionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Clear a discussion session and its conversation memory.
     */
    @DeleteMapping("/discuss/{sessionId}")
    public ResponseEntity<AgentResponse<Void>> clearDiscussion(
            @PathVariable String sessionId) {
        discussionClient.clearSession(sessionId);
        return ResponseEntity.ok(AgentResponse.success("orchestrator", null));
    }

    // ──────────────────── Request/Response DTOs ────────────────────

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class StartWorkflowRequest {
        private TaskContext.AccountProfile accountProfile;
        private Map<String, Object> inputs;
        private boolean requireHumanReview;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class StageInfo {
        private int order;
        private String code;
        private String name;
        private String description;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class DiscussStartRequest {
        private String fuzzyIdea;
        private TaskContext.AccountProfile accountProfile;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class DiscussChatRequest {
        private String message;
    }
}
