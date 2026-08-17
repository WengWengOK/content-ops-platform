package com.contentops.common.agent;

import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent 执行结果（多 Agent 协作框架）。
 *
 * <p>采用 Java 21 {@code record} 封装单个 Agent 任务执行后的完整结果，包括成功标志、
 * 输出内容、错误信息、执行耗时、token 消耗与质量评分。结果可通过 {@link #merge(AgentResult)}
 * 方法合并多个 Agent 的产出，用于并行与层级协作模式下的结果聚合。
 *
 * <h3>结果合并</h3>
 * <p>{@link #merge(AgentResult)} 遵循以下规则：
 * <ul>
 *   <li>成功标志：两者都成功才为成功</li>
 *   <li>输出内容：拼接双方输出（以分隔符隔开）</li>
 *   <li>错误信息：合并双方错误列表</li>
 *   <li>执行耗时：取较大值</li>
 *   <li>token 消耗：累加（使用 {@link TokenUsage#sum}）</li>
 *   <li>质量评分：取较高分</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AgentResult r1 = AgentResult.success("t-1", "调研结果...", 1200L, usage1, 85);
 * AgentResult r2 = AgentResult.success("t-2", "写作结果...", 3400L, usage2, 78);
 * AgentResult merged = r1.merge(r2);
 * }</pre>
 *
 * @param taskId        对应的任务 ID
 * @param success       是否执行成功
 * @param output        输出内容（文本或 JSON 字符串）
 * @param errors        错误信息列表（成功时为空）
 * @param executionTime 执行耗时（毫秒）
 * @param tokenUsage    token 消耗（输入/输出/总计）
 * @param qualityScore  质量评分（0-100，-1 表示未评分）
 *
 * @see AgentTask
 * @see TokenUsage
 */
public record AgentResult(
        String taskId,
        boolean success,
        String output,
        List<String> errors,
        long executionTime,
        TokenUsage tokenUsage,
        int qualityScore
) {

    /** 合并输出时使用的分隔符。 */
    private static final String MERGE_SEPARATOR = "\n\n---\n\n";

    /** 未评分时的质量分占位值。 */
    public static final int SCORE_NOT_RATED = -1;

    /**
     * 紧凑构造器：规范化错误列表与 tokenUsage。
     */
    public AgentResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        if (tokenUsage == null) {
            tokenUsage = new TokenUsage(0, 0, 0);
        }
    }

    // ──────────────────────── 工厂方法 ────────────────────────

    /**
     * 构建一个成功结果。
     *
     * @param taskId        任务 ID
     * @param output        输出内容
     * @param executionTime 执行耗时（毫秒）
     * @param tokenUsage    token 消耗
     * @param qualityScore  质量评分
     * @return 成功结果实例
     */
    public static AgentResult success(String taskId, String output, long executionTime,
                                       TokenUsage tokenUsage, int qualityScore) {
        return new AgentResult(taskId, true, output, List.of(), executionTime, tokenUsage, qualityScore);
    }

    /**
     * 构建一个成功结果（不附带质量评分）。
     *
     * @param taskId        任务 ID
     * @param output        输出内容
     * @param executionTime 执行耗时（毫秒）
     * @param tokenUsage    token 消耗
     * @return 成功结果实例
     */
    public static AgentResult success(String taskId, String output, long executionTime,
                                       TokenUsage tokenUsage) {
        return new AgentResult(taskId, true, output, List.of(), executionTime, tokenUsage, SCORE_NOT_RATED);
    }

    /**
     * 构建一个失败结果。
     *
     * @param taskId 任务 ID
     * @param error  错误信息
     * @return 失败结果实例
     */
    public static AgentResult failure(String taskId, String error) {
        return new AgentResult(taskId, false, null, List.of(error), 0L, new TokenUsage(0, 0, 0), SCORE_NOT_RATED);
    }

    /**
     * 构建一个失败结果（带执行耗时）。
     *
     * @param taskId        任务 ID
     * @param error         错误信息
     * @param executionTime 执行耗时（毫秒）
     * @return 失败结果实例
     */
    public static AgentResult failure(String taskId, String error, long executionTime) {
        return new AgentResult(taskId, false, null, List.of(error), executionTime, new TokenUsage(0, 0, 0), SCORE_NOT_RATED);
    }

    /**
     * 构建一个超时降级结果。
     *
     * @param taskId        任务 ID
     * @param partialOutput 超时前已获得的部分输出（可为 null）
     * @param executionTime 执行耗时（毫秒）
     * @return 降级结果实例（success=false）
     */
    public static AgentResult timeout(String taskId, String partialOutput, long executionTime) {
        List<String> errs = List.of("任务执行超时，已降级返回部分结果");
        return new AgentResult(taskId, false, partialOutput, errs, executionTime,
                new TokenUsage(0, 0, 0), SCORE_NOT_RATED);
    }

    // ──────────────────────── 合并方法 ────────────────────────

    /**
     * 合并另一个 Agent 的执行结果。
     *
     * <p>合并规则参见类级 JavaDoc。合并后的 taskId 取当前结果的 taskId，
     * 适用于将多个子任务结果聚合为一个整体结果。
     *
     * @param other 待合并的另一个结果（为 null 时返回当前结果）
     * @return 合并后的新结果
     */
    public AgentResult merge(AgentResult other) {
        if (other == null) {
            return this;
        }

        // 输出拼接
        String mergedOutput;
        if (output == null && other.output == null) {
            mergedOutput = null;
        } else if (output == null) {
            mergedOutput = other.output;
        } else if (other.output == null) {
            mergedOutput = output;
        } else {
            mergedOutput = output + MERGE_SEPARATOR + other.output;
        }

        // 错误合并
        List<String> mergedErrors = new ArrayList<>(errors);
        mergedErrors.addAll(other.errors);

        // token 累加
        TokenUsage mergedUsage = TokenUsage.sum(tokenUsage, other.tokenUsage);

        // 耗时取较大值
        long mergedTime = Math.max(executionTime, other.executionTime);

        // 质量分取较高分（忽略未评分项）
        int mergedScore;
        if (qualityScore == SCORE_NOT_RATED) {
            mergedScore = other.qualityScore;
        } else if (other.qualityScore == SCORE_NOT_RATED) {
            mergedScore = qualityScore;
        } else {
            mergedScore = Math.max(qualityScore, other.qualityScore);
        }

        return new AgentResult(
                taskId,
                success && other.success,
                mergedOutput,
                mergedErrors,
                mergedTime,
                mergedUsage,
                mergedScore
        );
    }

    /**
     * 合并多个结果（从左到右依次 merge）。
     *
     * @param taskId   聚合结果的任务 ID
     * @param results  待合并的结果集合
     * @return 聚合后的结果（无结果时返回一个空的成功结果）
     */
    public static AgentResult mergeAll(String taskId, List<AgentResult> results) {
        Objects.requireNonNull(taskId, "taskId 不能为 null");
        if (results == null || results.isEmpty()) {
            return new AgentResult(taskId, true, "", List.of(), 0L, new TokenUsage(0, 0, 0), SCORE_NOT_RATED);
        }
        AgentResult acc = new AgentResult(
                taskId,
                results.getFirst().success(),
                results.getFirst().output(),
                results.getFirst().errors(),
                results.getFirst().executionTime(),
                results.getFirst().tokenUsage(),
                results.getFirst().qualityScore()
        );
        for (int i = 1; i < results.size(); i++) {
            acc = acc.merge(results.get(i));
        }
        return acc;
    }

    /**
     * 判断结果是否达到指定质量阈值。
     *
     * @param threshold 最低质量阈值
     * @return true 表示质量达标（未评分时视为不达标）
     */
    public boolean meetsQuality(int threshold) {
        return qualityScore != SCORE_NOT_RATED && qualityScore >= threshold;
    }

    /**
     * 获取输入 token 数（便捷方法）。
     *
     * @return 输入 token 数
     */
    public int inputTokens() {
        Integer v = tokenUsage.inputTokenCount();
        return v != null ? v : 0;
    }

    /**
     * 获取输出 token 数（便捷方法）。
     *
     * @return 输出 token 数
     */
    public int outputTokens() {
        Integer v = tokenUsage.outputTokenCount();
        return v != null ? v : 0;
    }

    /**
     * 获取总 token 数（便捷方法）。
     *
     * @return 总 token 数
     */
    public int totalTokens() {
        Integer v = tokenUsage.totalTokenCount();
        return v != null ? v : inputTokens() + outputTokens();
    }
}
