package com.aihub.user.dto;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation 长度边界矩阵（每个 {@code @Size} 的 n-1 / n / n+1）。
 *
 * <p>约束是参数校验的第一道闸，边界取值错了要么放垃圾进库，要么挡住合法输入。
 * 不启动 Spring 上下文，直接用 jakarta validation 默认实现。
 */
class UserDtoValidationBoundaryTest {

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

    /** 返回指定属性路径上的校验消息集合 */
    private static Set<String> violations(Object bean, String property) {
        return validator.validate(bean).stream()
                .filter(v -> v.getPropertyPath().toString().equals(property))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static boolean valid(Object bean, String property) {
        return violations(bean, property).isEmpty();
    }

    // ---------------- RegisterRequest ----------------

    @Test
    @DisplayName("username：2 拒绝 / 3 通过 / 50 通过 / 51 拒绝")
    void registerUsernameBoundary() {
        assertThat(valid(reg("ab", "E2ePass12"), "username")).isFalse();
        assertThat(valid(reg("abc", "E2ePass12"), "username")).isTrue();
        assertThat(valid(reg("u".repeat(50), "E2ePass12"), "username")).isTrue();
        assertThat(valid(reg("u".repeat(51), "E2ePass12"), "username")).isFalse();
        // 纯空白等价于缺失
        assertThat(valid(reg("   ", "E2ePass12"), "username")).isFalse();
    }

    @Test
    @DisplayName("password：7 拒绝 / 8 通过 / 72 通过 / 73 拒绝（BCrypt 72 字节上限）")
    void registerPasswordBoundary() {
        assertThat(valid(reg("e2e-user", "E2e1234"), "password")).isFalse();
        assertThat(valid(reg("e2e-user", "E2e12345"), "password")).isTrue();
        assertThat(valid(reg("e2e-user", "x".repeat(72)), "password")).isTrue();
        assertThat(valid(reg("e2e-user", "x".repeat(73)), "password")).isFalse();
    }

    @Test
    @DisplayName("nickname：null 通过 / 50 通过 / 51 拒绝")
    void registerNicknameBoundary() {
        assertThat(valid(new RegisterRequest("e2e-user", "E2ePass12", null, null), "nickname")).isTrue();
        assertThat(valid(new RegisterRequest("e2e-user", "E2ePass12", "n".repeat(50), null), "nickname")).isTrue();
        assertThat(valid(new RegisterRequest("e2e-user", "E2ePass12", "n".repeat(51), null), "nickname")).isFalse();
    }

    @Test
    @DisplayName("email：null 通过 / 合法通过 / 100 通过 / 101 拒绝 / 非法格式拒绝")
    void registerEmailBoundary() {
        assertThat(valid(new RegisterRequest("e2e-user", "E2ePass12", null, null), "email")).isTrue();
        assertThat(valid(new RegisterRequest("e2e-user", "E2ePass12", null, "a@x.com"), "email")).isTrue();
        // @Email 还校验本地部分 ≤64：靠拉长域名（标签 ≤63）把总长凑到 100
        String email100 = "a".repeat(35) + "@" + "b".repeat(60) + ".com";  // 35 + 1 + 60 + 4 = 100
        String email101 = "a".repeat(36) + "@" + "b".repeat(60) + ".com";  // 101
        assertThat(email100).hasSize(100);
        assertThat(email101).hasSize(101);
        assertThat(valid(new RegisterRequest("e2e-user", "E2ePass12", null, email100), "email")).isTrue();
        assertThat(valid(new RegisterRequest("e2e-user", "E2ePass12", null, email101), "email")).isFalse();
        assertThat(valid(new RegisterRequest("e2e-user", "E2ePass12", null, "not-an-email"), "email")).isFalse();
    }

    // ---------------- LoginRequest ----------------

    @Test
    @DisplayName("LoginRequest：username ≤50、password ≤72")
    void loginBoundary() {
        assertThat(valid(new LoginRequest("u".repeat(50), "p"), "username")).isTrue();
        assertThat(valid(new LoginRequest("u".repeat(51), "p"), "username")).isFalse();
        assertThat(valid(new LoginRequest("u", "p".repeat(72)), "password")).isTrue();
        assertThat(valid(new LoginRequest("u", "p".repeat(73)), "password")).isFalse();
        assertThat(valid(new LoginRequest("", "p"), "username")).isFalse();
    }

    // ---------------- UpdateRoleRequest ----------------

    @Test
    @DisplayName("role：大小写不敏感接受三种合法值，其余拒绝")
    void updateRoleBoundary() {
        for (String ok : List.of("USER", "user", "Maintainer", "ADMIN", "admin")) {
            assertThat(valid(new UpdateRoleRequest(ok), "role")).as("role=%s", ok).isTrue();
        }
        for (String bad : List.of("ROOT", "SUPERADMIN", "", "  ")) {
            assertThat(valid(new UpdateRoleRequest(bad), "role")).as("role=%s", bad).isFalse();
        }
    }

    // ---------------- CreateWorkspaceRequest ----------------

    @Test
    @DisplayName("workspace：name ≤100、description ≤500")
    void workspaceTextBoundary() {
        assertThat(valid(ws("w".repeat(100), "d", items(1)), "name")).isTrue();
        assertThat(valid(ws("w".repeat(101), "d", items(1)), "name")).isFalse();
        assertThat(valid(ws("ws", "d".repeat(500), items(1)), "description")).isTrue();
        assertThat(valid(ws("ws", "d".repeat(501), items(1)), "description")).isFalse();
    }

    @Test
    @DisplayName("workspace.items：空拒绝 / 1 通过 / 20 通过 / 21 拒绝")
    void workspaceItemsCountBoundary() {
        assertThat(valid(ws("ws", "d", List.of()), "items")).isFalse();
        assertThat(valid(ws("ws", "d", items(1)), "items")).isTrue();
        assertThat(valid(ws("ws", "d", items(20)), "items")).isTrue();
        assertThat(valid(ws("ws", "d", items(21)), "items")).isFalse();
    }

    @Test
    @DisplayName("workspace.item：assetId 必填且为正；version ≤50")
    void workspaceItemBoundary() {
        assertThat(valid(ws("ws", "d", List.of(new CreateWorkspaceRequest.Item(null, "v1"))), "items[0].assetId")).isFalse();
        assertThat(valid(ws("ws", "d", List.of(new CreateWorkspaceRequest.Item(0L, "v1"))), "items[0].assetId")).isFalse();
        assertThat(valid(ws("ws", "d", List.of(new CreateWorkspaceRequest.Item(-1L, "v1"))), "items[0].assetId")).isFalse();
        assertThat(valid(ws("ws", "d", List.of(new CreateWorkspaceRequest.Item(1L, "v1"))), "items[0].assetId")).isTrue();
        assertThat(valid(ws("ws", "d", List.of(new CreateWorkspaceRequest.Item(1L, "v".repeat(50)))), "items[0].version")).isTrue();
        assertThat(valid(ws("ws", "d", List.of(new CreateWorkspaceRequest.Item(1L, "v".repeat(51)))), "items[0].version")).isFalse();
    }

    private static RegisterRequest reg(String username, String password) {
        return new RegisterRequest(username, password, null, null);
    }

    private static CreateWorkspaceRequest ws(String name, String description, List<CreateWorkspaceRequest.Item> items) {
        return new CreateWorkspaceRequest(name, description, items);
    }

    private static List<CreateWorkspaceRequest.Item> items(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new CreateWorkspaceRequest.Item((long) i + 1, "1.0.0"))
                .toList();
    }
}
