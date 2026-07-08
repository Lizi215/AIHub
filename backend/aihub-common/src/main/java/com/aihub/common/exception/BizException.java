package com.aihub.common.exception;

import com.aihub.common.result.ErrorCode;

/**
 * 业务异常。业务代码抛出本异常表达可预期的失败，由全局异常处理器统一转换为 {@code Result}。
 *
 * <p>禁止用本异常包装不可预期的系统故障，那类异常应直接抛出并由全局处理器兜底为 {@link ErrorCode#SYSTEM_ERROR}。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
