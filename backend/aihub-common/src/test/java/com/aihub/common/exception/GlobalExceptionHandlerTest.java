package com.aihub.common.exception;

import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理器的「协议错误」分支。
 *
 * <p>POST/PUT 少了 {@code Content-Type: application/json} 时，Tomcat 按 x-www-form-urlencoded
 * 处理会报 415；这类异常若落到兜底分支会被包装成 90000 "system error"，调用方看不出真实原因。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("Content-Type 不支持 → PARAM_INVALID 并带上真实提示（不再吞成 90000）")
    void shouldReportUnsupportedMediaType() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                "Content-Type 'application/x-www-form-urlencoded;charset=UTF-8' is not supported");

        Result<Void> result = handler.handleClientProtocolException(ex);

        assertThat(result.code()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(result.message()).contains("x-www-form-urlencoded");
    }

    @Test
    @DisplayName("请求方法不支持（如 publish 用 POST 而非 PUT）→ PARAM_INVALID 并说明方法错误")
    void shouldReportMethodNotSupported() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException(
                HttpMethod.POST.name(), List.of(HttpMethod.PUT.name()));

        Result<Void> result = handler.handleClientProtocolException(ex);

        assertThat(result.code()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(result.message()).contains("POST");
    }
}
