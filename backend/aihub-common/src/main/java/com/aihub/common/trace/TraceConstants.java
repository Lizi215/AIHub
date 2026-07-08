package com.aihub.common.trace;

/**
 * traceId 链路常量。全链路同一 traceId 是刚需，见 docs/SECURITY.md §4。
 */
public final class TraceConstants {

    /** HTTP 头名称，Gateway 生成、Feign 透传、响应回写均使用该头。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 当前登录用户 id 的透传头名称，由 Gateway 校验 JWT 后写入。 */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** 用户角色透传头名称，角色随 JWT 签发，业务服务直接从请求头读取。 */
    public static final String USER_ROLE_HEADER = "X-User-Role";

    /** Logback MDC 中的 key，日志格式中通过 %X{traceId} 引用。 */
    public static final String MDC_TRACE_ID = "traceId";

    /** traceId 在请求属性中的 key，供非 MDC 场景（如 WebFlux）读取。 */
    public static final String ATTR_TRACE_ID = TraceConstants.class.getName() + ".traceId";

    /** traceId 在 Reactor Context 中的 key。 */
    public static final String CONTEXT_TRACE_ID = TraceConstants.class.getName() + ".contextTraceId";

    private TraceConstants() {
    }
}
