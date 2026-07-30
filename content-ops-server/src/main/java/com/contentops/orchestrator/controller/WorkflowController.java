package com.contentops.orchestrator.controller;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.DiscussionResponse;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.dto.TopicPlanResult;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.orchestrator.gateway.AgentGateway;
import com.contentops.orchestrator.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/workflow")
@RequiredArgsConstructor
@Tag(name = "工作流编排", description = "内容运营流水线编排接口 — 管理选题→内容→配图→发布→分析→优化全流程，支持循环优化、渐进式生成、人机协同和讨论模式")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final AgentGateway agentGateway;

    /**
     * Start a new content operations workflow.
     * This kicks off the pipeline from the Topic Planning stage.
     */
    @PostMapping("/start")
    @Operation(
            summary = "启动内容运营工作流",
            description = "从选题规划阶段（Topic Planning）开始，创建并启动一条完整的内容运营流水线。" +
                    "工作流会依次经过选题→内容→配图→发布→分析→优化6个阶段，支持循环优化。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> startWorkflow(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "工作流启动请求，包含账号画像、输入参数和是否需要人工审核",
                    required = true
            )
            @Valid @RequestBody StartWorkflowRequest request) {

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
                .cycleCount(1)  // A计划：初始化循环计数
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
    @Operation(
            summary = "查询工作流状态",
            description = "获取指定工作流的完整上下文信息，包括当前阶段、子阶段、输入输出、累积产物、循环轮次等。"
    )
    public ResponseEntity<AgentResponse<TaskContext>> getWorkflowStatus(
            @Parameter(description = "工作流 ID（UUID 格式）", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String workflowId) {

        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.ok(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        return ResponseEntity.ok(AgentResponse.success("orchestrator", context));
    }

    /**
     * List all workflows (for Dashboard).
     */
    @GetMapping
    @Operation(
            summary = "获取所有工作流列表",
            description = "返回所有工作流的上下文列表，按创建时间倒序排列。前端仪表盘可据此展示工作流概览和统计数据。"
    )
    public ResponseEntity<AgentResponse<List<TaskContext>>> listWorkflows() {
        List<TaskContext> workflows = workflowService.listAllWorkflows();
        return ResponseEntity.ok(AgentResponse.success("orchestrator", workflows));
    }

    /**
     * Approve the current stage and proceed to the next (for human-in-the-loop).
     */
    @PostMapping("/{workflowId}/approve")
    @Operation(
            summary = "审批当前阶段并推进",
            description = "人工审核通过后，推进工作流到下一阶段。仅当 requireHumanReview=true 时需要调用此接口。" +
                    "可在 feedback 中传入修改意见，注入到下一阶段的输入中。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> approveStage(
            @Parameter(description = "工作流 ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String workflowId,
            @Parameter(description = "可选的审批反馈，key-value 形式，会被注入到下一阶段输入中")
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
    @Operation(
            summary = "确认子阶段并推进到下一步",
            description = "渐进式生成场景下，用户确认当前子阶段产物后调用此端点推进。" +
                    "如确认大纲后推进到初稿生成，或确认风格方向后推进到批量生图。" +
                    "请求体可选传入确认内容（如修改后的大纲或选择的风格）。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> confirmSubStage(
            @Parameter(description = "工作流 ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String workflowId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "可选的确认内容，可包含 confirmedOutline（确认的大纲）或 confirmedStyle（选择的风格方向）"
            )
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
    @Operation(
            summary = "获取所有流水线阶段",
            description = "返回6个阶段的完整定义列表，包括阶段顺序、编码、中文名称和描述说明。" +
                    "前端可据此渲染流水线进度条和阶段说明。"
    )
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
    @Operation(
            summary = "启动讨论会话",
            description = "创建一个多轮讨论会话，用户可以与 AI 讨论模糊的创作想法，明确方向后再生成方案。" +
                    "讨论模式下不走自动流水线，而是通过对话逐步澄清需求。"
    )
    public ResponseEntity<AgentResponse<DiscussionResponse>> startDiscussion(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "讨论启动请求，包含模糊创意和账号画像",
                    required = true
            )
            @Valid @RequestBody DiscussStartRequest request) {

        log.info("Starting discussion session via orchestrator");

        Map<String, Object> feignRequest = new HashMap<>();
        feignRequest.put("fuzzyIdea", request.getFuzzyIdea());
        feignRequest.put("accountProfile", request.getAccountProfile());

        AgentResponse<DiscussionResponse> response = agentGateway.startDiscussion(feignRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Continue the discussion with a new user message.
     */
    @PostMapping("/discuss/{sessionId}/chat")
    @Operation(
            summary = "继续讨论对话",
            description = "在已有的讨论会话中发送新消息，AI 会根据上下文继续多轮对话，帮助用户逐步明确创作方向。"
    )
    public ResponseEntity<AgentResponse<DiscussionResponse>> chat(
            @Parameter(description = "讨论会话 ID", required = true)
            @PathVariable String sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "用户消息内容",
                    required = true
            )
            @Valid @RequestBody DiscussChatRequest request) {

        log.info("Discussion chat via orchestrator: sessionId={}", sessionId);

        Map<String, Object> feignRequest = new HashMap<>();
        feignRequest.put("message", request.getMessage());

        AgentResponse<DiscussionResponse> response = agentGateway.chatDiscussion(sessionId, feignRequest);
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
    @Operation(
            summary = "结束讨论并启动流水线",
            description = "结束讨论会话，将讨论产出的选题方案（TopicPlanResult）作为输入，" +
                    "从内容创作阶段（CONTENT_CREATION）启动流水线，跳过选题规划阶段。" +
                    "如果 startPipeline=false，则只返回讨论结果，不自动启动流水线。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> finalizeDiscussion(
            @Parameter(description = "讨论会话 ID", required = true)
            @PathVariable String sessionId,
            @Parameter(description = "是否自动启动流水线（默认 true）", example = "true")
            @RequestParam(required = false, defaultValue = "true") boolean startPipeline) {

        log.info("Finalizing discussion: sessionId={}, startPipeline={}", sessionId, startPipeline);

        AgentResponse<TopicPlanResult> finalizeResponse = agentGateway.finalizeDiscussion(sessionId);

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
                    .cycleCount(1)  // A计划：初始化循环计数
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
            AgentResponse<DiscussionSession> sessionResponse = agentGateway.getDiscussionSession(sessionId);
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
    @Operation(
            summary = "获取讨论会话状态",
            description = "获取指定讨论会话的当前状态，包括会话 ID、对话历史和关联的账号画像。"
    )
    public ResponseEntity<AgentResponse<DiscussionSession>> getDiscussionSession(
            @Parameter(description = "讨论会话 ID", required = true)
            @PathVariable String sessionId) {
        AgentResponse<DiscussionSession> response = agentGateway.getDiscussionSession(sessionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Clear a discussion session and its conversation memory.
     */
    @DeleteMapping("/discuss/{sessionId}")
    @Operation(
            summary = "清除讨论会话",
            description = "删除指定讨论会话及其对话记忆（Redis ChatMemory）。清除后无法恢复。"
    )
    public ResponseEntity<AgentResponse<Void>> clearDiscussion(
            @Parameter(description = "讨论会话 ID", required = true)
            @PathVariable String sessionId) {
        agentGateway.clearDiscussionSession(sessionId);
        return ResponseEntity.ok(AgentResponse.success("orchestrator", null));
    }

    // ──────────────────── Request/Response DTOs ────────────────────

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @Schema(description = "启动工作流请求")
    public static class StartWorkflowRequest {
        @Schema(description = "账号画像信息，包含账号名称、定位领域、目标受众、语气风格、发布平台等", required = true)
        @jakarta.validation.constraints.NotNull(message = "accountProfile 不能为空")
        private TaskContext.AccountProfile accountProfile;
        @Schema(description = "工作流输入参数，键值对形式，如主题关键词、特别要求等")
        private Map<String, Object> inputs;
        @Schema(description = "是否需要人工审核（为 true 时每个阶段完成后暂停等待审批）", example = "false")
        private boolean requireHumanReview;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @Schema(description = "流水线阶段信息")
    public static class StageInfo {
        @Schema(description = "阶段顺序号（1-6）", example = "1")
        private int order;
        @Schema(description = "阶段编码，如 TOPIC_PLANNING", example = "TOPIC_PLANNING")
        private String code;
        @Schema(description = "阶段中文名称", example = "选题规划")
        private String name;
        @Schema(description = "阶段描述说明")
        private String description;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @Schema(description = "启动讨论会话请求")
    public static class DiscussStartRequest {
        @Schema(description = "用户的模糊创意或想法描述", required = true, example = "我想写一篇关于职场新人成长的系列文章")
        @jakarta.validation.constraints.NotBlank(message = "fuzzyIdea 不能为空")
        private String fuzzyIdea;
        @Schema(description = "账号画像信息")
        private TaskContext.AccountProfile accountProfile;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @Schema(description = "讨论对话消息请求")
    public static class DiscussChatRequest {
        @Schema(description = "用户发送的消息内容", required = true, example = "可以更聚焦在时间管理这个角度吗？")
        @jakarta.validation.constraints.NotBlank(message = "message 不能为空")
        private String message;
    }
}
