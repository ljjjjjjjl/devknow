package com.devknow.web.config;

import com.devknow.common.api.ApiResponse;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>三条规则：
 * <ol>
 *   <li><b>业务异常</b>按 WARN 记录，不打堆栈 —— 它是可预期的失败，打堆栈只会淹没日志</li>
 *   <li><b>未知异常</b>按 ERROR 记录并打堆栈，同时对外隐藏细节，避免泄漏内部结构</li>
 *   <li><b>参数校验失败</b>把字段级错误原样返回，前端才能定位到具体输入框</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ApiResponse.fail(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数非法: {}", e.getMessage());
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    /**
     * 客户端断开导致写响应失败。
     *
     * <p><b>为什么要单独兜这一条</b>：SSE 流里客户端一刷新页面或关标签，写响应就会抛
     * {@code IOException}（Windows 上是"你的主机中的软件中止了一个已建立的连接"）。
     * Spring 把它记成 async error 并做一次错误分发，落到 {@link #handleUnknown} ——
     * 而那里要写 {@code ApiResponse}，响应的 Content-Type 还停在 {@code text/event-stream}，
     * 于是再抛 {@code HttpMessageNotWritableException}。一次断开在日志里留下三条 ERROR 堆栈，
     * 演示页每刷新一次就刷一屏。
     *
     * <p>这既不是业务异常也不是 bug：对端已经走了，没有任何人能收到响应体。
     * 所以只记一行 WARN，并返回<b>空体</b>（void 返回值不经过消息转换器，
     * 绕开那个注定失败的 Content-Type 协商）。
     *
     * <p>取舍：本应用里能冒出 MVC 层的 IO 异常几乎只有客户端断开这一种
     * —— 采集与文件的 IO 都在各自的 Service 里包成了业务异常，
     * AgentController 的 SSE 写失败也已包成 ClientGoneException。
     * 真出现别的 IO 失败时，这里仍会记下 message，只是没有堆栈。
     */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.OK)
    public void handleClientAbort(IOException e) {
        log.warn("写响应失败（通常是客户端已断开）: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknown(Exception e) {
        log.error("未预期的异常", e);
        return ApiResponse.fail(ErrorCode.INTERNAL_ERROR);
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
