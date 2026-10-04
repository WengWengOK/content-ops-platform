package com.contentops.comment;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.security.AuthContext;
import com.contentops.common.security.RequireRole;
import com.contentops.common.security.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图语料库接口：维护「常见说法 → 标准术语」，查看命中率并做阈值调优预览。
 *
 * <p>匹配策略：归一化精确匹配 → 向量相似度 ≥ 阈值（默认 0.95）→ 未命中交给模型工作流。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/comments/intent-corpus")
@RequiredArgsConstructor
@RequireRole(UserRole.CREATOR)
@Tag(name = "意图语料库")
public class IntentCorpusController {

    private final IntentCorpusService corpusService;
    private final CommentRepository commentRepository;

    private String ownerId() {
        return AuthContext.currentUserId();
    }

    @GetMapping
    @Operation(summary = "语料库列表 + 概览（规模/按意图分布/缓存状态）")
    public AgentResponse<Map<String, Object>> list() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stats", corpusService.stats(ownerId()));
        data.put("entries", corpusService.list(ownerId()));
        return AgentResponse.success("comment", data);
    }

    @GetMapping("/stats")
    @Operation(summary = "语料库概览 + 评论侧意图来源分布（命中率观测）")
    public AgentResponse<Map<String, Object>> stats(@RequestParam(required = false) String platform,
                                                    @RequestParam(required = false) String workId) {
        Map<String, Object> data = new LinkedHashMap<>(corpusService.stats(ownerId()));
        data.put("commentSources", commentRepository.statsIntentSource(ownerId(),
                platform == null ? "" : platform, workId == null ? "" : workId));
        return AgentResponse.success("comment", data);
    }

    @PostMapping
    @Operation(summary = "新增语料（重复短语自动跳过）")
    public AgentResponse<IntentCorpusEntry> add(@RequestBody AddEntryRequest request) {
        IntentCorpusEntry entry = corpusService.add(ownerId(), request.getIntent(), request.getPhrase());
        if (entry == null) {
            return AgentResponse.failure("comment", "该说法已存在于语料库中");
        }
        corpusService.rebuild(ownerId());
        return AgentResponse.success("comment", entry);
    }

    @DeleteMapping("/{corpusId}")
    @Operation(summary = "删除语料")
    public AgentResponse<Map<String, Object>> delete(@PathVariable String corpusId) {
        corpusService.delete(corpusId);
        corpusService.rebuild(ownerId());
        return AgentResponse.success("comment", Map.of("deleted", true, "corpusId", corpusId));
    }

    @PutMapping("/{corpusId}/enabled")
    @Operation(summary = "启用/停用某条语料")
    public AgentResponse<Map<String, Object>> setEnabled(@PathVariable String corpusId,
                                                         @RequestBody SetEnabledRequest request) {
        corpusService.setEnabled(corpusId, request.isEnabled());
        corpusService.rebuild(ownerId());
        return AgentResponse.success("comment", Map.of("corpusId", corpusId, "enabled", request.isEnabled()));
    }

    @PostMapping("/rebuild")
    @Operation(summary = "重建向量缓存（批量改动语料后调用）")
    public AgentResponse<Map<String, Object>> rebuild() {
        int size = corpusService.rebuild(ownerId());
        return AgentResponse.success("comment", Map.of("rebuilt", true, "cacheSize", size));
    }

    @PostMapping("/match")
    @Operation(summary = "匹配预览：给定文本返回命中结果与最相近候选（用于阈值调优）")
    public AgentResponse<Map<String, Object>> match(@RequestBody MatchRequest request) {
        double threshold = request.getThreshold() == null ? 0 : request.getThreshold();
        IntentCorpusService.MatchResult result = threshold > 0
                ? corpusService.match(request.getText(), ownerId(), threshold)
                : corpusService.match(request.getText(), ownerId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matched", result.matched());
        data.put("intent", result.intent());
        data.put("phrase", result.phrase());
        data.put("score", result.score());
        data.put("matchedBy", result.matchedBy());
        data.put("candidates", corpusService.preview(request.getText(), ownerId(),
                request.getTopK() == null ? 5 : request.getTopK()));
        return AgentResponse.success("comment", data);
    }

    @Data
    public static class AddEntryRequest {
        private String intent;
        private String phrase;
    }

    @Data
    public static class SetEnabledRequest {
        private boolean enabled = true;
    }

    @Data
    public static class MatchRequest {
        private String text;
        private Double threshold;
        private Integer topK;
    }
}