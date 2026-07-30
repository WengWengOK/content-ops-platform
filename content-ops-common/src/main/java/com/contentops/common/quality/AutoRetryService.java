package com.contentops.common.quality;

import com.contentops.common.enums.AgentStage;
import com.contentops.common.metrics.TokenEstimator;
import com.contentops.common.metrics.TokenMetricsService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 低分自动重试服务（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>当 Agent 输出的质量评分低于 {@link QualityThresholdProperties#getMinScore()}
 * 阈值时，自动重试（最多 {@link QualityThresholdProperties#getMaxRetries()} 次）。
 * 每次重试会在原始 prompt 中追加上一轮的改进建议，引导 LLM 针对性优化。
 *
 * <h3>重试流程</h3>
 * <ol>
 *   <li>首次调用 LLM，获取结果</li>
 *   <li>使用 {@link QualityAssessmentService} 评估质量</li>
 *   <li>若总分 ≥ 阈值 → 返回结果（重试成功或无需重试）</li>
 *   <li>若总分 < 阈值且仍有重试次数 → 追加改进建议到 prompt，重新调用 LLM</li>
 *   <li>达到最大重试次数仍不达标 → 返回最后一次结果（最优）</li>
 * </ol>
 *
 * <h3>指标记录</h3>
 * <p>每次重试调用的 token 消耗和调用状态会记录到 {@link TokenMetricsService}，
 * 使用 "{stage}:retry" 作为 agentStage 标签以区分重试调用与正常调用。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * AutoRetryService.AutoRetryResult result = autoRetryService.executeWithRetry(
 *     AgentStage.CONTENT_CREATION,
 *     originalPrompt,
 *     prompt -> contentAgent.generate(prompt),  // LLM 调用函数
 *     workflowId
 * );
 * String bestContent = result.getContent();
 * }</pre>
 *
 * @see QualityAssessmentService
 * @see QualityThresholdProperties
 * @see TokenMetricsService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoRetryService {

    private final QualityAssessmentService qualityAssessmentService;
    private final QualityThresholdProperties qualityProperties;
    private final TokenMetricsService tokenMetricsService;

    /** 重试 prompt 中改进建议的前缀 */
    private static final String SUGGESTION_HEADER = "\n\n---\n【质量改进建议】上一轮生成结果评分较低，请针对以下问题优化：\n";

    /**
     * 带自动重试的 LLM 调用执行器。
     *
     * <p>调用方通过 {@code llmInvoker} 函数式接口提供实际的 LLM 调用逻辑，
     * 本服务负责质量评估、阈值判断、重试编排和指标记录。
     *
     * @param stage      Agent 阶段
     * @param prompt     原始 prompt（重试时会追加改进建议）
     * @param llmInvoker LLM 调用函数，接收 prompt 返回生成结果
     * @param workflowId 工作流 ID（用于指标记录）
     * @return 重试结果，包含最终内容、质量评分和重试次数
     */
    public AutoRetryResult executeWithRetry(AgentStage stage,
                                            String prompt,
                                            LlmInvoker llmInvoker,
                                            String workflowId) {
        // 质量评估未启用时，直接调用一次返回
        if (!qualityProperties.isEnabled()) {
            log.debug("[AutoRetry] 质量评估未启用，stage={} 直接调用", stage.getCode());
            String content = llmInvoker.invoke(prompt);
            recordMetrics(stage, workflowId, prompt, content, true, 0);
            return AutoRetryResult.builder()
                    .content(content)
                    .finalScore(qualityAssessmentService.assessQuality(stage, content))
                    .retryCount(0)
                    .success(true)
                    .build();
        }

        // 自动重试未启用时，调用一次并评估但不重试
        if (!qualityProperties.isAutoRetry()) {
            String content = llmInvoker.invoke(prompt);
            QualityScore score = qualityAssessmentService.assessQuality(stage, content);
            recordMetrics(stage, workflowId, prompt, content, true, 0);
            return AutoRetryResult.builder()
                    .content(content)
                    .finalScore(score)
                    .retryCount(0)
                    .success(score.isAboveThreshold(qualityProperties.getMinScore()))
                    .build();
        }

        return doExecuteWithRetry(stage, prompt, llmInvoker, workflowId);
    }

    /**
     * 核心重试逻辑。
     */
    private AutoRetryResult doExecuteWithRetry(AgentStage stage,
                                                String prompt,
                                                LlmInvoker llmInvoker,
                                                String workflowId) {
        int maxRetries = qualityProperties.getMaxRetries();
        int minScore = qualityProperties.getMinScore();

        String currentPrompt = prompt;
        String bestContent = null;
        QualityScore bestScore = null;
        int bestRetryIndex = -1;

        // 总共最多调用 maxRetries + 1 次（首次 + 重试）
        int totalAttempts = maxRetries + 1;

        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            boolean isRetry = attempt > 0;
            log.info("[AutoRetry] stage={}, attempt={}/{}, isRetry={}",
                    stage.getCode(), attempt + 1, totalAttempts, isRetry);

            // 调用 LLM
            Instant start = Instant.now();
            String content;
            try {
                content = llmInvoker.invoke(currentPrompt);
            } catch (Exception e) {
                log.error("[AutoRetry] stage={}, attempt={} LLM 调用失败", stage.getCode(), attempt + 1, e);
                recordMetrics(stage, workflowId, currentPrompt, "", false, attempt);
                // 调用失败，如果有之前的结果则返回，否则继续重试
                if (bestContent != null) {
                    break;
                }
                continue;
            }
            Duration duration = Duration.between(start, Instant.now());

            // 评估质量
            QualityScore score = qualityAssessmentService.assessQuality(stage, content);

            // 记录指标
            recordMetricsWithRetry(stage, workflowId, currentPrompt, content, true, attempt, duration);

            // 更新最优结果（取总分最高的）
            if (bestScore == null || score.getTotalScore() > bestScore.getTotalScore()) {
                bestContent = content;
                bestScore = score;
                bestRetryIndex = attempt;
            }

            log.info("[AutoRetry] stage={}, attempt={}, totalScore={}, threshold={}, passed={}",
                    stage.getCode(), attempt + 1, score.getTotalScore(), minScore,
                    score.isAboveThreshold(minScore));

            // 质量达标，提前退出
            if (score.isAboveThreshold(minScore)) {
                log.info("[AutoRetry] stage={} 质量达标（总分={}），重试次数={}",
                        stage.getCode(), score.getTotalScore(), attempt);
                break;
            }

            // 未达标且仍有重试机会，追加改进建议到 prompt
            if (attempt < totalAttempts - 1) {
                currentPrompt = appendSuggestions(prompt, score.getSuggestions());
                log.debug("[AutoRetry] stage={} 追加改进建议准备重试, suggestions={}",
                        stage.getCode(), score.getSuggestions().size());
            }
        }

        boolean success = bestScore != null && bestScore.isAboveThreshold(minScore);
        int actualRetryCount = bestRetryIndex > 0 ? bestRetryIndex : 0;

        log.info("[AutoRetry] stage={} 完成, 最优总分={}, 重试次数={}, 达标={}",
                stage.getCode(),
                bestScore != null ? bestScore.getTotalScore() : 0,
                actualRetryCount,
                success);

        return AutoRetryResult.builder()
                .content(bestContent)
                .finalScore(bestScore)
                .retryCount(actualRetryCount)
                .success(success)
                .build();
    }

    /**
     * 将改进建议追加到原始 prompt 中。
     *
     * @param originalPrompt 原始 prompt
     * @param suggestions    改进建议列表
     * @return 追加建议后的新 prompt
     */
    private String appendSuggestions(String originalPrompt, List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return originalPrompt;
        }
        StringBuilder sb = new StringBuilder(originalPrompt);
        sb.append(SUGGESTION_HEADER);
        for (int i = 0; i < suggestions.size(); i++) {
            sb.append(i + 1).append(". ").append(suggestions.get(i)).append("\n");
        }
        sb.append("请根据以上建议重新生成，确保在逻辑性、可读性和原创性三个维度均达到较高水平。");
        return sb.toString();
    }

    /**
     * 记录正常调用的指标。
     */
    private void recordMetrics(AgentStage stage, String workflowId,
                                String prompt, String content,
                                boolean success, int attempt) {
        try {
            int inputTokens = TokenEstimator.estimate(prompt);
            int outputTokens = TokenEstimator.estimate(content);
            String stageTag = attempt > 0 ? stage.getCode() + ":retry" : stage.getCode();
            tokenMetricsService.recordTokenUsage(workflowId, stageTag, inputTokens, outputTokens);
            tokenMetricsService.recordAgentCall(stageTag, success);
        } catch (Exception e) {
            log.warn("[AutoRetry] 记录指标失败: {}", e.getMessage());
        }
    }

    /**
     * 记录带延迟信息的重试调用指标。
     */
    private void recordMetricsWithRetry(AgentStage stage, String workflowId,
                                         String prompt, String content,
                                         boolean success, int attempt, Duration duration) {
        try {
            int inputTokens = TokenEstimator.estimate(prompt);
            int outputTokens = TokenEstimator.estimate(content);
            String stageTag = attempt > 0 ? stage.getCode() + ":retry" : stage.getCode();
            tokenMetricsService.recordTokenUsage(workflowId, stageTag, inputTokens, outputTokens);
            tokenMetricsService.recordAgentCall(stageTag, success);
            tokenMetricsService.recordAgentDuration(stageTag, duration);
        } catch (Exception e) {
            log.warn("[AutoRetry] 记录延迟指标失败: {}", e.getMessage());
        }
    }

    // ──────────────── 函数式接口与结果 DTO ────────────────

    /**
     * LLM 调用函数式接口。
     *
     * <p>调用方通过实现此接口提供实际的 LLM 调用逻辑（如调用 Agent 的 generate 方法）。
     */
    @FunctionalInterface
    public interface LlmInvoker {
        /**
         * 使用指定 prompt 调用 LLM。
         *
         * @param prompt 输入 prompt（重试时可能包含追加的改进建议）
         * @return LLM 生成的结果文本
         */
        String invoke(String prompt);
    }

    /**
     * 自动重试结果 DTO。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AutoRetryResult {
        /** 最终生成的内容（质量最优的结果） */
        private String content;

        /** 最终内容的质量评分 */
        private QualityScore finalScore;

        /** 实际重试次数（0 表示首次即达标，未重试） */
        private int retryCount;

        /** 是否达到质量阈值 */
        private boolean success;
    }
}
