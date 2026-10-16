package com.contentops.common.agent.external;

import com.contentops.common.audit.AuditService;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.common.rag.DocumentIngestionPipeline;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 Agent（CrewAI）统一入口。
 *
 * <p>平台侧发起 → crew 用平台工具做研究 → 产出**回写知识库**（进 RAG 与评测面）→ 记审计。
 * 这样外部 crew 不需要数据库凭据，调用点显式、可计费、可熔断。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/external-agents")
@RequiredArgsConstructor
@Tag(name = "外部 Agent（CrewAI 研究 crew）")
public class ExternalAgentController {

    private final ExternalAgentProperties properties;
    private final CrewAiResearchClient crewAiResearchClient;
    private final DocumentIngestionPipeline ingestionPipeline;
    private final AuditService auditService;

    @GetMapping("/status")
    @Operation(summary = "外部 Agent 状态：是否启用、crew 服务探活、是否回写知识库")
    public AgentResponse<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.isEnabled());
        data.put("crewaiEnabled", properties.getCrewai().isEnabled());
        data.put("baseUrl", properties.getCrewai().getBaseUrl());
        data.put("writeBackToKb", properties.getCrewai().isWriteBackToKb());
        data.put("timeoutMs", properties.getCrewai().getTimeoutMs());
        data.put("crew", crewAiResearchClient.enabled() ? crewAiResearchClient.health()
                : Map.of("status", "disabled"));
        return AgentResponse.success("external-agent", data);
    }

    @PostMapping("/research/run")
    @Operation(summary = "触发一次 CrewAI 选题研究，并（可选）把报告回写知识库")
    public AgentResponse<Map<String, Object>> runResearch(@RequestBody ResearchRunRequest request) {
        if (!properties.isEnabled() || !properties.getCrewai().isEnabled()) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "外部研究 Agent 未启用：请设置 contentops.external-agents.crewai.enabled=true");
        }
        if (request == null || request.getTopic() == null || request.getTopic().isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "topic");
        }
        String topic = request.getTopic().trim();
        String niche = request.getNiche() == null || request.getNiche().isBlank()
                ? properties.getCrewai().getDefaultNiche() : request.getNiche().trim();
        long started = System.currentTimeMillis();

        Map<String, Object> result;
        try {
            result = crewAiResearchClient.research(topic, niche, request.getPlatforms(),
                    request.getMaxTopics(), request.getExecutor());
        } catch (Exception e) {
            log.warn("[ExternalAgent] 研究失败: topic={}, err={}", topic, e.getMessage());
            throw new BusinessException(ErrorCode.AGENT_CALL_FAILED, "CrewAI 研究失败：" + e.getMessage());
        }

        Map<String, Object> ingest = null;
        String report = asString(result.get("reportMarkdown"));
        if (properties.getCrewai().isWriteBackToKb() && report != null && !report.isBlank()) {
            ingest = writeBack(topic, niche, report);
        }

        int topicCount = result.get("topicCandidates") instanceof List<?> list ? list.size() : 0;
        auditService.record("EXTERNAL_AGENT_RESEARCH", "topic", topic,
                "CrewAI 研究：executor=" + result.get("executor") + "，选题 " + topicCount
                        + " 个，回写知识库=" + (ingest != null && Boolean.TRUE.equals(ingest.get("success"))));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("niche", niche);
        data.put("executor", result.get("executor"));
        data.put("hotAngles", result.get("hotAngles"));
        data.put("topicCandidates", result.get("topicCandidates"));
        data.put("factCheck", result.get("factCheck"));
        data.put("evidenceDigest", result.get("evidenceDigest"));
        data.put("reportMarkdown", report);
        data.put("ingest", ingest);
        data.put("latencyMs", System.currentTimeMillis() - started);
        log.info("[ExternalAgent] 研究完成：topic={}, executor={}, topics={}, ingest={}, latencyMs={}",
                topic, result.get("executor"), topicCount, ingest, data.get("latencyMs"));
        return AgentResponse.success("external-agent", data);
    }

    /** 把调研报告写进知识库：类型 research_report、来源 crewai（可被 RAG 检索到，也可用于评测）。 */
    private Map<String, Object> writeBack(String topic, String niche, String report) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("type", "research_report");
        metadata.put("agent", "crewai-research");
        metadata.put("source", "crewai");
        metadata.put("topic", topic);
        if (niche != null && !niche.isBlank()) {
            metadata.put("niche", niche);
        }
        try {
            DocumentIngestionPipeline.IngestionResult result =
                    ingestionPipeline.ingestText(report, "crewai-research-" + safeName(topic) + ".md", metadata);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("documentId", result.documentId());
            info.put("chunkCount", result.chunkCount());
            info.put("success", result.success());
            info.put("message", result.message());
            return info;
        } catch (Exception e) {
            log.warn("[ExternalAgent] 回写知识库失败: {}", e.getMessage());
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("success", false);
            info.put("message", "回写知识库失败：" + e.getMessage());
            return info;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String safeName(String topic) {
        String name = topic.replaceAll("[^\\p{IsHan}a-zA-Z0-9_-]", "");
        return name.length() > 40 ? name.substring(0, 40) : (name.isEmpty() ? "topic" : name);
    }

    @Data
    public static class ResearchRunRequest {
        /** 研究主题/方向 */
        private String topic;
        /** 账号定位/领域（为空用配置默认值） */
        private String niche;
        /** 目标平台代号：XHS/WECHAT/DOUYIN/BILIBILI */
        private List<String> platforms;
        /** 选题候选数量 */
        private Integer maxTopics;
        /** 执行器：crewai / lite（为空按 crew 服务配置） */
        private String executor;
    }
}
