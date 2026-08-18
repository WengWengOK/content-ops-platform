package com.contentops.orchestrator.controller;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.DiscussionResponse;
import com.contentops.common.dto.DiscussionSession;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.dto.TopicPlanResult;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.PublishMode;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.platform.ContentPlatform;
import com.contentops.common.platform.PlatformSpecRegistry;
import com.contentops.common.security.AuthContext;
import com.contentops.orchestrator.gateway.AgentGateway;
import com.contentops.orchestrator.service.WorkflowService;
import com.contentops.topic.agent.DiscussionAgent;
import com.contentops.topic.service.DiscussionSessionService;
import com.contentops.common.upload.FileStorageService;
import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.common.event.WorkflowEventBroadcaster;
import com.contentops.common.audit.AuditService;
import com.contentops.common.memory.ProjectMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
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
    private final PlatformSpecRegistry platformSpecRegistry;
    private final DiscussionSessionService discussionSessionService;
    private final DiscussionAgent discussionAgent;
    private final WorkflowEventBroadcaster workflowEventBroadcaster;
    private final AuditService auditService;
    private final FileStorageService fileStorageService;
    private final ProjectMemoryService projectMemoryService;

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

        java.util.List<ContentPlatform> platforms = platformSpecRegistry.resolveAll(
                request.getAccountProfile().getPlatforms());
        if (platforms.isEmpty()) {
            platforms = java.util.List.of(ContentPlatform.XIAOHONGSHU);
        }
        java.util.Map<String, Object> normalizedInputs = new HashMap<>();
        if (request.getInputs() != null) {
            normalizedInputs.putAll(request.getInputs());
        }
        normalizedInputs.put("platforms",
                platforms.stream().map(ContentPlatform::getCode).toList());
        normalizedInputs.put("platformNames",
                platforms.stream().map(ContentPlatform::getDisplayName).toList());
        normalizedInputs.put("publishMode",
                PublishMode.fromCode(request.getPublishMode()).getCode());
        normalizedInputs.put("collectionIds",
                request.getCollectionIds() == null
                        ? java.util.List.of()
                        : new java.util.ArrayList<>(request.getCollectionIds()));
        if (request.getPlatformAccounts() != null && !request.getPlatformAccounts().isEmpty()) {
            java.util.Map<String, java.util.Map<String, String>> accountMap = new HashMap<>();
            request.getPlatformAccounts().forEach((key, info) -> {
                java.util.Map<String, String> entry = new HashMap<>();
                entry.put("accountId", info.getAccountId());
                entry.put("accountName", info.getAccountName());
                accountMap.put(key, entry);
            });
            normalizedInputs.put("platformAccounts", accountMap);
        }
        request.getAccountProfile().setPlatforms(
                new java.util.ArrayList<>(platforms.stream().map(ContentPlatform::getDisplayName).toList()));

        // 成本控制：默认最多 2 轮（每轮 = 整条流水线），需要更多可传 inputs.maxCycles
        int maxCycles = 2;
        Object maxCyclesInput = normalizedInputs.get("maxCycles");
        if (maxCyclesInput instanceof Number number) {
            maxCycles = Math.max(1, number.intValue());
        }

        String workflowId = UUID.randomUUID().toString();
        TaskContext context = TaskContext.builder()
                .workflowId(workflowId)
                .ownerId(AuthContext.currentUserId())
                .currentStage(AgentStage.TOPIC_PLANNING.getCode())
                .accountProfile(request.getAccountProfile())
                .inputs(normalizedInputs)
                .accumulatedArtifacts(new java.util.HashMap<>())
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .requireHumanReview(request.isRequireHumanReview())
                .maxCycles(maxCycles)
                .cycleCount(1)  // A计划：初始化循环计数
                .build();

        // 长期记忆 P2：工作流启动时注入跨工作流项目记忆（失败不阻断）
        projectMemoryService.enrichContextWithMemory(context);

        workflowService.startWorkflow(context);
        auditService.record("WORKFLOW_START", "workflow", workflowId,
                "启动工作流：" + request.getAccountProfile().getAccountName());

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
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权访问该工作流"));
        }
        return ResponseEntity.ok(AgentResponse.success("orchestrator", context));
    }

    /**
     * 订阅工作流阶段事件（SSE）：STAGE_STARTED / STAGE_COMPLETED / STAGE_FAILED 实时推送。
     */
    @GetMapping("/{workflowId}/events")
    @Operation(
            summary = "订阅工作流阶段事件（SSE）",
            description = "前端通过 EventSource 订阅，阶段推进时实时收到事件，用于流式展示流水线进度。"
    )
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter workflowEvents(
            @Parameter(description = "工作流 ID", required = true) @PathVariable String workflowId) {
        return workflowEventBroadcaster.subscribe(workflowId);
    }

    /**
     * List all workflows (for Dashboard).
     */
    @GetMapping
    @Operation(
            summary = "获取工作流列表（分页）",
            description = "分页返回工作流列表，按创建时间倒序排列。支持 page/size 参数分页。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> listWorkflows(
            @Parameter(description = "页码（从 0 开始，默认 0）", example = "0")
            @RequestParam(required = false, defaultValue = "0") int page,
            @Parameter(description = "每页条数（默认 20，最大 100）", example = "20")
            @RequestParam(required = false, defaultValue = "20") int size) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);

        String ownerId = AuthContext.currentUserId();
        List<TaskContext> all = ownerId != null
                ? workflowService.listWorkflowsByOwner(ownerId)
                : workflowService.listAllWorkflows();
        int total = all.size();
        int fromIndex = Math.min(safePage * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);
        List<TaskContext> pageData = all.subList(fromIndex, toIndex);

        Map<String, Object> result = new HashMap<>();
        result.put("content", pageData);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("totalPages", (int) Math.ceil((double) total / safeSize));

        return ResponseEntity.ok(AgentResponse.success("orchestrator", result));
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

        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权访问该工作流"));
        }
        workflowService.approveAndProceed(workflowId, feedback);
        auditService.record("WORKFLOW_APPROVE", "workflow", workflowId,
                feedback == null ? "通过当前阶段" : "通过并附带修改意见");

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

        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权访问该工作流"));
        }
        log.info("[Workflow:{}] Confirming sub-stage", workflowId);
        workflowService.confirmSubStage(workflowId, body);
        auditService.record("WORKFLOW_CONFIRM_SUBSTAGE", "workflow", workflowId,
                "确认子阶段：" + body);

        return ResponseEntity.ok(AgentResponse.success(
                "orchestrator",
                Map.of("message", "Sub-stage confirmed. Proceeding to next sub-stage.")
        ));
    }

    /**
     * 选题确认后选择发布平台：单平台直接在原工作流继续，多平台扇出并行分支。
     */
    @PostMapping("/{workflowId}/select-platforms")
    @Operation(
            summary = "选择发布平台并开始产出",
            description = "选题规划完成后调用：用户确认选题并选择目标平台。" +
                    "单平台直接在原工作流继续内容创作；多平台扇出为并行分支流水线。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> selectPlatforms(
            @Parameter(description = "工作流 ID", required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String workflowId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "目标平台列表（中文名或 code）及可选各平台账号",
                    required = true
            )
            @Valid @RequestBody SelectPlatformsRequest request) {

        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权访问该工作流"));
        }

        java.util.Map<String, java.util.Map<String, String>> accountMap = new java.util.LinkedHashMap<>();
        if (request.getPlatformAccounts() != null) {
            request.getPlatformAccounts().forEach((key, info) -> {
                java.util.Map<String, String> entry = new java.util.HashMap<>();
                entry.put("accountId", info.getAccountId());
                entry.put("accountName", info.getAccountName());
                accountMap.put(key, entry);
            });
        }

        workflowService.selectPlatforms(workflowId, request.getPlatforms(), accountMap,
                request.getTopic(), request.getCustomTopic());
        auditService.record("WORKFLOW_SELECT_PLATFORMS", "workflow", workflowId,
                "选择平台：" + String.join(",", request.getPlatforms()));

        return ResponseEntity.ok(AgentResponse.success(
                "orchestrator",
                Map.of(
                        "message", "平台已确认，流水线开始产出",
                        "platforms", request.getPlatforms()
                )
        ));
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @Schema(description = "选择发布平台请求（选题规划完成后调用）")
    public static class SelectPlatformsRequest {
        @Schema(description = "目标发布平台（中文名或 code），至少一个", required = true)
        @jakarta.validation.constraints.NotEmpty(message = "platforms 不能为空")
        private java.util.List<String> platforms;
        @Schema(description = "用户从多个选题中选择的标题（可选）")
        private String topic;
        @Schema(description = "用户自定义选题（可选，选择其他时填写）")
        private String customTopic;
        @Schema(description = "各平台账号映射：key 为平台中文名或 code")
        private java.util.Map<String, PlatformAccountInfo> platformAccounts;
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
        // 主流水线只保留 4 个 Agent 阶段；数据分析和优化为独立服务，见 /standalone-services
        List<StageInfo> stages = java.util.Arrays.stream(AgentStage.values())
                .filter(s -> s.getOrder() <= 4)
                .map(s -> new StageInfo(s.getOrder(), s.getCode(), s.getNameCn(), s.getDescription()))
                .toList();
        return ResponseEntity.ok(AgentResponse.success("orchestrator", stages));
    }

    /**
     * 独立服务列表：数据分析、优化迭代（不进入主流水线，按需调用）。
     */
    @GetMapping("/standalone-services")
    @Operation(
            summary = "获取独立服务列表",
            description = "返回数据分析和优化两个独立服务定义，它们不在主流水线中自动执行，可对已完成的作品按需调用。"
    )
    public ResponseEntity<AgentResponse<List<StageInfo>>> getStandaloneServices() {
        List<StageInfo> services = java.util.Arrays.stream(AgentStage.values())
                .filter(s -> s == AgentStage.DATA_ANALYSIS || s == AgentStage.OPTIMIZATION)
                .map(s -> new StageInfo(s.getOrder(), s.getCode(), s.getNameCn(), s.getDescription()))
                .toList();
        return ResponseEntity.ok(AgentResponse.success("orchestrator", services));
    }

    /**
     * 独立运行数据分析服务（对已完成的作品执行，不进入主流水线）。
     */
    @PostMapping("/{workflowId}/analyze")
    @Operation(
            summary = "运行数据分析（独立服务）",
            description = "对指定工作流单独执行数据分析阶段，结果写入 accumulatedArtifacts['data-analysis']，不影响主流水线状态。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> runAnalysis(
            @Parameter(description = "工作流 ID", required = true)
            @PathVariable String workflowId) {
        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权访问该工作流"));
        }
        workflowService.runStandaloneStage(workflowId, AgentStage.DATA_ANALYSIS);
        return ResponseEntity.ok(AgentResponse.success(
                "orchestrator",
                Map.of("workflowId", workflowId,
                        "service", AgentStage.DATA_ANALYSIS.getCode(),
                        "message", "数据分析服务已启动（独立运行）")
        ));
    }

    /**
     * 独立运行优化迭代服务（对已完成的作品执行，不进入主流水线）。
     */
    @PostMapping("/{workflowId}/optimize")
    @Operation(
            summary = "运行优化迭代（独立服务）",
            description = "对指定工作流单独执行优化阶段，结果写入 accumulatedArtifacts['optimization']，不影响主流水线状态。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> runOptimize(
            @Parameter(description = "工作流 ID", required = true)
            @PathVariable String workflowId) {
        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权访问该工作流"));
        }
        workflowService.runStandaloneStage(workflowId, AgentStage.OPTIMIZATION);
        return ResponseEntity.ok(AgentResponse.success(
                "orchestrator",
                Map.of("workflowId", workflowId,
                        "service", AgentStage.OPTIMIZATION.getCode(),
                        "message", "优化迭代服务已启动（独立运行）")
        ));
    }

    /**
     * 下载作品到本地：将标题、正文（Markdown/HTML）、封面与配图清单打包为 ZIP。
     */
    @GetMapping("/{workflowId}/download")
    @Operation(
            summary = "下载作品（ZIP 打包）",
            description = "将已完成作品打包为 ZIP 下载：Markdown 原文、HTML 排版稿、封面/配图清单与 manifest 元数据。"
    )
    public ResponseEntity<byte[]> downloadWorkflow(
            @Parameter(description = "工作流 ID", required = true)
            @PathVariable String workflowId) {
        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(null);
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(null);
        }
        try {
            byte[] zip = workflowService.exportWorkflowZip(workflowId);
            String fileName = "contentops-work-" + workflowId.substring(0,
                    Math.min(8, workflowId.length())) + ".zip";
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + fileName + "\"")
                    .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/zip")
                    .body(zip);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getErrorCode() != null
                            ? e.getErrorCode().getHttpStatus()
                            : HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

    // ──────────────────── 用户人工上传 / 确定性编辑 ────────────────────

    /**
     * 上传封面图片替换 AI 生成封面（确定性生效：下载/渲染使用新封面）。
     */
    @PostMapping(value = "/{workflowId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "上传封面替换 AI 生成封面",
            description = "用户手动上传封面图片（png/jpg/jpeg/webp/gif），立即替换作品封面并生效于下载/渲染。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> uploadCover(
            @Parameter(description = "工作流 ID", required = true)
            @PathVariable String workflowId,
            @RequestParam("file") MultipartFile file) {
        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该工作流"));
        }
        FileStorageService.StoredFile stored = fileStorageService.store(file, "image");
        workflowService.setCoverImage(workflowId, stored.url());
        return ResponseEntity.ok(AgentResponse.success("orchestrator", Map.of(
                "coverImageUrl", stored.url(),
                "fileId", stored.fileId(),
                "replaced", true)));
    }

    /**
     * 上传创作素材文档（md/txt/pdf/docx），AI 自动分析选题或创作内容。
     */
    @PostMapping(value = "/{workflowId}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "上传创作素材",
            description = "上传文档作为创作素材：文本类（md/txt）自动提取全文注入 inputs，"
                    + "供选题/内容 Agent 分析后生成作品。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> uploadMaterial(
            @Parameter(description = "工作流 ID", required = true)
            @PathVariable String workflowId,
            @RequestParam("file") MultipartFile file) {
        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该工作流"));
        }
        FileStorageService.StoredFile stored = fileStorageService.store(file, "material");
        String content = fileStorageService.readTextContent(
                fileStorageService.resolve(stored.fileId()).orElse(null), stored.fileId());
        workflowService.addReferenceMaterial(
                workflowId, stored.originalName(), stored.url(), content);
        return ResponseEntity.ok(AgentResponse.success("orchestrator", Map.of(
                "fileId", stored.fileId(),
                "name", stored.originalName(),
                "url", stored.url(),
                "textExtracted", content != null && !content.isBlank())));
    }

    /**
     * 用户确定性修改标题/正文（可视化编辑直接落库）。
     */
    @PutMapping("/{workflowId}/content")
    @Operation(
            summary = "保存用户手动修改的标题/正文",
            description = "可视化编辑中直接输入标题或正文并保存，下载 ZIP 与渲染将使用新版本（确定性修改）。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> updateContent(
            @Parameter(description = "工作流 ID", required = true)
            @PathVariable String workflowId,
            @RequestBody UpdateContentRequest request) {
        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该工作流"));
        }
        try {
            Map<String, Object> data = workflowService.updateWorkflowContent(
                    workflowId, request.getTitle(), request.getContent());
            return ResponseEntity.ok(AgentResponse.success("orchestrator", data));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getErrorCode() != null
                            ? e.getErrorCode().getHttpStatus()
                            : HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AgentResponse.failure("orchestrator", e.getMessage()));
        }
    }

    @lombok.Data
    public static class UpdateContentRequest {
        private String title;
        private String content;
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

        DiscussionSession session = discussionSessionService.getSession(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "讨论会话不存在: " + sessionId));
        }
        if (!isDiscussionOwnerAllowed(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该讨论会话"));
        }

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

        DiscussionSession session = discussionSessionService.getSession(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "讨论会话不存在: " + sessionId));
        }
        if (!isDiscussionOwnerAllowed(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该讨论会话"));
        }

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
                    .ownerId(AuthContext.currentUserId())
                    .discussionSessionId(sessionId)
                    .currentStage(AgentStage.CONTENT_CREATION.getCode())
                    .accountProfile(null) // populated below if available
                    .inputs(new HashMap<>())
                    .accumulatedArtifacts(new HashMap<>())
                    .status(TaskStatus.PENDING.name())
                    .createdAt(LocalDateTime.now())
                    .requireHumanReview(false)
                    .maxCycles(2)  // 成本控制：讨论直达作品默认 2 轮
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

            // 作品与讨论会话建立归属关系：accountProfile 直接取自会话
            context.setAccountProfile(session.getAccountProfile());

            // 长期记忆 P2：讨论直达作品场景同样注入项目记忆（失败不阻断）
            projectMemoryService.enrichContextWithMemory(context);

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
     * 数据归属校验：鉴权关闭（未登录）时放行；鉴权开启时仅允许本人访问。
     */
    private boolean isOwnerAllowed(TaskContext context) {
        String currentUserId = AuthContext.currentUserId();
        if (currentUserId == null) {
            return true; // 鉴权未启用
        }
        return currentUserId.equals(context.getOwnerId());
    }

    /**
     * 讨论会话归属校验：鉴权关闭（未登录）时放行；鉴权开启时仅允许本人访问。
     */
    private boolean isDiscussionOwnerAllowed(DiscussionSession session) {
        String currentUserId = AuthContext.currentUserId();
        if (currentUserId == null) {
            return true; // 鉴权未启用
        }
        return currentUserId.equals(session.getOwnerId());
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
        DiscussionSession session = discussionSessionService.getSession(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "讨论会话不存在: " + sessionId));
        }
        if (!isDiscussionOwnerAllowed(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该讨论会话"));
        }
        return ResponseEntity.ok(AgentResponse.success("orchestrator", session));
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
        DiscussionSession session = discussionSessionService.getSession(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "讨论会话不存在: " + sessionId));
        }
        if (!isDiscussionOwnerAllowed(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该讨论会话"));
        }
        agentGateway.clearDiscussionSession(sessionId);
        return ResponseEntity.ok(AgentResponse.success("orchestrator", null));
    }

    // ──────────────────── 作品 ↔ 讨论会话：查看 / 聊天续改 ────────────────────

    /**
     * 查看一个作品的全部讨论/聊天记录（按更新时间倒序）。
     */
    @GetMapping("/{workflowId}/discussions")
    @Operation(
            summary = "查看作品的聊天记录",
            description = "返回绑定到该作品的所有讨论会话（含对话历史），仅本人可查看。"
    )
    public ResponseEntity<AgentResponse<List<DiscussionSession>>> listWorkflowDiscussions(
            @Parameter(description = "工作流 ID", required = true)
            @PathVariable String workflowId) {
        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该工作流"));
        }
        List<DiscussionSession> sessions = discussionSessionService.listSessionsByWorkflow(workflowId)
                .stream()
                .filter(this::isDiscussionOwnerAllowed)
                .toList();
        return ResponseEntity.ok(AgentResponse.success("orchestrator", sessions));
    }

    /**
     * 针对一个作品开启「聊天续改」会话：会话与作品建立归属关系。
     */
    @PostMapping("/{workflowId}/discuss/start")
    @Operation(
            summary = "针对作品开启聊天续改会话",
            description = "创建一个绑定到指定作品的讨论会话，用户可在会话中提出修改意见，"
                    + "随后调用 apply 将意见应用到作品上。"
    )
    public ResponseEntity<AgentResponse<DiscussionResponse>> startModifyDiscussion(
            @Parameter(description = "工作流 ID", required = true)
            @PathVariable String workflowId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "可选：初始修改意图（fuzzyIdea）",
                    required = false
            )
            @RequestBody(required = false) Map<String, Object> body) {
        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该工作流"));
        }

        String fuzzyIdea = body != null && body.get("fuzzyIdea") != null
                ? String.valueOf(body.get("fuzzyIdea"))
                : "我想继续完善这个作品，请根据我的意见修改。";
        DiscussionSession session = discussionSessionService.createSession(
                fuzzyIdea, context.getAccountProfile(), workflowId);

        String memoryId = discussionSessionService.getMemoryId(session.getSessionId());
        String aiReply = discussionAgent.discuss(memoryId, fuzzyIdea);
        discussionSessionService.addTurn(session, "user", fuzzyIdea);
        discussionSessionService.addTurn(session, "assistant", aiReply);
        discussionSessionService.updatePhase(session, discussionSessionService.detectPhase(aiReply));

        DiscussionResponse response = DiscussionResponse.builder()
                .sessionId(session.getSessionId())
                .phase(session.getPhase())
                .message(aiReply)
                .clarifyingQuestions(new java.util.ArrayList<>())
                .proposedDirections(new java.util.ArrayList<>())
                .canFinalize(discussionSessionService.canFinalize(session))
                .turnCount(session.getTurns() != null ? session.getTurns().size() : 0)
                .build();
        return ResponseEntity.ok(AgentResponse.success("orchestrator", response));
    }

    /**
     * 将聊天会话中的最新修改意见应用到作品（重新生成草稿，下载/渲染基于最新版本）。
     */
    @PostMapping("/{workflowId}/discuss/{sessionId}/apply")
    @Operation(
            summary = "应用聊天修改到作品",
            description = "取会话最后一条用户消息作为修改意见，调用内容 Agent 重新生成草稿并合并到作品，"
                    + "随后下载 ZIP 即为更新后的版本。"
    )
    public ResponseEntity<AgentResponse<Map<String, Object>>> applyDiscussionModification(
            @Parameter(description = "工作流 ID", required = true)
            @PathVariable String workflowId,
            @Parameter(description = "讨论会话 ID", required = true)
            @PathVariable String sessionId) {
        TaskContext context = workflowService.getWorkflowStatus(workflowId);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "Workflow not found: " + workflowId));
        }
        if (!isOwnerAllowed(context)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该工作流"));
        }
        DiscussionSession session = discussionSessionService.getSession(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentResponse.failure("orchestrator", "讨论会话不存在: " + sessionId));
        }
        if (!isDiscussionOwnerAllowed(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(AgentResponse.failure("orchestrator", "无权限访问该讨论会话"));
        }
        if (!workflowId.equals(session.getWorkflowId()) && !workflowId.equals(session.getSessionId())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AgentResponse.failure("orchestrator", "该会话不属于此作品"));
        }
        try {
            Map<String, Object> data = workflowService.applyDiscussionModification(workflowId, sessionId);
            return ResponseEntity.ok(AgentResponse.success("orchestrator", data));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getErrorCode() != null
                            ? e.getErrorCode().getHttpStatus()
                            : HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AgentResponse.failure("orchestrator", e.getMessage()));
        }
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
        @Schema(description = "创建时指定放入的作品合集 ID 列表（可选，按类型归集同类作品）", example = "[]")
        private java.util.List<String> collectionIds;
        @Schema(description = "各平台账号映射：key 为平台中文名或 code，value 含 accountId/accountName")
        private java.util.Map<String, PlatformAccountInfo> platformAccounts;
        @Schema(description = "是否需要人工审核（为 true 时每个阶段完成后暂停等待审批）", example = "false")
        private boolean requireHumanReview;
        @Schema(description = "发布作品模式：text-cover（文字+封面，默认）/ image-text（图文混排）/ full-image（全图卡片）",
                example = "text-cover")
        private String publishMode;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @Schema(description = "平台账号信息")
    public static class PlatformAccountInfo {
        @Schema(description = "平台账号 ID")
        private String accountId;
        @Schema(description = "平台账号名称")
        private String accountName;
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
