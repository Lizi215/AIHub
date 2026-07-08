package com.aihub.common.exception;

import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器（Servlet / MVC 栈）。
 *
 * <p>只负责把异常转换为统一响应体，不吞异常：非预期异常必须记录完整堆栈，否则线上无法定位。
 * 注意返回的 HTTP 状态码统一为 200，业务结果由响应体 {@code code} 表达，这是 docs/API.md §3 的契约。
 */
import org.springframework.web.HttpRequestMethodNotSupportedException;

import org.springframework.web.HttpMediaTypeNotSupportedException;

import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException ex) {
        log.warn("business exception, code={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return Result.fail(ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("parameter validation failed, detail={}", detail);
        return Result.fail(ErrorCode.PARAM_INVALID, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.warn("request body unreadable, message={}", ex.getMessage());
        return Result.fail(ErrorCode.PARAM_INVALID);
    }

    /**
     * 协议层面的客户端错误（方法不对、Content-Type 不对、路径不存在）。
     *
     * <p>这些异常若落到 {@link #handleUnexpectedException} 会被统一成 90000 "system error"，
     * 调用方看不出真实原因（如探针漏设 {@code Content-Type: application/json} 时服务端报 415，
     * 调用方却只看到 system error）。这里如实回报，且不算服务端故障。
     */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            NoResourceFoundException.class
    })
    public Result<Void> handleClientProtocolException(Exception ex) {
        log.warn("client protocol error: {}", ex.getMessage());
        return Result.fail(ErrorCode.PARAM_INVALID, ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpectedException(Exception ex) {
        // 兜底分支必须打全堆栈，否则无法复现
        log.error("unexpected exception", ex);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
