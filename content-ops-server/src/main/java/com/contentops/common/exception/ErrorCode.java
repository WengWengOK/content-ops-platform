package com.contentops.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 统一错误码定义 — 业务码 + HTTP状态码 + 消息模板。
 */
@Getter
public enum ErrorCode {

    // ════════════════ 工作流相关 4xx ════════════════
    WORKFLOW_NOT_FOUND(HttpStatus.NOT_FOUND, 40401, "工作流不存在: %s"),
    WORKFLOW_NOT_AWAITING_REVIEW(HttpStatus.CONFLICT, 40901, "工作流当前状态不是等待人工审核，当前状态: %s"),
    WORKFLOW_NOT_AWAITING_CONFIRMATION(HttpStatus.CONFLICT, 40902, "工作流当前状态不是等待确认，当前状态: %s"),
    NO_SUBSTAGE_TO_CONFIRM(HttpStatus.BAD_REQUEST, 40001, "当前没有待确认的子阶段，可能是普通阶段审批"),
    MISSING_REQUIRED_INPUT(HttpStatus.BAD_REQUEST, 40002, "缺少必需的输入参数: %s"),
    INVALID_WORKFLOW_STATE(HttpStatus.CONFLICT, 40903, "工作流状态非法: %s"),

    // ════════════════ 系统相关 5xx ════════════════
    PIPELINE_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "流水线执行失败: %s"),
    AGENT_CALL_FAILED(HttpStatus.BAD_GATEWAY, 50201, "Agent调用失败: %s"),
    REDIS_OPERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, 50301, "Redis操作失败: %s"),
    KAFKA_PUBLISH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, 50302, "Kafka事件发布失败: %s"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 50000, "服务器内部错误");

    private final HttpStatus httpStatus;
    private final int code;
    private final String messageTemplate;

    ErrorCode(HttpStatus httpStatus, int code, String messageTemplate) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.messageTemplate = messageTemplate;
    }

    /**
     * 格式化错误消息。
     */
    public String formatMessage(Object... args) {
        if (args == null || args.length == 0) {
            return messageTemplate;
        }
        return String.format(messageTemplate.replace("%s", "%s"), args);
    }
}
