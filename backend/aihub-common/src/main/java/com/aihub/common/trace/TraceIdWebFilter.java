package com.aihub.common.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 栈 traceId 过滤器（Gateway 使用）。
 *
 * <p>Reactive 链路的执行线程由 Reactor 调度，MDC 不会自动跨线程传播。这里把 traceId 写入
 * exchange attribute 与 Reactor Context 供链路读取，并显式打印 traceId 而不依赖 MDC，
 * 保证 Gateway 侧日志必然可见。
 *
 * <p>响应头在 {@code beforeCommit} 阶段写入：入口处直接设置会被被代理服务回写的同名头叠加，
 * 客户端收到重复的 {@code X-Trace-Id}；{@code beforeCommit} 在下游响应头合并后执行，用 {@code set} 收敛为单值。
 */
public class TraceIdWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = TraceIdSupport.resolveTraceId(
                exchange.getRequest().getHeaders().getFirst(TraceConstants.TRACE_ID_HEADER));

        exchange.getAttributes().put(TraceConstants.ATTR_TRACE_ID, traceId);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(TraceConstants.TRACE_ID_HEADER, traceId);
            return Mono.empty();
        });

        log.info("gateway inbound, traceId={}, method={}, path={}",
                traceId,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getRawPath());

        return chain.filter(exchange)
                .contextWrite(context -> context.put(TraceConstants.CONTEXT_TRACE_ID, traceId))
                .doFirst(() -> MDC.put(TraceConstants.MDC_TRACE_ID, traceId))
                .doFinally(signalType -> MDC.remove(TraceConstants.MDC_TRACE_ID));
    }
}
