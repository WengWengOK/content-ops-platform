package com.contentops.common.observability;

/**
 * LLM 追踪上下文：在阶段/Agent 执行前设置，供 GuardedChatModel 埋点时读取。
 * 线程内传递（与 WorkflowCostGuard 同机制），执行结束后必须 clear。
 */
public final class LlmTraceContext {

    private static final ThreadLocal<String> STAGE = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT = new ThreadLocal<>();

    private LlmTraceContext() {
    }

    public static void set(String stage, String agent) {
        STAGE.set(stage);
        AGENT.set(agent);
    }

    public static String stage() {
        return STAGE.get();
    }

    public static String agent() {
        return AGENT.get();
    }

    public static void clear() {
        STAGE.remove();
        AGENT.remove();
    }
}
