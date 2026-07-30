package com.contentops.common.exception;

/**
 * 业务异常 — 对应客户端可纠正的错误（HTTP 4xx）。
 * 如：工作流不存在、状态不匹配、参数缺失等。
 */
public class BusinessException extends BaseException {

    public BusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode, errorCode.formatMessage(args));
    }

    public BusinessException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, errorCode.formatMessage(args), cause);
    }
}
