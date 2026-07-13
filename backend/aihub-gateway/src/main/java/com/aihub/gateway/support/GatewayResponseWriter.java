package com.aihub.gateway.support;

import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 按统一响应体契约写回错误响应。
 *
 * <p>鉴权过滤器与全局异常处理共用，保证两条路径产出同一个响应体，避免一处 401 一处 200 + code。
 * HTTP 状态码固定 200，业务结果由响应体 {@code code} 表达（docs/API.md §3）。
 */
@Component
public class GatewayResponseWriter {

    private final ObjectMapper objectMapper;

    public GatewayResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(new IllegalStateException("response already committed, cannot write error body"));
        }
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.fail(errorCode));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
