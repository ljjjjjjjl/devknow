package com.devknow.common.api;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 返回体。
 *
 * <p>所有 Controller 一律返回 {@code ApiResponse<T>}，避免出现"有的接口返回裸对象、
 * 有的返回 {code,msg,data}"的混乱局面。
 */
@Data
public class ApiResponse<T> implements Serializable {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = ErrorCode.SUCCESS.getCode();
        r.message = ErrorCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = errorCode.getCode();
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return this.code == ErrorCode.SUCCESS.getCode();
    }
}
