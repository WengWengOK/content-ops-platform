package com.contentops.comment;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.security.AuthContext;
import com.contentops.common.security.RequireRole;
import com.contentops.common.security.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论区 AI 助手（MVP：小红书）：
 * 作品发布后的评论采集 → 意图/情感分析 → 多轮 AI 对话 → 审核/发送。
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

    /** 开发模式（未开启鉴权）时 ownerId 为 null，SQL 侧自动不过滤，保证联调可用 */
    private String ownerId() {
        return AuthContext.currentUserId();
    }

    @PostMapping("/collect")
    @Operation(summary = "采集评论（MVP 模拟小红书数据源）")
    public AgentResponse<Map<String, Object>> collect(@RequestBody CollectRequest request) {
        if (request.getWorkId() == null || request.getWorkId().isBlank()) {
            return AgentResponse.failure("comment", "workId 不能为空");
        }
        List<Comment> comments = collector.collectFromPlatform(request.getWorkId().trim(), ownerId());
        int inserted = 0;
        for (Comment c : comments) {
            long before = repository.findById(c.getCommentId()).isPresent() ? 1 : 0;
            repository.insert(c);
            long after = repository.findById(c.getCommentId()).isPresent() ? 1 : 0;
            if (after > before) {
                inserted++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("collected", comments.size());
        data.put("inserted", inserted);
        data.put("comments", comments);
        return AgentResponse.success("comment", data);
    }

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

    @GetMapping("/{commentId}")
    @Operation(summary = "评论详情")
    public AgentResponse<Comment> get(@PathVariable String commentId) {
        return repository.findById(commentId)
                .map(c -> AgentResponse.success("comment", c))
                .orElseGet(() -> AgentResponse.failure("comment", "评论不存在: " + commentId));
    }

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
                repository.updateAnalysis(c.getCommentId(), analyzed.getIntent(),
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
        Comment comment = repository.findById(commentId)
                .orElse(null);
        if (comment == null) {
            return AgentResponse.failure("comment", "评论不存在: " + commentId);
        }
        Comment analyzed = analysisService.analyze(comment);
        repository.updateAnalysis(commentId, analyzed.getIntent(), analyzed.getSentiment(),
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
        Comment comment = replyService.approve(commentId);
        return AgentResponse.success("comment", comment);
    }

    @PostMapping("/{commentId}/send")
    @Operation(summary = "发送回复：APPROVED → SENT（MVP 模拟发送）")
    public AgentResponse<Comment> send(@PathVariable String commentId) {
        Comment comment = replyService.send(commentId);
        return AgentResponse.success("comment", comment);
    }

    @PutMapping("/{commentId}/reply")
    @Operation(summary = "人工修改回复内容/状态")
    public AgentResponse<Comment> updateReply(@PathVariable String commentId,
                                              @RequestBody UpdateReplyRequest request) {
        Comment comment = replyService.updateReply(commentId, request.getReply(), request.getStatus());
        return AgentResponse.success("comment", comment);
    }

    private String blank(String s) {
        return s == null ? "" : s;
    }

    @Data
    public static class CollectRequest {
        @NotBlank(message = "workId 不能为空")
        private String workId;
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
}
