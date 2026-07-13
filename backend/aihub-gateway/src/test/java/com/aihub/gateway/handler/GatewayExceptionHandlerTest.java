package com.aihub.gateway.handler;

import com.aihub.common.result.ErrorCode;
import com.aihub.common.trace.TraceConstants;
import com.aihub.gateway.support.GatewayResponseWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关全局异常处理（WebFlux 栈，必须实现 {@code ErrorWebExceptionHandler}）。
 */
@DisplayName("网关 GatewayExceptionHandler")
class GatewayExceptionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GatewayExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GatewayExceptionHandler(new GatewayResponseWriter(objectMapper));
    }

    private int codeOf(MockServerWebExchange exchange) throws Exception {
        DataBuffer buffer = exchange.getResponse().getBody().blockFirst();
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8)).path("code").asInt();
    }

    @Test
    @DisplayName("路由不存在（404）→ 如实返回 NOT_FOUND，而不是笼统的 system error")
    void notFoundShouldMapToNotFound() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/does-not-exist").build());

        StepVerifier.create(handler.handle(exchange,
                new ResponseStatusException(HttpStatus.NOT_FOUND))).verifyComplete();

        assertThat(codeOf(exchange)).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("下游异常 → 统一 SYSTEM_ERROR（不把堆栈透给调用方）")
    void unexpectedExceptionShouldMapToSystemError() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/assets").build());

        StepVerifier.create(handler.handle(exchange, new IllegalStateException("downstream exploded")))
                .verifyComplete();

        DataBuffer buffer = exchange.getResponse().getBody().blockFirst();
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        JsonNode node = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        assertThat(node.path("code").asInt()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(node.path("message").asText()).doesNotContain("exploded");
    }

    @Test
    @DisplayName("响应已提交时不再写错误体（向上抛，避免半截响应）")
    void committedResponseShouldNotBeRewritten() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/assets").build());
        exchange.getResponse().setComplete().block();

        StepVerifier.create(handler.handle(exchange, new IllegalStateException("late failure")))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(IllegalStateException.class))
                .verify();
    }

    @Test
    @DisplayName("traceId 属性存在时不抛异常（跨服务排障依赖它）")
    void shouldTolerateTraceIdAttribute() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/assets").build());
        exchange.getAttributes().put(TraceConstants.ATTR_TRACE_ID, "trace-123");

        StepVerifier.create(handler.handle(exchange, new IllegalStateException("boom"))).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("异常处理器注册为最高优先级（@Order(-1)），否则会走默认错误页破坏契约")
    void shouldBeHighestPrecedence() {
        assertThat(GatewayExceptionHandler.class.getAnnotation(org.springframework.core.annotation.Order.class))
                .isNotNull()
                .extracting(annotation -> annotation.value())
                .isEqualTo(-1);
    }
}
