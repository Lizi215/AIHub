package com.aihub.community.dto;

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

/** Community 模块 Bean Validation 边界矩阵（n-1 / n / n+1 与必填）。 */
class CommunityDtoValidationBoundaryTest {

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

    // ---------------- CreatePostRequest ----------------

    @Test
    @DisplayName("CreatePostRequest.title：200 通过 / 201 拒绝 / 空白拒绝")
    void postTitleBoundary() {
        assertThat(valid(new CreatePostRequest("GUIDE", "t".repeat(200), "c", null), "title")).isTrue();
        assertThat(valid(new CreatePostRequest("GUIDE", "t".repeat(201), "c", null), "title")).isFalse();
        assertThat(valid(new CreatePostRequest("GUIDE", "  ", "c", null), "title")).isFalse();
    }

    @Test
    @DisplayName("CreatePostRequest.type / content：不得空白")
    void postRequiredBoundary() {
        assertThat(valid(new CreatePostRequest("", "t", "c", null), "type")).isFalse();
        assertThat(valid(new CreatePostRequest("GUIDE", "t", "", null), "content")).isFalse();
        assertThat(valid(new CreatePostRequest("GUIDE", "t", "c", null), "type")).isTrue();
    }

    // ---------------- CreateIssueRequest ----------------

    @Test
    @DisplayName("CreateIssueRequest.assetId：null 拒绝 / 有值通过")
    void issueAssetIdBoundary() {
        assertThat(valid(new CreateIssueRequest(null, "v1", "t", "c"), "assetId")).isFalse();
        assertThat(valid(new CreateIssueRequest(1L, "v1", "t", "c"), "assetId")).isTrue();
    }

    @Test
    @DisplayName("CreateIssueRequest.version：null 通过 / 50 通过 / 51 拒绝")
    void issueVersionBoundary() {
        assertThat(valid(new CreateIssueRequest(1L, null, "t", "c"), "version")).isTrue();
        assertThat(valid(new CreateIssueRequest(1L, "v".repeat(50), "t", "c"), "version")).isTrue();
        assertThat(valid(new CreateIssueRequest(1L, "v".repeat(51), "t", "c"), "version")).isFalse();
    }

    @Test
    @DisplayName("CreateIssueRequest.title：200 通过 / 201 拒绝；content 必填")
    void issueTitleContentBoundary() {
        assertThat(valid(new CreateIssueRequest(1L, "v1", "t".repeat(200), "c"), "title")).isTrue();
        assertThat(valid(new CreateIssueRequest(1L, "v1", "t".repeat(201), "c"), "title")).isFalse();
        assertThat(valid(new CreateIssueRequest(1L, "v1", "t", "  "), "content")).isFalse();
    }

    // ---------------- CreateCommentRequest ----------------

    @Test
    @DisplayName("CreateCommentRequest.content：不得空白")
    void commentContentBoundary() {
        assertThat(valid(new CreateCommentRequest("  "), "content")).isFalse();
        assertThat(valid(new CreateCommentRequest(""), "content")).isFalse();
        assertThat(valid(new CreateCommentRequest("nice"), "content")).isTrue();
    }
}
