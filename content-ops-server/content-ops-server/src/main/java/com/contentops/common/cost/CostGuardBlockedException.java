package com.contentops.common.cost;

/**
 * 成本护栏阻断异常：工作流预算用尽或熔断器打开时抛出，
 * 由 Agent Controller 捕获后转为失败响应，工作流据此终止。
 */
public class CostGuardBlockedException extends RuntimeException {

    /** 错误消息标记：预算用尽（StageExecutor 据此将工作流置为 BUDGET_EXCEEDED） */
    public static final String BUDGET_MARKER = "[BUDGET_EXCEEDED]";
    /** 错误消息标记：熔断打开 */
    public static final String CIRCUIT_MARKER = "[CIRCUIT_OPEN]";

    public enum Reason {
        BUDGET_EXCEEDED,
        CIRCUIT_OPEN
    }

    private final Reason reason;

    public CostGuardBlockedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
