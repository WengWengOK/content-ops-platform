package com.contentops.comment;

import com.contentops.common.audit.AuditService;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.common.security.AuthContext;
import com.contentops.common.security.RequireRole;
import com.contentops.common.security.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评论区 AI 助手（小红书优先）：
 * 评论采集（真实接口/模拟）→ 意图情感分析 → 多轮 AI 对话 → 审核发送，
 * 并支持把作品加入「自动采集监控」由定时任务持续拉取新评论。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/comments")
@RequiredArgsConstructor
@RequireRole(UserRole.CREATOR)
@Tag(name = "评论区 AI 助手")
public class CommentController {

    private final CommentCollector collector;
    private final CommentRepository repository;
    private final CommentAnalysisService analysisService;
    private final CommentReplyService replyService;
    private final CommentWatchRepository watchRepository;
    private final CommentCollectionJob collectionJob;
    private final CommentJobRunRepository jobRunRepository;
    private final CommentProperties properties;
    private final XhsCommentApiClient apiClient;
    private final AuditService auditService;

    /** 开发模式（未开启鉴权）时 ownerId 为 null，SQL 侧自动不过滤，保证联调可用 */
    private String ownerId() {
        return AuthContext.currentUserId();
    }

    // ──────────────────────── 采集 ────────────────────────

    @PostMapping("/collect")
    @Operation(summary = "采集评论（按配置走真实接口或模拟数据源）")
    public AgentResponse<Map<String, Object>> collect(@RequestBody CollectRequest request) {
        if (request.getWorkId() == null || request.getWorkId().isBlank()) {
            return AgentResponse.failure("comment", "workId 不能为空");
        }
        String platform = blank(request.getPlatform()).isBlank() ? "xiaohongshu" : request.getPlatform().trim();
        CommentCollector.CollectionResult result;
        try {
            result = collector.collect(platform, request.getWorkId().trim(), ownerId(),
                    request.getXsecToken());
        } catch (CommentSourceException e) {
            return AgentResponse.failure("comment", "[" + e.getCode() + "] " + e.getMessage());
        }

        int inserted = 0;
        for (Comment c : result.commentsOrEmpty()) {
            if (repository.exists(c.getCommentId())) {
                continue;
            }
            repository.insert(c);
            if (repository.exists(c.getCommentId())) {
                inserted++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("collected", result.commentsOrEmpty().size());
        data.put("inserted", inserted);
        data.put("source", result.source());
        data.put("fallbackReason", result.fallbackReason());
        data.put("comments", result.commentsOrEmpty());
        return AgentResponse.success("comment", data);
    }

    @GetMapping("/source-status")
    @Operation(summary = "数据源诊断：真实接口是否已配置及排查提示")
    public AgentResponse<Map<String, Object>> sourceStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configuredSource", properties.getSource());
        data.put("xiaohongshuEnabled", properties.getXiaohongshu().isEnabled());
        data.put("xiaohongshuConfigured", apiClient.isConfigured());
        data.put("xiaohongshuEndpoint", properties.getXiaohongshu().getEndpoint());
        data.put("xiaohongshuAuthMode", properties.getXiaohongshu().getAuthMode());
        data.put("fallbackToMock", properties.isFallbackToMock());
        data.put("hint", apiClient.statusHint());
        data.put("xiaohongshuReplyEnabled", properties.getXiaohongshu().isReplyEnabled());
        data.put("xiaohongshuReplyConfigured", apiClient.isReplyConfigured());
        data.put("replyHint", apiClient.replyStatusHint());
        data.put("platformNote",
                "小红书官方开放平台当前仅开放电商类 API（订单/售后/商品/库存/物流/财务），"
                        + "未提供笔记评论接口；真实评论数据请配置第三方数据服务或自建采集桥的 endpoint + access-token");
        return AgentResponse.success("comment", data);
    }

    // ──────────────────────── 列表 / 统计 ────────────────────────

    @GetMapping
    @Operation(summary = "评论列表（平台/作品/意图/情感过滤）")
    public AgentResponse<Map<String, Object>> list(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String workId,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) String sentiment,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        List<Comment> comments = repository.list(
                ownerId(), blank(platform), blank(workId), blank(intent), blank(sentiment),
                Math.min(limit == null ? 50 : limit, 200));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", comments.size());
        data.put("comments", comments);
        return AgentResponse.success("comment", data);
    }

    @GetMapping("/stats")
    @Operation(summary = "意图/情感统计")
    public AgentResponse<Map<String, Object>> stats(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String workId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intent", repository.statsIntent(ownerId(), blank(platform), blank(workId)));
        data.put("sentiment", repository.statsSentiment(ownerId(), blank(platform), blank(workId)));
        return AgentResponse.success("comment", data);
    }

    // ──────────────────────── 自动采集监控 ────────────────────────

    @GetMapping("/watches")
    @Operation(summary = "我的评论监控作品列表")
    public AgentResponse<List<CommentWatch>> watches() {
        return AgentResponse.success("comment", watchRepository.list(ownerId(), 100));
    }

    @PostMapping("/watches")
    @Operation(summary = "把作品加入自动采集监控（可选自动分析）")
    public AgentResponse<CommentWatch> addWatch(@RequestBody AddWatchRequest request) {
        if (request.getWorkId() == null || request.getWorkId().isBlank()) {
            return AgentResponse.failure("comment", "workId 不能为空");
        }
        String platform = blank(request.getPlatform()).isBlank() ? "xiaohongshu" : request.getPlatform().trim();
        String owner = ownerId();
        CommentWatch existing = watchRepository.findByWork(platform, request.getWorkId().trim(), owner).orElse(null);
        if (existing != null) {
            return AgentResponse.success("comment", existing,
                    Map.of("message", "该作品已在监控列表中"));
        }
        CommentWatch watch = CommentWatch.builder()
                .watchId(UUID.randomUUID().toString())
                .ownerId(owner)
                .platform(platform)
                .workId(request.getWorkId().trim())
                .workflowId(blank(request.getWorkflowId()))
                .xsecToken(blank(request.getXsecToken()))
                .autoAnalyze(request.getAutoAnalyze() == null ? properties.isAutoAnalyze() : request.getAutoAnalyze())
                .enabled(true)
                .totalCollected(0)
                .createdAt(LocalDateTime.now())
                .build();
        watchRepository.insert(watch);
        auditService.record("COMMENT_WATCH_ADD", "comment-watch", watch.getWatchId(),
                "添加评论监控作品：" + platform + "/" + watch.getWorkId());
        return AgentResponse.success("comment", watch);
    }

    @PutMapping("/watches/{watchId}/enabled")
    @Operation(summary = "启用/暂停监控作品")
    public AgentResponse<Map<String, Object>> setWatchEnabled(@PathVariable String watchId,
                                                              @RequestBody SetEnabledRequest request) {
        CommentWatch watch = requireWatch(watchId);
        watchRepository.updateEnabled(watch.getWatchId(), request.isEnabled());
        auditService.record("COMMENT_WATCH_TOGGLE", "comment-watch", watchId,
                request.isEnabled() ? "启用评论监控" : "暂停评论监控");
        return AgentResponse.success("comment", Map.of(
                "watchId", watchId,
                "enabled", request.isEnabled()));
    }

    @DeleteMapping("/watches/{watchId}")
    @Operation(summary = "移除监控作品")
    public AgentResponse<Map<String, Object>> removeWatch(@PathVariable String watchId) {
        CommentWatch watch = requireWatch(watchId);
        watchRepository.delete(watch.getWatchId());
        auditService.record("COMMENT_WATCH_REMOVE", "comment-watch", watchId, "移除评论监控作品");
        return AgentResponse.success("comment", Map.of("removed", true, "watchId", watchId));
    }

    @PostMapping("/watches/{watchId}/run")
    @Operation(summary = "立即采集该监控作品的新评论")
    public AgentResponse<Map<String, Object>> runWatch(@PathVariable String watchId) {
        CommentWatch watch = requireWatch(watchId);
        CommentCollectionJob.WatchRunResult result =
                collectionJob.runForWatch(watch, Math.max(0, properties.getAutoAnalyzeLimit()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("watchId", result.watchId());
        data.put("workId", result.workId());
        data.put("source", result.source());
        data.put("fallbackReason", result.fallbackReason());
        data.put("collected", result.collected());
        data.put("inserted", result.inserted());
        data.put("analyzed", result.analyzed());
        data.put("error", result.error());
        return result.error() == null
                ? AgentResponse.success("comment", data)
                : AgentResponse.failure("comment", result.error());
    }

    @PostMapping("/watches/run-all")
    @Operation(summary = "立即执行一轮批量采集（等价于一次定时任务）")
    public AgentResponse<CommentJobRun> runAll() {
        CommentJobRun run = collectionJob.runOnce("manual");
        return AgentResponse.success("comment", run);
    }

    @GetMapping("/scheduler")
    @Operation(summary = "定时采集状态：间隔、自动分析、监控数量、最近一次运行")
    public AgentResponse<Map<String, Object>> schedulerStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.isEnabled());
        data.put("scheduled", properties.isScheduled());
        data.put("collectMs", properties.getCollectMs());
        data.put("maxWorksPerTick", properties.getMaxWorksPerTick());
        data.put("autoAnalyze", properties.isAutoAnalyze());
        data.put("autoAnalyzeLimit", properties.getAutoAnalyzeLimit());
        data.put("minIntervalSeconds", properties.getXiaohongshu().getMinIntervalSeconds());
        data.put("watchCount", watchRepository.list(ownerId(), 500).size());
        data.put("lastRun", jobRunRepository.latest().orElse(null));
        data.put("timestamp", LocalDateTime.now());
        return AgentResponse.success("comment", data);
    }

    // ──────────────────────── 分析 / 对话 / 回复 ────────────────────────

    @PostMapping("/analyze-all")
    @Operation(summary = "批量分析某作品的评论（意图/情感/摘要/回复草稿）")
    public AgentResponse<Map<String, Object>> analyzeAll(@RequestBody AnalyzeAllRequest request) {
        List<Comment> comments = repository.list(
                ownerId(), blank(request.getPlatform()), blank(request.getWorkId()),
                "", "", Math.min(request.getLimit() == null ? 50 : request.getLimit(), 200));
        List<Comment> updated = new ArrayList<>();
        for (Comment c : comments) {
            if (c.getIntent() == null || c.getIntent().isBlank()) {
                Comment analyzed = analysisService.analyze(c);
                repository.updateAnalysisAndStatus(c.getCommentId(), analyzed.getIntent(),
                        analyzed.getSentiment(), analyzed.getAiSummary(), analyzed.getAiReply());
                updated.add(analyzed);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("analyzed", updated.size());
        data.put("comments", updated);
        return AgentResponse.success("comment", data);
    }

    @PostMapping("/{commentId}/analyze")
    @Operation(summary = "单条评论 AI 分析")
    public AgentResponse<Comment> analyze(@PathVariable String commentId) {
        Comment comment = repository.findById(commentId).orElse(null);
        if (comment == null) {
            return AgentResponse.failure("comment", "评论不存在: " + commentId);
        }
        Comment analyzed = analysisService.analyze(comment);
        repository.updateAnalysisAndStatus(commentId, analyzed.getIntent(), analyzed.getSentiment(),
                analyzed.getAiSummary(), analyzed.getAiReply());
        return AgentResponse.success("comment", analyzed);
    }

    @PostMapping("/{commentId}/reply/chat")
    @Operation(summary = "多轮 AI 对话（生成回复草稿）")
    public AgentResponse<Comment> chat(@PathVariable String commentId,
                                       @RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return AgentResponse.failure("comment", "message 不能为空");
        }
        Comment comment = replyService.chat(commentId, request.getMessage().trim());
        return AgentResponse.success("comment", comment);
    }

    @PostMapping("/{commentId}/approve")
    @Operation(summary = "审核通过：DRAFT → APPROVED")
    public AgentResponse<Comment> approve(@PathVariable String commentId) {
        return AgentResponse.success("comment", replyService.approve(commentId));
    }

    @PostMapping("/{commentId}/send")
    @Operation(summary = "发送回复：APPROVED → SENT（已配置真实接口则真实回复，否则模拟发送）")
    public AgentResponse<Map<String, Object>> send(@PathVariable String commentId,
                                                   @RequestBody(required = false) SendRequest request) {
        CommentReplyService.SendResult result =
                replyService.send(commentId, request == null ? null : request.getXsecToken());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("comment", result.comment());
        data.put("sendMode", result.sendMode());
        data.put("message", result.message());
        return AgentResponse.success("comment", data);
    }

    @PutMapping("/{commentId}/reply")
    @Operation(summary = "人工修改回复内容/状态")
    public AgentResponse<Comment> updateReply(@PathVariable String commentId,
                                              @RequestBody UpdateReplyRequest request) {
        return AgentResponse.success("comment",
                replyService.updateReply(commentId, request.getReply(), request.getStatus()));
    }

    @GetMapping("/{commentId}")
    @Operation(summary = "评论详情")
    public AgentResponse<Comment> get(@PathVariable String commentId) {
        return repository.findById(commentId)
                .map(c -> AgentResponse.success("comment", c))
                .orElseGet(() -> AgentResponse.failure("comment", "评论不存在: " + commentId));
    }

    // ──────────────────────── 内部工具 ────────────────────────

    private CommentWatch requireWatch(String watchId) {
        CommentWatch watch = watchRepository.findById(watchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "监控项不存在: " + watchId));
        String owner = ownerId();
        if (owner != null && watch.getOwnerId() != null && !owner.equals(watch.getOwnerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该监控项");
        }
        return watch;
    }

    private String blank(String s) {
        return s == null ? "" : s;
    }

    @Data
    public static class CollectRequest {
        @NotBlank(message = "workId 不能为空")
        private String workId;
        private String platform;
        /** 自建桥（xiaohongshu-mcp）必填：笔记访问票据 */
        private String xsecToken;
    }

    @Data
    public static class AddWatchRequest {
        @NotBlank(message = "workId 不能为空")
        private String workId;
        private String platform;
        private String workflowId;
        private Boolean autoAnalyze;
        /** 自建桥（xiaohongshu-mcp）必填：笔记访问票据 */
        private String xsecToken;
    }

    @Data
    public static class SetEnabledRequest {
        private boolean enabled = true;
    }

    @Data
    public static class AnalyzeAllRequest {
        private String workId;
        private String platform;
        private Integer limit;
    }

    @Data
    public static class ChatRequest {
        @NotBlank(message = "message 不能为空")
        private String message;
    }

    @Data
    public static class UpdateReplyRequest {
        private String reply;
        private String status;
    }

    @Data
    public static class SendRequest {
        /** 可选：真实发送所需的 xsec_token（不传则取该作品的监控项/全局默认值） */
        private String xsecToken;
    }
}