package com.aihub.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet 栈 traceId 过滤器。读取上游 {@code X-Trace-Id}，缺失时生成；
 * 写入 MDC、回写响应头，请求结束后清理 MDC。
 *
 * <p>上游已带 traceId 时沿用而非重新生成，否则链路会断裂。
 */
public class TraceIdServletFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = TraceIdSupport.resolveTraceId(request.getHeader(TraceConstants.TRACE_ID_HEADER));
        request.setAttribute(TraceConstants.ATTR_TRACE_ID, traceId);
        response.setHeader(TraceConstants.TRACE_ID_HEADER, traceId);
        MDC.put(TraceConstants.MDC_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceConstants.MDC_TRACE_ID);
        }
    }
}
