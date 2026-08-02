package com.contentops.common.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * Agent 三级降级策略 — 字节面试最高频考点。
 *
 * <p><b>面试回答模板：</b>"我设计了三级降级策略：前 2 次失败自动重试（指数退避），
 * 第 3 次切换到备用模型，第 4 次使用模板兜底文案并标记 cacheable=false 避免缓存污染，
 * 第 5 次返回错误并写入死信队列供人工接管。"
 *
 * <h3>降级层级</h3>
 * <pre>
 *   Level 1 (失败 1-2 次): 重试 + 指数退避（base_delay × 2^attempt）
 *   Level 2 (失败 3 次):   切换备用模型（fallback model）
 *   Level 3 (失败 4 次):   模板兜底 + 标记 cacheable=false
 *   Level 4 (失败 5 次):   错误 + 死信队列 + 人工接管标记
 * </pre>
 *
 * <h3>核心特性</h3>
 * <ul>
 *   <li><b>指数退避</b>：Level 1 重试间隔按 2 的幂次递增，避免雪崩</li>
 *   <li><b>模型切换</b>：Level 2 自动切换到配置的备用模型（如 gpt-4o → gpt-4o-mini）</li>
 *   <li><b>缓存隔离</b>：Level 3 兜底结果标记 cacheable=false，防止缓存污染</li>
 *   <li><b>死信队列</b>：Level 4 将失败任务写入死信队列，包含完整上下文供人工审查</li>
 * </ul>
 *
 * @see AgentLoopDetector
 */
@Slf4j
@Component
public class AgentDegradationStrategy {

    /** 最大重试次数（Level 1-2 的重试上限） */
    private static final int MAX_RETRY = 2;

    /** 指数退避基数（毫秒） */
    private static final long BACKOFF_BASE_MS = 1000;

    /** 最大退避时间（毫秒），避免过长的等待 */
    private static final long BACKOFF_MAX_MS = 30_000;

    /** 死信队列：存储无法处理的任务 */
    private final ConcurrentLinkedDeque<DeadLetterEntry> deadLetterQueue = new ConcurrentLinkedDeque<>();

    /** 每个任务的失败计数器 */
    private final Map<String, FailureContext> failureContexts = new ConcurrentHashMap<>();

    /**
     * 失败上下文 — 记录单个任务的失败历史。
     */
    private static class FailureContext {
        int failureCount;
        String lastError;
        long lastFailureTime;
        String currentModel;
        boolean modelSwitched;
    }

    /**
     * 死信队列条目。
     *
     * @param taskId     任务 ID
     * @param agentStage Agent 阶段
     * @param error      错误信息
     * @param context    上下文快照（Prompt / 参数等）
     * @param timestamp  入队时间戳
     * @param reason     失败原因分类
     */
    public record DeadLetterEntry(
            String taskId,
            String agentStage,
            String error,
            Map<String, Object> context,
            long timestamp,
            FailureReason reason
    ) {
    }

    /**
     * 失败原因分类枚举。
     */
    public enum FailureReason {
        /** 模型调用超时 */
        LLM_TIMEOUT,
        /** 模型返回错误（5xx/429） */
        LLM_ERROR,
        /** 工具执行失败 */
        TOOL_FAILURE,
        /** 循环检测中断 */
        LOOP_DETECTED,
        /** 质量评估不达标 */
        QUALITY_BELOW_THRESHOLD,
        /** 未知错误 */
        UNKNOWN
    }

    /**
     * 降级决策结果。
     *
     * @param level        降级层级（1=重试, 2=切模型, 3=模板兜底, 4=死信队列）
     * @param action       建议的动作
     * @param retryDelayMs 重试延迟（仅 Level 1 有效）
     * @param shouldSwitchModel 是否应切换模型
     * @param fallbackTemplate 模板兜底文案（仅 Level 3 有效）
     * @param cacheable    结果是否可缓存
     */
    public record DegradationDecision(
            int level,
            DegradationAction action,
            long retryDelayMs,
            boolean shouldSwitchModel,
            String fallbackTemplate,
            boolean cacheable,
            String message
    ) {
    }

    /**
     * 降级动作枚举。
     */
    public enum DegradationAction {
        /** 重试（指数退避） */
        RETRY,
        /** 切换备用模型后重试 */
        SWITCH_MODEL,
        /** 返回模板兜底 */
        TEMPLATE_FALLBACK,
        /** 写入死信队列，标记人工接管 */
        DEAD_LETTER
    }

    /**
     * 记录一次失败并获取降级决策。
     *
     * <p>每次 Agent 执行失败时调用此方法，根据累计失败次数返回对应的降级策略。
     *
     * @param taskId     任务 ID
     * @param agentStage Agent 阶段名称
     * @param error      错误信息
     * @param reason     失败原因分类
     * @param context    上下文快照（可为 null）
     * @return 降级决策
     */
    public DegradationDecision onFailure(String taskId, String agentStage, String error,
                                         FailureReason reason, Map<String, Object> context) {
        FailureContext fc = failureContexts.computeIfAbsent(taskId, k -> {
            FailureContext c = new FailureContext();
            c.failureCount = 0;
            c.currentModel = "primary";
            return c;
        });

        fc.failureCount++;
        fc.lastError = error;
        fc.lastFailureTime = System.currentTimeMillis();

        log.warn("[Degradation] 任务失败 #{}: taskId={}, stage={}, reason={}, error={}",
                fc.failureCount, taskId, agentStage, reason, truncate(error, 200));

        return decide(fc, taskId, agentStage, error, reason, context);
    }

    /**
     * 根据失败次数决定降级策略。
     */
    private DegradationDecision decide(FailureContext fc, String taskId, String agentStage,
                                        String error, FailureReason reason,
                                        Map<String, Object> context) {
        int n = fc.failureCount;

        // Level 1: 失败 1-2 次 → 重试 + 指数退避
        if (n <= MAX_RETRY) {
            long delay = Math.min(BACKOFF_BASE_MS * (1L << (n - 1)), BACKOFF_MAX_MS);
            log.info("[Degradation] Level 1: 重试（第 {} 次），退避 {}ms: taskId={}", n, delay, taskId);
            return new DegradationDecision(
                    1, DegradationAction.RETRY, delay, false, null, true,
                    "重试（指数退避 " + delay + "ms）"
            );
        }

        // Level 2: 失败 3 次 → 切换备用模型
        if (n == MAX_RETRY + 1) {
            fc.modelSwitched = true;
            fc.currentModel = "fallback";
            log.info("[Degradation] Level 2: 切换备用模型: taskId={}", taskId);
            return new DegradationDecision(
                    2, DegradationAction.SWITCH_MODEL, 0, true, null, false,
                    "切换备用模型重试"
            );
        }

        // Level 3: 失败 4 次 → 模板兜底 + cacheable=false
        if (n == MAX_RETRY + 2) {
            String template = generateFallbackTemplate(agentStage, error);
            log.info("[Degradation] Level 3: 模板兜底（cacheable=false）: taskId={}", taskId);
            return new DegradationDecision(
                    3, DegradationAction.TEMPLATE_FALLBACK, 0, false, template, false,
                    "模板兜底（禁止缓存）"
            );
        }

        // Level 4: 失败 5+ 次 → 死信队列 + 人工接管
        DeadLetterEntry entry = new DeadLetterEntry(
                taskId, agentStage, error,
                context != null ? new HashMap<>(context) : new HashMap<>(),
                System.currentTimeMillis(), reason
        );
        deadLetterQueue.addLast(entry);
        log.error("[Degradation] Level 4: 写入死信队列，标记人工接管: taskId={}, stage={}, queueSize={}",
                taskId, agentStage, deadLetterQueue.size());
        return new DegradationDecision(
                4, DegradationAction.DEAD_LETTER, 0, false, null, false,
                "已写入死信队列，等待人工接管"
        );
    }

    /**
     * 记录任务成功，清除失败上下文。
     *
     * @param taskId 任务 ID
     */
    public void onSuccess(String taskId) {
        FailureContext fc = failureContexts.remove(taskId);
        if (fc != null && fc.failureCount > 0) {
            log.info("[Degradation] 任务恢复成功: taskId={}, previousFailures={}", taskId, fc.failureCount);
        }
    }

    /**
     * 获取指定任务的当前失败次数。
     *
     * @param taskId 任务 ID
     * @return 失败次数（未记录返回 0）
     */
    public int getFailureCount(String taskId) {
        FailureContext fc = failureContexts.get(taskId);
        return fc != null ? fc.failureCount : 0;
    }

    /**
     * 检查任务是否已切换到备用模型。
     *
     * @param taskId 任务 ID
     * @return true 表示已切换到备用模型
     */
    public boolean isModelSwitched(String taskId) {
        FailureContext fc = failureContexts.get(taskId);
        return fc != null && fc.modelSwitched;
    }

    /**
     * 从死信队列取出条目供人工审查。
     *
     * @return 死信队列条目（队列为空返回 null）
     */
    public DeadLetterEntry pollDeadLetter() {
        return deadLetterQueue.pollFirst();
    }

    /**
     * 获取死信队列当前大小。
     */
    public int getDeadLetterQueueSize() {
        return deadLetterQueue.size();
    }

    /**
     * 窥探死信队列中的所有条目（不消费）。
     */
    public java.util.List<DeadLetterEntry> peekDeadLetterQueue() {
        return new java.util.ArrayList<>(deadLetterQueue);
    }

    /**
     * 清理已完成任务的失败上下文。
     *
     * @param taskId 任务 ID
     */
    public void cleanup(String taskId) {
        failureContexts.remove(taskId);
    }

    /**
     * 获取降级统计信息（用于监控面板）。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeFailureContexts", failureContexts.size());
        stats.put("deadLetterQueueSize", deadLetterQueue.size());

        // 统计各层级的触发次数
        Map<Integer, Integer> levelCounts = new HashMap<>();
        for (FailureContext fc : failureContexts.values()) {
            int level = Math.min(fc.failureCount, 4);
            levelCounts.merge(level, 1, Integer::sum);
        }
        stats.put("levelDistribution", levelCounts);
        return stats;
    }

    // ──────────────────────── 内部方法 ────────────────────────

    /**
     * 生成模板兜底文案。
     *
     * <p>根据 Agent 阶段生成不同的兜底文案，确保即使降级也有可交付内容。
     */
    private String generateFallbackTemplate(String agentStage, String error) {
        String stage = agentStage != null ? agentStage.toLowerCase() : "";
        return switch (stage) {
            case "topic-planning" -> """
                    ## 选题建议（降级生成）

                    由于系统暂时无法完成完整的选题分析，以下是基于常规经验的推荐选题方向：

                    1. **行业热点追踪**：关注近期 AI、大模型、内容创作等领域的技术动态
                    2. **用户痛点分析**：从评论区、社群反馈中提取高频问题
                    3. **竞品内容对比**：分析同类账号近期爆款内容结构

                    > 注意：此内容为降级兜底文案，建议人工审核后发布。
                    """;
            case "content-creation" -> """
                    ## 内容初稿（降级生成）

                    由于系统暂时无法完成完整的内容创作，以下是一份基础框架供编辑参考：

                    ### 标题
                    [待编辑：基于选题方向拟定吸引人的标题]

                    ### 正文
                    [待编辑：在此处补充正文内容]

                    > 注意：此内容为降级兜底文案，cacheable=false，建议人工完善后发布。
                    """;
            default -> """
                    ## 系统提示（降级模式）

                    当前 Agent 执行已触发降级策略。建议：
                    1. 检查模型服务状态
                    2. 稍后重试
                    3. 如持续失败请联系人工接管

                    > 此结果已标记 cacheable=false，不会被缓存。
                    """;
        };
    }

    /** 截断文本。 */
    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /**
     * 指数退避等待。
     *
     * @param delayMs 等待毫秒数
     */
    public static void backoffSleep(long delayMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
