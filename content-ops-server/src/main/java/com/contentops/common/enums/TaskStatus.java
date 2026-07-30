package com.contentops.common.enums;

/**
 * Task status throughout the workflow lifecycle.
 */
public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    AWAITING_HUMAN,
    /** P1: 等待异步 Agent 结果（Kafka 异步模式，用于长耗时 Agent） */
    AWAITING_ASYNC,
    COMPLETED,
    FAILED,
    SKIPPED
}
