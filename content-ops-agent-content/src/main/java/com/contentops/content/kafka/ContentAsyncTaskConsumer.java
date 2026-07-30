package com.contentops.content.kafka;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.ContentDraftResult;
import com.contentops.common.dto.OutlineResult;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AsyncTaskEvent;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.metrics.TokenEstimator;
import com.contentops.common.metrics.TokenMetricsService;
import com.contentops.content.agent.ContentCreationAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Content Agent 异步任务消费者（P1: 弹性与可观测性 — Kafka 异步模式）。
 *
 * <p>监听 {@code content-ops.async.tasks} topic 中的异步任务请求，
 * 当 agentStage = "content-creation" 时消费消息，执行内容创作 LLM 调用，
 * 然后将结果发送到 {@code content-ops.async.results} topic。
 *
 * <p>支持的子阶段：
 * <ul>
 *   <li>{@code outline} — 大纲生成</li>
 *   <li>{@code draft}  — 初稿生成（长耗时，异步执行的核心场景）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentAsyncTaskConsumer {

    private final ContentCreationAgent contentCreationAgent;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TokenMetricsService tokenMetricsService;

    /**
     * 消费异步任务请求。
     *
     * <p>消费者组配置为 {@code content-async-consumer-group}，
     * 通过 filter 确保只消费 agentStage = "content-creation" 的消息。
     */
    @KafkaListener(
            topics = AgentConstants.ASYNC_TASK_REQUEST_TOPIC,
            groupId = "content-async-consumer-group"
    )
    public void handleAsyncTask(AsyncTaskEvent.AsyncTaskRequest request) {
        // 过滤：只处理 content-creation 阶段的任务
        if (!AgentStage.CONTENT_CREATION.getCode().equals(request.getAgentStage())) {
            return;
        }
        log.info("[ContentAsync] 收到异步任务: taskId={}, workflowId={}, subStage={}",
                request.getTaskId(), request.getWorkflowId(), request.getSubStage());

        long startTime = System.currentTimeMillis();

        try {
            AsyncTaskEvent.AsyncTaskResult result = processTask(request, startTime);
            sendResult(result);
        } catch (Exception e) {
            log.error("[ContentAsync] 异步任务执行失败: taskId={}", request.getTaskId(), e);
            sendResult(buildFailureResult(request, e.getMessage(), startTime));
        }
    }

    /**
     * 根据子阶段路由到对应的 Agent 方法。
     */
    private AsyncTaskEvent.AsyncTaskResult processTask(
            AsyncTaskEvent.AsyncTaskRequest request, long startTime) {

        String subStage = request.getSubStage();
        AccountProfile profile = request.getAccountProfile();

        if (profile == null) {
            return buildFailureResult(request, "Missing accountProfile", startTime);
        }

        String memoryId = String.format(AgentConstants.MEMORY_ID_FORMAT,
                AgentStage.CONTENT_CREATION.getCode(), request.getWorkflowId());

        Map<String, Object> inputs = request.getInputs() != null
                ? request.getInputs() : new HashMap<>();
        Map<String, Object> artifacts = request.getAccumulatedArtifacts() != null
                ? request.getAccumulatedArtifacts() : new HashMap<>();

        Map<String, Object> data;
        int inputTokens = 0;
        int outputTokens = 0;

        switch (subStage != null ? subStage : "") {
            case "outline" -> {
                String topic = getStr(inputs, artifacts, "topic", "selectedTopic");
                String angle = getStr(inputs, artifacts, "angle");
                String additionalContext = getStr(inputs, artifacts, "additionalContext");
                String personalExperience = resolvePersonalExperience(inputs, profile);

                OutlineResult outlineResult = contentCreationAgent.generateOutline(
                        memoryId, topic, angle, profile.getNiche(),
                        profile.getTargetAudience(), profile.getTone(),
                        additionalContext, personalExperience);

                data = new HashMap<>();
                data.put("outline", outlineResult);
                data.put("topic", topic);
                data.put("stage", "outline");
                data.put("needsConfirmation", true);

                inputTokens = TokenEstimator.estimate(topic, angle, additionalContext, personalExperience,
                        profile.getNiche(), profile.getTargetAudience(), profile.getTone());
                outputTokens = TokenEstimator.estimate(outlineResult);
            }
            case "draft" -> {
                String topic = getStr(inputs, artifacts, "topic", "selectedTopic");
                String confirmedOutline = getStr(inputs, artifacts, "confirmedOutline");
                String personalExperience = resolvePersonalExperience(inputs, profile);

                ContentDraftResult draftResult = contentCreationAgent.generateDraft(
                        memoryId, confirmedOutline, topic, profile.getNiche(),
                        profile.getTone(), profile.getNiche(),
                        request.getWorkflowId(), personalExperience);

                data = new HashMap<>();
                data.put("outline", draftResult.getOutline());
                data.put("draftContent", draftResult.getDraftContent());
                data.put("wordCount", draftResult.getWordCount());
                data.put("titleVariations", draftResult.getTitleVariations());
                data.put("tags", draftResult.getTags());
                data.put("summary", draftResult.getSummary());
                data.put("stage", "draft");

                inputTokens = TokenEstimator.estimate(confirmedOutline, topic, personalExperience,
                        profile.getNiche(), profile.getTone());
                outputTokens = TokenEstimator.estimate(draftResult.getDraftContent(),
                        draftResult.getOutline(), draftResult.getSummary());
            }
            default -> {
                return buildFailureResult(request,
                        "Unknown or missing subStage: " + subStage, startTime);
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;

        // P1: 记录 token 消耗与调用指标
        tokenMetricsService.recordTokenUsage(request.getWorkflowId(),
                AgentStage.CONTENT_CREATION.getCode(), inputTokens, outputTokens);
        tokenMetricsService.recordAgentCall(AgentStage.CONTENT_CREATION.getCode(), true);
        tokenMetricsService.recordAgentDuration(AgentStage.CONTENT_CREATION.getCode(),
                java.time.Duration.ofMillis(durationMs));

        return AsyncTaskEvent.AsyncTaskResult.builder()
                .taskId(request.getTaskId())
                .workflowId(request.getWorkflowId())
                .agentStage(request.getAgentStage())
                .subStage(subStage)
                .success(true)
                .data(data)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .durationMs(durationMs)
                .completedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 发送异步任务结果到 Kafka。
     */
    private void sendResult(AsyncTaskEvent.AsyncTaskResult result) {
        log.info("[ContentAsync] 发送异步结果: taskId={}, success={}, duration={}ms",
                result.getTaskId(), result.isSuccess(), result.getDurationMs());
        try {
            kafkaTemplate.send(AgentConstants.ASYNC_TASK_RESULT_TOPIC,
                    result.getWorkflowId(), result);
        } catch (Exception e) {
            log.error("[ContentAsync] 发送结果失败: taskId={}", result.getTaskId(), e);
        }
    }

    /**
     * 构建失败结果。
     */
    private AsyncTaskEvent.AsyncTaskResult buildFailureResult(
            AsyncTaskEvent.AsyncTaskRequest request, String error, long startTime) {
        return AsyncTaskEvent.AsyncTaskResult.builder()
                .taskId(request.getTaskId())
                .workflowId(request.getWorkflowId())
                .agentStage(request.getAgentStage())
                .subStage(request.getSubStage())
                .success(false)
                .error(error)
                .durationMs(System.currentTimeMillis() - startTime)
                .completedAt(LocalDateTime.now())
                .build();
    }

    // ══════════════════ 工具方法 ══════════════════

    private String getStr(Map<String, Object> inputs, Map<String, Object> artifacts,
                          String... keys) {
        for (String key : keys) {
            Object value = inputs.get(key);
            if (value == null) {
                value = artifacts.get(key);
            }
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String resolvePersonalExperience(Map<String, Object> inputs, AccountProfile profile) {
        String personalExperience = getStr(inputs, Map.of(), "personalExperience");
        if ((personalExperience == null || personalExperience.isBlank())
                && profile.getPersonalExperience() != null) {
            personalExperience = profile.getPersonalExperience();
        }
        return personalExperience;
    }
}
