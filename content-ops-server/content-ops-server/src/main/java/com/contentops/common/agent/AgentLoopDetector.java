package com.contentops.common.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 循环检测器 — 基于 Action 签名 hash 检测重复模式。
 *
 * <p><b>面试考点（字节必考）：</b>"reflection 失败 3 次后怎么处理？"
 * "怎么避免多个 Agent 相互扯皮或死循环？"
 *
 * <p>本检测器通过记录最近 N 次 Action 签名（tool + params hash），
 * 检测 Agent 执行循环中的重复调用模式：
 * <ul>
 *   <li><b>连续相同 Action</b>：连续 3 次调用相同工具 + 相同参数 → 自动中断</li>
 *   <li><b>循环模式检测</b>：检测 A→B→A→B 交替模式（窗口内重复率超阈值）</li>
 *   <li><b>工具降权</b>：连续两次失败的工具从可选列表剔除</li>
 *   <li><b>状态去重</b>：同一工具 + 同一组参数在同一会话内调用过直接返回缓存结果</li>
 * </ul>
 *
 * <h3>工作流程</h3>
 * <pre>
 *   Action 1: search("AI趋势")  → hash=a1b2c3  → 无重复
 *   Action 2: search("AI趋势")  → hash=a1b2c3  → 连续 2 次，警告
 *   Action 3: search("AI趋势")  → hash=a1b2c3  → 连续 3 次，中断！触发降级
 * </pre>
 *
 * @see AgentDegradationStrategy
 */
@Slf4j
@Component
public class AgentLoopDetector {

    /** 连续相同 Action 的中断阈值（历史中已有 2 次相同签名时，第 3 次触发中断） */
    private static final int CONSECUTIVE_INTERRUPT_THRESHOLD = 2;

    /** 循环模式检测窗口大小 */
    private static final int PATTERN_WINDOW_SIZE = 6;

    /** 循环模式重复率阈值（窗口内重复 Action 占比超过此值则判定为循环） */
    private static final double PATTERN_REPEAT_THRESHOLD = 0.6;

    /** 工具失败降权阈值：连续失败达到此值则从可选列表剔除 */
    private static final int TOOL_FAILURE_DEMOTE_THRESHOLD = 2;

    /** 每个会话的 Action 签名历史（会话 ID → 签名队列） */
    private final Map<String, Deque<String>> actionHistory = new ConcurrentHashMap<>();

    /** 每个会话的工具调用缓存（会话 ID → (签名 → 结果)） */
    private final Map<String, Map<String, String>> callCache = new ConcurrentHashMap<>();

    /** 工具失败计数器（工具名 → 连续失败次数） */
    private final Map<String, Integer> toolFailureCount = new ConcurrentHashMap<>();

    /** 被降权的工具集合 */
    private final Map<String, Long> demotedTools = new ConcurrentHashMap<>();

    /** 工具降权冷却时间（毫秒） */
    private static final long DEMOTION_COOLDOWN_MS = 60_000;

    /**
     * 循环检测结果。
     *
     * @param loopDetected  是否检测到循环
     * @param reason        检测原因（如 "consecutive_3" 或 "pattern_repeat_0.67"）
     * @param cachedResult  命中状态去重时的缓存结果（未命中为 null）
     */
    public record LoopDetectionResult(boolean loopDetected, String reason, String cachedResult) {
    }

    /**
     * 记录一次 Action 调用并检测循环。
     *
     * <p>该方法完成以下工作：
     * <ol>
     *   <li>计算 Action 签名 hash</li>
     *   <li>检查状态去重：同一会话内相同签名是否已调用过</li>
     *   <li>检查连续相同 Action：连续 N 次相同签名则判定循环</li>
     *   <li>检查循环模式：窗口内重复率超阈值则判定循环</li>
     *   <li>记录签名到历史队列</li>
     * </ol>
     *
     * @param sessionId 会话标识（通常是 taskId）
     * @param toolName  工具名称
     * @param params    工具参数（将被 hash 化）
     * @return 检测结果
     */
    public LoopDetectionResult checkAndRecord(String sessionId, String toolName, String params) {
        String signature = computeSignature(toolName, params);
        Deque<String> history = actionHistory.computeIfAbsent(sessionId, k -> new LinkedList<>());

        // 1. 状态去重：检查同一签名是否已调用过
        Map<String, String> cache = callCache.computeIfAbsent(sessionId, k -> new HashMap<>());
        String cached = cache.get(signature);
        if (cached != null) {
            log.info("[LoopDetector] 状态去重命中: sessionId={}, tool={}, signature={}",
                    sessionId, toolName, signature.substring(0, 8));
            return new LoopDetectionResult(false, "cache_hit", cached);
        }

        // 2. 检查连续相同 Action
        int consecutiveCount = countConsecutive(history, signature);
        if (consecutiveCount >= CONSECUTIVE_INTERRUPT_THRESHOLD) {
            log.warn("[LoopDetector] 检测到连续 {} 次相同 Action，触发中断: sessionId={}, tool={}",
                    consecutiveCount, sessionId, toolName);
            return new LoopDetectionResult(true, "consecutive_" + consecutiveCount, null);
        }

        // 3. 检查循环模式（A→B→A→B 交替）
        if (history.size() >= PATTERN_WINDOW_SIZE) {
            double repeatRate = calculateRepeatRate(history, PATTERN_WINDOW_SIZE);
            if (repeatRate >= PATTERN_REPEAT_THRESHOLD) {
                log.warn("[LoopDetector] 检测到循环模式（重复率 {}）: sessionId={}, tool={}",
                        String.format("%.2f", repeatRate), sessionId, toolName);
                return new LoopDetectionResult(true,
                        "pattern_repeat_" + String.format("%.2f", repeatRate), null);
            }
        }

        // 4. 记录签名到历史
        history.addLast(signature);
        while (history.size() > PATTERN_WINDOW_SIZE * 2) {
            history.pollFirst();
        }

        return new LoopDetectionResult(false, null, null);
    }

    /**
     * 缓存工具调用结果（用于状态去重）。
     *
     * @param sessionId 会话标识
     * @param toolName  工具名称
     * @param params    工具参数
     * @param result    调用结果
     */
    public void cacheResult(String sessionId, String toolName, String params, String result) {
        String signature = computeSignature(toolName, params);
        callCache.computeIfAbsent(sessionId, k -> new HashMap<>()).put(signature, result);
    }

    /**
     * 记录工具调用失败，连续失败达到阈值时降权。
     *
     * @param toolName 工具名称
     * @return 是否触发了降权
     */
    public boolean recordToolFailure(String toolName) {
        int count = toolFailureCount.merge(toolName, 1, Integer::sum);
        if (count >= TOOL_FAILURE_DEMOTE_THRESHOLD) {
            demotedTools.put(toolName, System.currentTimeMillis());
            log.warn("[LoopDetector] 工具 {} 连续失败 {} 次，已降权（{}ms 内不可用）",
                    toolName, count, DEMOTION_COOLDOWN_MS);
            return true;
        }
        return false;
    }

    /**
     * 记录工具调用成功，重置失败计数。
     *
     * @param toolName 工具名称
     */
    public void recordToolSuccess(String toolName) {
        toolFailureCount.remove(toolName);
    }

    /**
     * 检查工具是否被降权。
     *
     * @param toolName 工具名称
     * @return true 表示工具当前被降权（不可用）
     */
    public boolean isToolDemoted(String toolName) {
        Long demotedAt = demotedTools.get(toolName);
        if (demotedAt == null) {
            return false;
        }
        // 冷却期过后自动恢复
        if (System.currentTimeMillis() - demotedAt > DEMOTION_COOLDOWN_MS) {
            demotedTools.remove(toolName);
            toolFailureCount.remove(toolName);
            log.info("[LoopDetector] 工具 {} 降权冷却期结束，已恢复", toolName);
            return false;
        }
        return true;
    }

    /**
     * 清理会话的检测状态。
     *
     * @param sessionId 会话标识
     */
    public void cleanupSession(String sessionId) {
        actionHistory.remove(sessionId);
        callCache.remove(sessionId);
    }

    /**
     * 获取当前检测统计信息（用于监控和调试）。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeSessions", actionHistory.size());
        stats.put("demotedTools", demotedTools.keySet());
        stats.put("toolFailureCounts", new HashMap<>(toolFailureCount));
        return stats;
    }

    // ──────────────────────── 内部方法 ────────────────────────

    /**
     * 计算 Action 签名 hash（SHA-256 取前 16 字符）。
     */
    private String computeSignature(String toolName, String params) {
        String input = (toolName != null ? toolName : "") + "|" + (params != null ? params : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            // SHA-256 一定存在，异常时退化到字符串 hash
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 统计历史队列中末尾连续相同签名的次数。
     */
    private int countConsecutive(Deque<String> history, String signature) {
        int count = 0;
        // 从队列尾部向前数连续相同的签名
        var it = ((LinkedList<String>) history).descendingIterator();
        while (it.hasNext()) {
            if (it.next().equals(signature)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * 计算窗口内签名重复率。
     *
     * <p>取窗口大小的 Action 签名，统计去重后的唯一签名占比。
     * 重复率 = 1 - (唯一签名数 / 窗口大小)。
     */
    private double calculateRepeatRate(Deque<String> history, int windowSize) {
        if (history.size() < windowSize) {
            return 0.0;
        }
        // 取最近 windowSize 个签名
        var list = new LinkedList<>(history);
        int start = Math.max(0, list.size() - windowSize);
        var window = list.subList(start, list.size());

        long uniqueCount = window.stream().distinct().count();
        return 1.0 - ((double) uniqueCount / windowSize);
    }
}
