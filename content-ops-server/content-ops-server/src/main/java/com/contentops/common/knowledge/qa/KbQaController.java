package com.contentops.common.knowledge.qa;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
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
 * 知识库问答 + RAG 错误识别接口。
 *
 * <p>对应五层错误识别：{@code /ask}（在线生成 + 检索侧 + 引用 + 忠实度）、
 * {@code /feedback}·{@code /citation-click}·{@code /handoff}（隐式信号）、
 * {@code /risk-log}·{@code /review-queue}（人工过清单）、{@code /offline/*}（聚类与回归）。
 */
@RestController
@RequestMapping("/api/v1/kb/qa")
@RequiredArgsConstructor
@Tag(name = "知识库问答（RAG 错误识别）")
public class KbQaController {

    private final KbQaService kbQaService;
    private final QaImplicitSignalService implicitSignalService;
    private final QaOfflineMiner offlineMiner;

    @PostMapping("/ask")
    @Operation(summary = "提问：检索 → 充分性判断 → 接地生成 → 引用校验 → 忠实度校验，并落库全部质量信号")
    public AgentResponse<Map<String, Object>> ask(@RequestBody AskRequest request) {
        String ownerId = AuthContext.currentUserId();
        QaRecord record = kbQaService.ask(request.getQuestion(), ownerId, request.getTopK(), request.getFilters());
        List<QaSignal> signals = implicitSignalService.detectOnNewQuestion(
                record.getQaId(), record.getQuestion(), ownerId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("record", record);
        data.put("signals", signals);
        return AgentResponse.success("kb-qa", data);
    }

    @GetMapping("/{qaId}")
    @Operation(summary = "问答详情（含全部质量信号与反馈信号）")
    public AgentResponse<Map<String, Object>> detail(@PathVariable String qaId) {
        QaRecord record = kbQaService.require(qaId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("record", record);
        data.put("signals", implicitSignalService.signalsOf(qaId));
        return AgentResponse.success("kb-qa", data);
    }

    @GetMapping("/records")
    @Operation(summary = "问答记录列表（可按复核状态 / 风险等级 / 拒答 / 疑似错误过滤）")
    public AgentResponse<Map<String, Object>> records(
            @RequestParam(required = false) String reviewState,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false, defaultValue = "false") boolean refusedOnly,
            @RequestParam(required = false, defaultValue = "false") boolean suspectedOnly,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        List<QaRecord> records = kbQaService.list(AuthContext.currentUserId(), reviewState, riskLevel,
                refusedOnly, suspectedOnly, limit == null ? 50 : limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", records.size());
        data.put("records", records);
        return AgentResponse.success("kb-qa", data);
    }

    @GetMapping("/risk-log")
    @Operation(summary = "高风险检索日志：拒答 / 检索侧风险信号 / 高风险答案（人工优先过清单）")
    public AgentResponse<Map<String, Object>> riskLog(
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        List<QaRecord> records = kbQaService.riskLog(AuthContext.currentUserId(), limit == null ? 50 : limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", records.size());
        data.put("records", records);
        return AgentResponse.success("kb-qa", data);
    }

    @GetMapping("/review-queue")
    @Operation(summary = "人工复核清单（默认 NEEDS_REVIEW）")
    public AgentResponse<Map<String, Object>> reviewQueue(
            @RequestParam(required = false) String reviewState,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        String state = reviewState == null || reviewState.isBlank() ? "NEEDS_REVIEW" : reviewState;
        List<QaRecord> records = kbQaService.list(AuthContext.currentUserId(), state, null,
                false, false, limit == null ? 50 : limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reviewState", state);
        data.put("total", records.size());
        data.put("records", records);
        return AgentResponse.success("kb-qa", data);
    }

    @PutMapping("/{qaId}/review")
    @Operation(summary = "人工复核完成：标记 REVIEWED（可传 reviewState 覆盖）")
    public AgentResponse<QaRecord> review(@PathVariable String qaId,
                                          @RequestBody(required = false) ReviewRequest request) {
        kbQaService.updateReviewState(qaId, request == null ? null : request.getReviewState());
        return kbQaService.find(qaId)
                .map(record -> AgentResponse.success("kb-qa", record))
                .orElseGet(() -> AgentResponse.failure("kb-qa", "问答记录不存在: " + qaId));
    }

    @PostMapping("/{qaId}/recheck")
    @Operation(summary = "复检：用当前检索结果对已存答案重新做引用/忠实度标记（不改答案文本）")
    public AgentResponse<QaRecord> recheck(@PathVariable String qaId) {
        return AgentResponse.success("kb-qa", kbQaService.recheck(qaId));
    }

    @PostMapping("/{qaId}/feedback")
    @Operation(summary = "显式反馈：点踩（helpful=false）会记为高置信度错误样本")
    public AgentResponse<Map<String, Object>> feedback(@PathVariable String qaId,
                                                       @RequestBody FeedbackRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (request != null && !request.isHelpful()) {
            data.put("signal", implicitSignalService.explicitDislike(
                    qaId, AuthContext.currentUserId(), request.getReason()));
        }
        data.put("signals", implicitSignalService.signalsOf(qaId));
        return AgentResponse.success("kb-qa", data);
    }

    @PostMapping("/{qaId}/citation-click")
    @Operation(summary = "隐式信号：引用章节点击（停留过短 → 引用没帮上忙）")
    public AgentResponse<Map<String, Object>> citationClick(@PathVariable String qaId,
                                                            @RequestBody CitationClickRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (request != null) {
            data.put("signal", implicitSignalService.citationClick(
                    qaId, AuthContext.currentUserId(), request.getDwellMs()));
        }
        return AgentResponse.success("kb-qa", data);
    }

    @PostMapping("/{qaId}/handoff")
    @Operation(summary = "隐式信号：转人工结果回填（确认答案错误 → 疑似错误样本）")
    public AgentResponse<Map<String, Object>> handoff(@PathVariable String qaId,
                                                      @RequestBody HandoffRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (request != null) {
            data.put("signal", implicitSignalService.handoff(qaId, AuthContext.currentUserId(),
                    request.isAnswerWasWrong(), request.getNote()));
        }
        return AgentResponse.success("kb-qa", data);
    }

    @GetMapping("/stats")
    @Operation(summary = "概览：总量 / 拒答 / 高风险 / 待复核 / 疑似错误 + 信号类型分布")
    public AgentResponse<Map<String, Object>> stats() {
        String ownerId = AuthContext.currentUserId();
        Map<String, Object> data = new LinkedHashMap<>(kbQaService.stats(ownerId));
        data.put("signalTypeStats", implicitSignalService.typeStats(ownerId));
        return AgentResponse.success("kb-qa", data);
    }

    @PostMapping("/offline/cluster")
    @Operation(summary = "离线聚类：把拒答/高风险/引用不实/疑似错误样本聚成问题簇，发现知识库盲区")
    public AgentResponse<Map<String, Object>> cluster(
            @RequestParam(required = false, defaultValue = "REFUSED") String scope,
            @RequestParam(required = false, defaultValue = "200") Integer limit,
            @RequestParam(required = false) Double threshold) {
        List<QaOfflineMiner.QaCluster> clusters = offlineMiner.cluster(
                AuthContext.currentUserId(), scope, limit == null ? 200 : limit, threshold);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", scope);
        data.put("clusterCount", clusters.size());
        data.put("clusters", clusters);
        return AgentResponse.success("kb-qa", data);
    }

    @PostMapping("/offline/promote")
    @Operation(summary = "把错误样本并入评测集（contentops_llm_eval_case），改检索/Prompt 后跑回归")
    public AgentResponse<Map<String, Object>> promote(@RequestBody PromoteRequest request) {
        return AgentResponse.success("kb-qa", offlineMiner.promote(
                AuthContext.currentUserId(), request == null ? List.of() : request.getQaIds()));
    }

    @GetMapping("/offline/report")
    @Operation(summary = "盲区总览：样本数 / 簇数 / 信号分布")
    public AgentResponse<Map<String, Object>> offlineReport(
            @RequestParam(required = false, defaultValue = "200") Integer limit) {
        return AgentResponse.success("kb-qa", offlineMiner.report(
                AuthContext.currentUserId(), limit == null ? 200 : limit));
    }

    @Data
    public static class AskRequest {
        private String question;
        private Integer topK;
        private Map<String, String> filters;
    }

    @Data
    public static class ReviewRequest {
        private String reviewState;
    }

    @Data
    public static class FeedbackRequest {
        private boolean helpful = true;
        private String reason;
    }

    @Data
    public static class CitationClickRequest {
        private long dwellMs;
    }

    @Data
    public static class HandoffRequest {
        private boolean answerWasWrong;
        private String note;
    }

    @Data
    public static class PromoteRequest {
        private List<String> qaIds;
    }
}
