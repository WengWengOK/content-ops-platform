package com.contentops.common.util;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * MDC 日志上下文注入过滤器 — 为每个请求注入 traceId 和 userId。
 *
 * <p><b>P1 修复：</b>此前日志中未关联 traceId，分布式场景下日志追踪困难。
 * 本过滤器为每个 HTTP 请求生成唯一 traceId 并注入 MDC，使所有日志行
 * 可通过 traceId 关联同一请求的完整链路。
 *
 * <p>注入的 MDC 字段：
 * <ul>
 *   <li>{@code traceId} — 从 X-Trace-Id Header 获取或自动生成 UUID</li>
 *   <li>{@code method} — HTTP 方法（GET/POST 等）</li>
 *   <li>{@code uri} — 请求 URI（已脱敏，不包含 query 参数中的 token）</li>
 * </ul>
 *
 * <p>在 logback-spring.xml 中通过 {@code %X{traceId}} 引用。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcLoggingFilter implements Filter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_METHOD = "method";
    private static final String MDC_URI = "uri";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                        FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_METHOD, request.getMethod());
        MDC.put(MDC_URI, sanitizeUri(request.getRequestURI()));

        // 在响应头中返回 traceId，便于前端关联
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    /**
     * 对 URI 进行脱敏，移除可能包含 token 的查询参数。
     */
    private String sanitizeUri(String uri) {
        if (uri == null) return "";
        // 只保留 path 部分，不记录 query 参数（可能包含 token）
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }
}
