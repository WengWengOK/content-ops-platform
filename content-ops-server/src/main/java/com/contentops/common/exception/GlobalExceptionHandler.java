package com.contentops.common.exception;

import com.contentops.common.dto.AgentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理器 — 统一异常返回格式。
 *
 * <p><b>P0 修复：</b>此前 Controller 层抛出的 RuntimeException 等异常
 * 会直接返回 Spring Boot 默认错误页面 / 堆栈信息，存在信息泄露风险。
 * 本类通过 {@code @RestControllerAdvice} 统一拦截并包装为标准 AgentResponse。
 *
 * <p>异常处理优先级：
 * <ol>
 *   <li>客户端请求错误 (4xx)：参数缺失、JSON 格式错误、请求体校验失败</li>
 *   <li>业务逻辑错误 (4xx/5xx)：IllegalArgumentException、IllegalStateException</li>
 *   <li>兜底异常 (5xx)：所有未捕获的 Exception</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ════════════════ 4xx 客户端错误 ════════════════

    /**
     * 请求参数缺失。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<AgentResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(AgentResponse.failure("system",
                        "缺少必需的请求参数: " + e.getParameterName()));
    }

    /**
     * 请求体 JSON 格式错误或无法解析。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AgentResponse<Void>> handleUnreadableMessage(
            HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(AgentResponse.failure("system",
                        "请求体格式错误，请检查 JSON 格式是否正确"));
    }

    /**
     * 请求参数校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AgentResponse<Void>> handleValidationException(
            MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("Validation failed: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(AgentResponse.failure("system", message));
    }

    /**
     * 非法参数 — 业务逻辑校验不通过。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentResponse<Void>> handleIllegalArgument(
            IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(AgentResponse.failure("system", e.getMessage()));
    }

    /**
     * 业务异常 — 客户端可纠正的错误（4xx）。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<AgentResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception [{}]: {}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(AgentResponse.failure("system", e.getMessage()));
    }

    /**
     * 系统异常 — 服务端内部错误（5xx）。
     */
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<AgentResponse<Void>> handleSystemException(SystemException e) {
        log.error("System exception [{}]: {}", e.getErrorCode().getCode(), e.getMessage(), e);
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(AgentResponse.failure("system", e.getMessage()));
    }

    /**
     * 请求路径不存在。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<AgentResponse<Void>> handleNotFound(
            NoHandlerFoundException e) {
        log.warn("No handler found for: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(AgentResponse.failure("system",
                        "接口不存在: " + e.getRequestURL()));
    }

    // ════════════════ 5xx 服务端错误 ════════════════

    /**
     * 非法状态 — 如分布式锁获取失败、工作流状态不一致。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AgentResponse<Void>> handleIllegalState(
            IllegalStateException e) {
        log.error("Illegal state error", e);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(AgentResponse.failure("system", e.getMessage()));
    }

    /**
     * 业务运行时异常 — 如工作流不存在、状态不匹配等。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AgentResponse<Void>> handleRuntimeException(
            RuntimeException e) {
        // BaseException 子类已被上方 handler 处理，不会到达这里
        log.error("Runtime exception", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AgentResponse.failure("system", e.getMessage()));
    }

    /**
     * 兜底异常处理 — 所有未被上方 handler 匹配的异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentResponse<Void>> handleGenericException(
            Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AgentResponse.failure("system", "服务器内部错误，请稍后重试"));
    }
}
