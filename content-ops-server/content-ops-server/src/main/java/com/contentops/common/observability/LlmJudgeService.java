package com.contentops.common.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * LLM-as-Judge：用判官模型对 Agent 阶段产物评分（相关性/完整性/格式/风险），
 * 结果落库供回归门禁。默认只记录不阻断，gateEnabled=true 时可阻断流水线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmJudgeService {

    private static final String JUDGE_PROMPT = """
            你是资深内容运营质量评审官。请对下面的 Agent 阶段产物打分（0-100 整数），
            从四个维度评估：内容质量、完整性与结构、格式规范（是否为有效结构化输出）、
            事实与风险（是否疑似编造/夸大）。
            只输出严格 JSON：{"score":80,"feedback":"一句话点评","passed":true}
            阶段：%s
            任务输入：%s
            产物输出：%s
            """;

    private final @Qualifier("formattingChatModel") ChatModel chatModel;
    private final EvalProperties properties;
    private final LlmEvalRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 同步判分（手动评测/CI 回归用）。
     */
    public Map<String, Object> judge(String stage, String input, String output, String workflowId) {
        int threshold = properties.getThreshold();
        String feedback = "";
        Integer score = null;
        boolean passed = false;
        if (properties.isEnabled() && output != null && !output.isBlank()) {
            try {
                String prompt = JUDGE_PROMPT.formatted(
                        safe(stage), truncate(safe(input), 800), truncate(safe(output), 4000));
                String raw = chatModel.chat(prompt);
                JsonNode node = extractJson(raw);
                if (node != null) {
                    score = node.path("score").isIntegralNumber() ? node.path("score").asInt() : null;
                    feedback = node.path("feedback").isTextual() ? node.path("feedback").asText() : "";
                    passed = node.path("passed").isBoolean()
                            ? node.path("passed").asBoolean()
                            : score != null && score >= threshold;
                }
            } catch (Exception e) {
                log.warn("[Eval] 判分失败（降级为不通过并记录）: stage={}, err={}", stage, e.getMessage());
                feedback = "判官调用失败: " + e.getMessage();
            }
        }
        String caseId = UUID.randomUUID().toString();
        repository.insertCase(caseId, safe(stage), stage + " 自动用例",
                truncate(safe(input), 2000), null, Timestamp.valueOf(LocalDateTime.now()));
        String runId = UUID.randomUUID().toString();
        repository.insertRun(runId, caseId, workflowId, safe(stage), "deepseek-chat",
                score, truncate(feedback, 1000), score != null && score >= threshold,
                threshold, Timestamp.valueOf(LocalDateTime.now()));
        return Map.of(
                "runId", runId,
                "stage", safe(stage),
                "score", score,
                "feedback", feedback,
                "passed", score != null && score >= threshold,
                "threshold", threshold);
    }

    /**
     * 流水线阶段完成后异步判分（不阻塞流程）。
     */
    public void judgeAsync(String stage, String input, String output, String workflowId) {
        if (!properties.isEnabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                judge(stage, input, output, workflowId);
            } catch (Exception e) {
                log.warn("[Eval] 异步判分失败: stage={}", stage);
            }
        });
    }

    private JsonNode extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
