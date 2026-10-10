package com.devknow.common.exception;

import com.devknow.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。全局异常处理器会把它翻译成对应的 {@code ApiResponse}。
 *
 * <p>与 {@code RuntimeException} 的区别：业务异常代表"可预期的失败"
 * （如知识源不存在），日志按 WARN 记录；非业务异常按 ERROR 记录并打堆栈。
 */
@Getter
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

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
