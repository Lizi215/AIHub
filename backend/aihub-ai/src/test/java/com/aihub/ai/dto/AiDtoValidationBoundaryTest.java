package com.aihub.ai.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3：AI 模块 Bean Validation 边界矩阵。
 *
 * <p>{@code sessionId} 既限长又限形（UUID 样式），两层约束要各自命中。
 */
class AiDtoValidationBoundaryTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static Set<String> violations(Object bean, String property) {
        return validator.validate(bean).stream()
                .filter(v -> v.getPropertyPath().toString().equals(property))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static boolean valid(Object bean, String property) {
        return violations(bean, property).isEmpty();
    }

    @Test
    @DisplayName("scene：空白拒绝 / 30 通过 / 31 拒绝")
    void sceneBoundary() {
        assertThat(valid(new CreateAgentTaskRequest("  ", "r", null), "scene")).isFalse();
        assertThat(valid(new CreateAgentTaskRequest("s".repeat(30), "r", null), "scene")).isTrue();
        assertThat(valid(new CreateAgentTaskRequest("s".repeat(31), "r", null), "scene")).isFalse();
    }

    @Test
    @DisplayName("request：空白拒绝 / 1000 通过 / 1001 拒绝")
    void requestBoundary() {
        assertThat(valid(new CreateAgentTaskRequest("scene", "", null), "request")).isFalse();
        assertThat(valid(new CreateAgentTaskRequest("scene", "r".repeat(1000), null), "request")).isTrue();
        assertThat(valid(new CreateAgentTaskRequest("scene", "r".repeat(1001), null), "request")).isFalse();
    }

    @Test
    @DisplayName("sessionId：null 通过；8 通过 / 7 拒绝；64 通过 / 65 拒绝；非十六进制拒绝")
    void sessionIdBoundary() {
        assertThat(valid(new CreateAgentTaskRequest("scene", "request", null), "sessionId")).isTrue();
        assertThat(valid(new CreateAgentTaskRequest("scene", "request", "abcdef12"), "sessionId")).isTrue();
        assertThat(valid(new CreateAgentTaskRequest("scene", "request", "abcdef1"), "sessionId")).isFalse();
        assertThat(valid(new CreateAgentTaskRequest("scene", "request", "a".repeat(64)), "sessionId")).isTrue();
        assertThat(valid(new CreateAgentTaskRequest("scene", "request", "a".repeat(65)), "sessionId")).isFalse();
        // 含非十六进制字符（z）→ 形态非法
        assertThat(valid(new CreateAgentTaskRequest("scene", "request", "zzzzzzzz"), "sessionId")).isFalse();
        // 标准 UUID 形如 36 长度，含连字符，应通过
        assertThat(valid(new CreateAgentTaskRequest("scene", "request", "550e8400-e29b-41d4-a716-446655440000"), "sessionId")).isTrue();
    }
}
