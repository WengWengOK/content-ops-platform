package com.contentops.common.util;

import com.contentops.common.event.AgentTaskRequest;

import java.util.Map;

/**
 * 请求参数解析工具类 — 从 AgentTaskRequest 中解析输入参数。
 *
 * <p>解析优先级：{@code inputs} > {@code accumulatedArtifacts}。
 * 前序阶段的产物会累积在 accumulatedArtifacts 中，当前阶段可通过此类回退查找。
 *
 * <p>P2-12: 抽取自 6 个 Controller 中重复的 {@code resolveInput} 方法，消除代码重复。
 */
public final class RequestInputResolver {

    private RequestInputResolver() {
        // 工具类，禁止实例化
    }

    /**
     * 解析字符串类型的输入参数。
     *
     * <p>优先从 {@code request.getInputs()} 中查找，找不到则回退到
     * {@code request.getAccumulatedArtifacts()}（前序阶段产物）。
     *
     * @param request 请求对象
     * @param key     参数键名
     * @return 参数的字符串值，找不到或为 null 时返回 null
     */
    public static String resolve(AgentTaskRequest request, String key) {
        if (request == null || key == null) {
            return null;
        }

        // 优先从 inputs 中查找
        Map<String, Object> inputs = request.getInputs();
        if (inputs != null && inputs.containsKey(key)) {
            Object value = inputs.get(key);
            return value == null ? null : String.valueOf(value);
        }

        // 回退到 accumulatedArtifacts（前序阶段产物）
        Map<String, Object> artifacts = request.getAccumulatedArtifacts();
        if (artifacts != null && artifacts.containsKey(key)) {
            Object value = artifacts.get(key);
            return value == null ? null : String.valueOf(value);
        }

        return null;
    }

    /**
     * 解析字符串类型的输入参数，支持备选键名。
     *
     * <p>按顺序尝试 primaryKey 和 fallbackKeys，返回第一个非空结果。
     *
     * @param request       请求对象
     * @param primaryKey    首选参数键名
     * @param fallbackKeys  备选参数键名列表（按优先级排序）
     * @return 参数的字符串值，全部找不到时返回 null
     */
    public static String resolveWithFallback(AgentTaskRequest request,
                                              String primaryKey,
                                              String... fallbackKeys) {
        String value = resolve(request, primaryKey);
        if (value != null && !value.isBlank()) {
            return value;
        }
        for (String fallbackKey : fallbackKeys) {
            value = resolve(request, fallbackKey);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
