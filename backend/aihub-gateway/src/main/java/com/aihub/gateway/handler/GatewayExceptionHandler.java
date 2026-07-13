package com.aihub.gateway.handler;

import com.aihub.common.result.ErrorCode;
import com.aihub.common.trace.TraceConstants;
import com.aihub.gateway.support.GatewayResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 全局异常处理。
 *
 * <p>Gateway 基于 WebFlux，异常不经过 MVC 的 HandlerExceptionResolver，所以必须实现
 * {@link ErrorWebExceptionHandler}；用 {@code @RestControllerAdvice} 会让默认错误页直接透出。
 */
@Component
@Order(-1)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private final GatewayResponseWriter responseWriter;

    public GatewayExceptionHandler(GatewayResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        Object traceId = exchange.getAttribute(TraceConstants.ATTR_TRACE_ID);
        log.error("gateway request failed, traceId={}, path={}",
                traceId, exchange.getRequest().getURI().getRawPath(), ex);

        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        return responseWriter.write(exchange, resolveErrorCode(ex));
    }

    private ErrorCode resolveErrorCode(Throwable ex) {
        if (ex instanceof ResponseStatusException statusException
                && statusException.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.NOT_FOUND;
        }
        return ErrorCode.SYSTEM_ERROR;
    }
}
