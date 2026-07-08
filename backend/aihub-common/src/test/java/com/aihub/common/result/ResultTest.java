package com.aihub.common.result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应体与错误码分段契约（docs/API.md §3）。
 */
class ResultTest {

    @Test
    void okShouldCarrySuccessCodeAndData() {
        Result<String> result = Result.ok("payload");

        assertThat(result.code()).isZero();
        assertThat(result.message()).isEqualTo("ok");
        assertThat(result.data()).isEqualTo("payload");
    }

    @Test
    void okWithoutDataShouldHaveNullData() {
        Result<Void> result = Result.ok();

        assertThat(result.code()).isZero();
        assertThat(result.data()).isNull();
    }

    @Test
    void failShouldCarryErrorCode() {
        Result<Void> result = Result.fail(ErrorCode.NOT_FOUND);

        assertThat(result.code()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        assertThat(result.message()).isEqualTo(ErrorCode.NOT_FOUND.getMessage());
        assertThat(result.data()).isNull();
    }

    @Test
    void failShouldAllowCustomMessage() {
        Result<Void> result = Result.fail(ErrorCode.PARAM_INVALID, "page must be >= 1");

        assertThat(result.code()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(result.message()).isEqualTo("page must be >= 1");
    }

    @Test
    void errorCodesShouldStayInsideTheirAssignedSegments() {
        assertThat(ErrorCode.SUCCESS.getCode()).isZero();
        // 1xxxx 用户域
        assertThat(ErrorCode.UNAUTHORIZED.getCode()).isBetween(10000, 19999);
        assertThat(ErrorCode.FORBIDDEN.getCode()).isBetween(10000, 19999);
        // 9xxxx AI 域 / 系统级
        assertThat(ErrorCode.SYSTEM_ERROR.getCode()).isBetween(90000, 99999);
        assertThat(ErrorCode.PARAM_INVALID.getCode()).isBetween(90000, 99999);
        assertThat(ErrorCode.NOT_FOUND.getCode()).isBetween(90000, 99999);
    }

    @Test
    void errorCodesShouldBeUnique() {
        long distinct = java.util.Arrays.stream(ErrorCode.values())
                .map(ErrorCode::getCode)
                .distinct()
                .count();

        assertThat(distinct).isEqualTo(ErrorCode.values().length);
    }
}
