package com.contentops.common.exception;

/**
 * 系统异常 — 对应服务端内部错误（HTTP 5xx）。
 * 如：Redis操作失败、Kafka发布失败、流水线执行异常等。
 */
public class SystemException extends BaseException {

    public SystemException(ErrorCode errorCode, Object... args) {
        super(errorCode, errorCode.formatMessage(args));
    }

    public SystemException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, errorCode.formatMessage(args), cause);
    }
}
