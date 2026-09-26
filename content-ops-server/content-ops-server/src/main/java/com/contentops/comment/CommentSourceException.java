package com.contentops.comment;

/**
 * 评论数据源异常：携带可读错误码与排查提示，供接口层直接返回给前端/用户。
 */
public class CommentSourceException extends RuntimeException {

    private final String code;

    public CommentSourceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CommentSourceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}