package com.contentops.common.quality;

import com.contentops.common.enums.AgentStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 竞争模式服务（P2 v2.1.0: 多模型路由与质量评估）。
 *
 * <p>对关键阶段（如内容创作、选题策划）支持竞争模式：并行调用两次 LLM，
 * 然后通过 {@link QualityAssessmentService} 对两个结果进行质量评分，
 * 选择总分更高的作为最终输出。
 *
 * <h3>设计说明</h3>
 * <p>由于不能直接修改各 Agent 模块的 {@code AiServices.builder()} 配置，
 * 本服务提供竞争模式的<b>策略决策</b>和<b>结果选择</b>能力。
 * 各 Agent 模块的编排逻辑可调用 {@link #shouldUseCompetitiveMode} 判断是否
 * 需要并行调用，再调用 {@link #selectBestResult} 选择最优结果。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * if (competitiveModeService.shouldUseCompetitiveMode(AgentStage.CONTENT_CREATION)) {
 *     // 并行调用两次 LLM（由 Agent 模块自行实现并行）
 *     String result1 = agent.generate(prompt);
 *     String result2 = agent.generate(prompt);
 *     // 选择质量最高的结果
 *     String best = competitiveModeService.selectBestResult(
 *             List.of(result1, result2), AgentStage.CONTENT_CREATION);
 * }
 * }</pre>
 *
 * @see CompetitiveModeProperties
 * @see QualityAssessmentService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompetitiveModeService {

    private final CompetitiveModeProperties properties;
    private final QualityAssessmentService qualityAssessmentService;

    /**
     * 判断指定阶段是否应启用竞争模式。
     *
     * <p>当竞争模式全局启用，且该阶段在配置的 stages 列表中时返回 true。
     *
     * @param stage Agent 阶段
     * @return true 表示该阶段应并行调用两次并择优
     */
    public boolean shouldUseCompetitiveMode(AgentStage stage) {
        if (!properties.isEnabled()) {
            return false;
        }
        boolean shouldUse = properties.getStages() != null
                && properties.getStages().contains(stage.getCode());
        if (shouldUse) {
            log.debug("[CompetitiveMode] stage={} 启用竞争模式", stage.getCode());
        }
        return shouldUse;
    }

    /**
     * 从多个候选结果中选择质量最优的结果。
     *
     * <p>使用 {@link QualityAssessmentService} 对每个候选结果进行评分，
     * 返回总分最高的结果。当多个结果总分相同时，返回列表中靠前的那个。
     *
     * @param results 候选结果列表（至少 2 个）
     * @param stage   Agent 阶段（用于评分策略）
     * @return 质量总分最高的结果
     * @throws IllegalArgumentException 当结果列表为空时抛出
     */
    public String selectBestResult(List<String> results, AgentStage stage) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("候选结果列表不能为空");
        }

        // 只有一个结果时直接返回
        if (results.size() == 1) {
            return results.get(0);
        }

        // 对每个结果评分并选择总分最高的
        String bestResult = results.stream()
                .max(Comparator.comparingInt(
                        result -> qualityAssessmentService.assessQuality(stage, result).getTotalScore()))
                .orElse(results.get(0));

        // 记录竞争模式选择详情
        logBestResultDetails(results, stage, bestResult);

        return bestResult;
    }

    /**
     * 从多个候选结果中选择质量最优的结果，并返回对应的评分。
     *
     * <p>当调用方需要知道最优结果的质量评分时使用此方法。
     *
     * @param results 候选结果列表
     * @param stage   Agent 阶段
     * @return 包含最优结果及其评分的包装对象
     */
    public CompetitiveResult selectBestWithScore(List<String> results, AgentStage stage) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("候选结果列表不能为空");
        }

        CompetitiveResult best = null;
        int bestScore = -1;

        for (String result : results) {
            QualityScore score = qualityAssessmentService.assessQuality(stage, result);
            if (score.getTotalScore() > bestScore) {
                bestScore = score.getTotalScore();
                best = new CompetitiveResult(result, score);
            }
        }

        log.info("[CompetitiveMode] stage={}, 候选数={}, 最优总分={}",
                stage.getCode(), results.size(), bestScore);

        return best;
    }

    /**
     * 记录竞争模式选择的详细评分信息。
     */
    private void logBestResultDetails(List<String> results, AgentStage stage, String bestResult) {
        int bestScore = qualityAssessmentService.assessQuality(stage, bestResult).getTotalScore();
        log.info("[CompetitiveMode] stage={}, 候选数={}, 最优总分={}",
                stage.getCode(), results.size(), bestScore);
    }

    /**
     * 竞争模式结果包装类，包含最优结果文本及其质量评分。
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class CompetitiveResult {
        /** 最优结果文本 */
        private String content;
        /** 最优结果的质量评分 */
        private QualityScore qualityScore;
    }
}
