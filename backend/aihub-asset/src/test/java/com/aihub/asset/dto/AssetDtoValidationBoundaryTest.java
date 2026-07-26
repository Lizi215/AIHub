package com.aihub.asset.dto;

import com.aihub.asset.controller.TagController;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asset 模块 Bean Validation 长度边界矩阵（n-1 / n / n+1）。
 *
 * <p>含 Controller 内联的 {@code SetTagsRequest}，它是标签写入口的第一道校验。
 * 标签数量上限（≤10）与单名清洗（trim/去重/≤20）在 Service 层，本类只覆盖 Bean Validation 能拦下的部分。
 */
class AssetDtoValidationBoundaryTest {

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

    // ---------------- CreateAssetRequest ----------------

    @Test
    @DisplayName("CreateAssetRequest.name：空白拒绝 / 200 通过 / 201 拒绝")
    void createNameBoundary() {
        assertThat(valid(new CreateAssetRequest("  ", "s", "c"), "name")).isFalse();
        assertThat(valid(new CreateAssetRequest("n".repeat(200), "s", "c"), "name")).isTrue();
        assertThat(valid(new CreateAssetRequest("n".repeat(201), "s", "c"), "name")).isFalse();
    }

    @Test
    @DisplayName("CreateAssetRequest.summary：null 通过 / 500 通过 / 501 拒绝")
    void createSummaryBoundary() {
        assertThat(valid(new CreateAssetRequest("n", null, "c"), "summary")).isTrue();
        assertThat(valid(new CreateAssetRequest("n", "s".repeat(500), "c"), "summary")).isTrue();
        assertThat(valid(new CreateAssetRequest("n", "s".repeat(501), "c"), "summary")).isFalse();
    }

    @Test
    @DisplayName("CreateAssetRequest.category：null 通过 / 50 通过 / 51 拒绝")
    void createCategoryBoundary() {
        assertThat(valid(new CreateAssetRequest("n", "s", null), "category")).isTrue();
        assertThat(valid(new CreateAssetRequest("n", "s", "c".repeat(50)), "category")).isTrue();
        assertThat(valid(new CreateAssetRequest("n", "s", "c".repeat(51)), "category")).isFalse();
    }

    // ---------------- UpdateAssetRequest ----------------

    @Test
    @DisplayName("UpdateAssetRequest：name ≤200、summary ≤500、category ≤50")
    void updateBoundary() {
        assertThat(valid(new UpdateAssetRequest("n".repeat(200), "s".repeat(500), "c".repeat(50)), "name")).isTrue();
        assertThat(valid(new UpdateAssetRequest("n".repeat(201), "s", "c"), "name")).isFalse();
        assertThat(valid(new UpdateAssetRequest("n", "s".repeat(501), "c"), "summary")).isFalse();
        assertThat(valid(new UpdateAssetRequest("n", "s", "c".repeat(51)), "category")).isFalse();
        assertThat(valid(new UpdateAssetRequest("", "s", "c"), "name")).isFalse();
    }

    // ---------------- DeleteAssetVersionsRequest ----------------

    @Test
    @DisplayName("DeleteAssetVersionsRequest：null/空 拒绝 / 20 通过 / 21 拒绝")
    void deleteVersionsCountBoundary() {
        assertThat(valid(new DeleteAssetVersionsRequest(null), "versions")).isFalse();
        assertThat(valid(new DeleteAssetVersionsRequest(List.of()), "versions")).isFalse();
        assertThat(valid(new DeleteAssetVersionsRequest(versions(20)), "versions")).isTrue();
        assertThat(valid(new DeleteAssetVersionsRequest(versions(21)), "versions")).isFalse();
    }

    @Test
    @DisplayName("DeleteAssetVersionsRequest：元素不得为 null / 空白")
    void deleteVersionsElementBoundary() {
        assertThat(valid(new DeleteAssetVersionsRequest(java.util.Arrays.asList("1.0.0", null)), "versions[1].<list element>")).isFalse();
        assertThat(valid(new DeleteAssetVersionsRequest(List.of("1.0.0", "  ")), "versions[1].<list element>")).isFalse();
        assertThat(valid(new DeleteAssetVersionsRequest(List.of("v1", "1.0.10")), "versions[1].<list element>")).isTrue();
    }

    // ---------------- 标签写入口（Controller 内联 DTO） ----------------

    @Test
    @DisplayName("SetTagsRequest.tags：null 列表放行（=清空）/ 单个标签 20 通过 / 21 拒绝 / 空白拒绝")
    void setTagsBoundary() {
        assertThat(valid(new TagController.SetTagsRequest(null), "tags")).isTrue();
        assertThat(valid(new TagController.SetTagsRequest(List.of()), "tags")).isTrue();
        assertThat(valid(new TagController.SetTagsRequest(List.of("t".repeat(20))), "tags[0].<list element>")).isTrue();
        assertThat(valid(new TagController.SetTagsRequest(List.of("t".repeat(21))), "tags[0].<list element>")).isFalse();
        assertThat(valid(new TagController.SetTagsRequest(List.of("  ")), "tags[0].<list element>")).isFalse();
    }

    private static List<String> versions(int count) {
        return IntStream.range(0, count).mapToObj(i -> "1.0." + i).toList();
    }
}
