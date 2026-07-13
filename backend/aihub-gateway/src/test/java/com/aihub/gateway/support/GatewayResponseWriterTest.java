package com.aihub.gateway.support;

import com.aihub.common.result.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一错误响应体契约（docs/API.md §3）：HTTP 200 + 业务 code；鉴权与异常两条路径必须一致。
 */
@DisplayName("网关 GatewayResponseWriter")
class GatewayResponseWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayResponseWriter writer = new GatewayResponseWriter(objectMapper);

    @Test
    @DisplayName("写回：HTTP 状态固定 200，响应体为 {code,message,data:null}，Content-Type 为 JSON")
    void shouldWriteUnifiedErrorBody() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/agent/tasks").build());

        StepVerifier.create(writer.write(exchange, ErrorCode.UNAUTHORIZED)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);

        DataBuffer buffer = exchange.getResponse().getBody().blockFirst();
        JsonNode node = objectMapper.readTree(new String(bufferBytes(buffer), StandardCharsets.UTF_8));
        assertThat(node.path("code").asInt()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
        assertThat(node.path("message").asText()).isEqualTo(ErrorCode.UNAUTHORIZED.getMessage());
        assertThat(node.path("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("响应已提交后再写错误体 → 明确报错，而不是静默产生半截响应")
    void shouldRejectWhenResponseAlreadyCommitted() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/assets").build());
        exchange.getResponse().setComplete().block();

        StepVerifier.create(writer.write(exchange, ErrorCode.SYSTEM_ERROR))
                .expectErrorSatisfies(ex -> assertThat(ex)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("already committed"))
                .verify();
    }

    private byte[] bufferBytes(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return bytes;
    }
}
