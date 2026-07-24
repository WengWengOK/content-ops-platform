package com.contentops.common.enums;

/**
 * Task status throughout the workflow lifecycle.
 */
public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    AWAITING_HUMAN,
    COMPLETED,
    FAILED,
    SKIPPED
}
