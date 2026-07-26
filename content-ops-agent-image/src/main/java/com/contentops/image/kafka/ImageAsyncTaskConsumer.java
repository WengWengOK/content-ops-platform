package com.contentops.image.kafka;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.ImageDesignResult;
import com.contentops.common.dto.StyleDirectionResult;
import com.contentops.common.dto.TaskContext.AccountProfile;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.event.AsyncTaskEvent;
import com.contentops.common.metrics.TokenEstimator;
import com.contentops.common.metrics.TokenMetricsService;
import com.contentops.image.agent.ImageDesignAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Image Agent 异步任务消费者（P1: 弹性与可观测性 — Kafka 异步模式）。
 *
 * <p>监听 {@code content-ops.async.tasks} topic 中的异步任务请求，
 * 当 agentStage = "image-design" 时消费消息，执行配图设计 LLM 调用，
 * 然后将结果发送到 {@code content-ops.async.results} topic。
 *
 * <p>支持的子阶段：
 * <ul>
 *   <li>{@code styles}   — 风格方向生成</li>
 *   <li>{@code generate} — 批量生图（长耗时，异步执行的核心场景）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageAsyncTaskConsumer {

    private static final List<String> DEFAULT_PLATFORMS = List.of("公众号", "小红书", "头条");

    private final ImageDesignAgent imageDesignAgent;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TokenMetricsService tokenMetricsService;

    /**
     * 消费异步任务请求。
     */
    @KafkaListener(
            topics = AgentConstants.ASYNC_TASK_REQUEST_TOPIC,
            groupId = "image-async-consumer-group"
    )
    public void handleAsyncTask(AsyncTaskEvent.AsyncTaskRequest request) {
        // 过滤：只处理 image-design 阶段的任务
        if (!AgentStage.IMAGE_DESIGN.getCode().equals(request.getAgentStage())) {
            return;
        }
        log.info("[ImageAsync] 收到异步任务: taskId={}, workflowId={}, subStage={}",
                request.getTaskId(), request.getWorkflowId(), request.getSubStage());

        long startTime = System.currentTimeMillis();

        try {
            AsyncTaskEvent.AsyncTaskResult result = processTask(request, startTime);
            sendResult(result);
        } catch (Exception e) {
            log.error("[ImageAsync] 异步任务执行失败: taskId={}", request.getTaskId(), e);
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
                AgentStage.IMAGE_DESIGN.getCode(), request.getWorkflowId());

        Map<String, Object> inputs = request.getInputs() != null
                ? request.getInputs() : new HashMap<>();
        Map<String, Object> artifacts = request.getAccumulatedArtifacts() != null
                ? request.getAccumulatedArtifacts() : new HashMap<>();

        String articleTitle = getStr(inputs, artifacts, "articleTitle", "topic");
        String articleContent = getStr(inputs, artifacts, "articleContent", "draftContent");
        String articleTone = getStr(inputs, artifacts, "tone");
        if (articleTone == null || articleTone.isBlank()) {
            articleTone = profile.getTone();
        }

        List<String> platforms = (profile.getPlatforms() == null || profile.getPlatforms().isEmpty())
                ? DEFAULT_PLATFORMS : profile.getPlatforms();

        Map<String, Object> data;
        int inputTokens = 0;
        int outputTokens = 0;

        switch (subStage != null ? subStage : "") {
            case "styles" -> {
                StyleDirectionResult stylesResult = imageDesignAgent.generateStyleDirections(
                        memoryId, articleTitle, articleContent, articleTone, platforms);

                data = new HashMap<>();
                data.put("styleDirections", stylesResult);
                data.put("articleTitle", articleTitle);
                data.put("stage", "styles");
                data.put("needsConfirmation", true);

                inputTokens = TokenEstimator.estimate(articleTitle, articleContent, articleTone);
                outputTokens = TokenEstimator.estimate(stylesResult);
            }
            case "generate" -> {
                String confirmedStyle = getStr(inputs, artifacts, "confirmedStyle");
                if (confirmedStyle == null || confirmedStyle.isBlank()) {
                    return buildFailureResult(request,
                            "Missing 'confirmedStyle' for generate sub-stage", startTime);
                }

                ImageDesignResult generateResult = imageDesignAgent.generateImages(
                        memoryId, confirmedStyle, articleTitle, articleContent,
                        articleTone, platforms);

                data = new HashMap<>();
                data.put("images", generateResult.getImages());
                data.put("covers", generateResult.getCovers());
                data.put("stage", "generate");

                inputTokens = TokenEstimator.estimate(confirmedStyle, articleTitle, articleContent, articleTone);
                outputTokens = TokenEstimator.estimate(generateResult);
            }
            default -> {
                return buildFailureResult(request,
                        "Unknown or missing subStage: " + subStage, startTime);
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;

        // P1: 记录 token 消耗与调用指标
        tokenMetricsService.recordTokenUsage(request.getWorkflowId(),
                AgentStage.IMAGE_DESIGN.getCode(), inputTokens, outputTokens);
        tokenMetricsService.recordAgentCall(AgentStage.IMAGE_DESIGN.getCode(), true);
        tokenMetricsService.recordAgentDuration(AgentStage.IMAGE_DESIGN.getCode(),
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
        log.info("[ImageAsync] 发送异步结果: taskId={}, success={}, duration={}ms",
                result.getTaskId(), result.isSuccess(), result.getDurationMs());
        try {
            kafkaTemplate.send(AgentConstants.ASYNC_TASK_RESULT_TOPIC,
                    result.getWorkflowId(), result);
        } catch (Exception e) {
            log.error("[ImageAsync] 发送结果失败: taskId={}", result.getTaskId(), e);
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
}
