package com.aihub.common.trace;

import java.util.UUID;

/**
 * traceId 生成与解析工具。
 *
 * <p>不依赖任何 Servlet / Reactive API：Servlet 过滤器与 WebFlux 过滤器共用同一套规则，
 * 否则一侧会加载到对方技术栈的类（如 Gateway 没有 Servlet API）。
 */
public final class TraceIdSupport {

    private TraceIdSupport() {
    }

    /**
     * 解析上游传入的 traceId；缺失时生成新的。
     *
     * @param headerValue 上游 HTTP 头中的 traceId，允许为 null
     * @return 非空的 traceId
     */
    public static String resolveTraceId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return generateTraceId();
        }
        return headerValue.trim();
    }

    /**
     * 生成新的 traceId（32 位无连字符 UUID）。
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
