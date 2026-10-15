package com.devknow.web.controller;

import com.devknow.agent.config.AgentConfigService;
import com.devknow.agent.engine.AgentRunner;
import com.devknow.agent.stream.AgentStreamListener;
import com.devknow.common.api.ApiResponse;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.JsonUtils;
import com.devknow.domain.dto.AgentChatRequest;
import com.devknow.domain.dto.AgentChatResponse;
import com.devknow.domain.dto.Citation;
import com.devknow.domain.entity.AgentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Agent 对话接口：非流式 + SSE 流式两个入口。
 *
 * <p><b>两个入口，一个循环</b>：都调 {@code AgentRunner}，差别只在传输方式。
 * 非流式留着不删 —— 前端要"一次性拿完整结果"（如服务端渲染、批处理）时，
 * 用 SSE 再自己拼起来是多余的一层。
 *
 * <p>事件协议（{@code event:} / {@code data:} 都是 JSON）：
 * <ul>
 *   <li>{@code step} — {@code {"step":1}}，一步 = 一次模型调用</li>
 *   <li>{@code delta} — {@code {"text":"..."}}，回答的增量片段，可多次</li>
 *   <li>{@code tool} — {@code {"name":"...","arguments":"..."}}，即将执行的工具</li>
 *   <li>{@code citation} — {@code {"documentId":..,"title":"..","url":".."}}，已按 id 去重</li>
 *   <li>{@code done} — 完整答案 + 步数 + 模型 + 耗时，<b>流里只有一个终态事件</b></li>
 *   <li>{@code error} — {@code {"code":..,"message":".."}}，与 done 互斥</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    /**
     * SSE 连接存活上限。一次多步 ReAct + 长回答可能跑几十秒，
     * 给 5 分钟；到点由容器断开，异常在 {@code onTimeout} 里记日志。
     */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final AgentConfigService configService;
    private final AgentRunner runner;
    private final ExecutorService streamExecutor;

    public AgentController(AgentConfigService configService, AgentRunner runner,
                           ExecutorService agentStreamExecutor) {
        this.configService = configService;
        this.runner = runner;
        this.streamExecutor = agentStreamExecutor;
    }

    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@RequestBody AgentChatRequest request) {
        AgentConfig config = configService.getRequired(request.agentConfigId());
        AgentChatResponse response = runner.run(config, request.query());
        log.info("Agent 对话完成: configId={}, steps={}, 引用 {} 条",
                config.getId(), response.steps(),
                response.citations() == null ? 0 : response.citations().size());
        return ApiResponse.ok(response);
    }

    /**
     * 流式对话。
     *
     * <p>返回 {@code SseEmitter} 后立刻交还容器线程，真正的执行丢给虚拟线程 ——
     * 一个连接全程占一个线程，这正是虚拟线程的用法。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody AgentChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onTimeout(() -> log.warn("SSE 超时未完成: agentConfigId={}", request.agentConfigId()));
        emitter.onError(e -> log.warn("SSE 连接异常: agentConfigId={}, {}", request.agentConfigId(), e.toString()));
        streamExecutor.execute(() -> runStream(request, emitter));
        return emitter;
    }

    private void runStream(AgentChatRequest request, SseEmitter emitter) {
        long startNanos = System.nanoTime();
        Long configId = request.agentConfigId();

        AgentStreamListener listener = new AgentStreamListener() {
            @Override
            public void onStep(int step) {
                send(emitter, "step", Map.of("step", step));
            }

            @Override
            public void onDelta(String text) {
                send(emitter, "delta", Map.of("text", text));
            }

            @Override
            public void onTool(String name, String arguments) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", name);
                payload.put("arguments", arguments == null ? "" : arguments);
                send(emitter, "tool", payload);
            }

            @Override
            public void onCitation(Citation citation) {
                send(emitter, "citation", citation);
            }
        };

        try {
            AgentConfig config = configService.getRequired(configId);
            AgentChatResponse response = runner.stream(config, request.query(), listener);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answer", response.answer());
            payload.put("steps", response.steps());
            payload.put("model", response.model());
            payload.put("elapsedMs", elapsedMs);
            payload.put("citations", response.citations());
            send(emitter, "done", payload);
            emitter.complete();

            log.info("流式对话完成: configId={}, steps={}, 引用 {} 条, {} ms",
                    configId, response.steps(), response.citations().size(), elapsedMs);
        } catch (ClientGoneException e) {
            // 客户端断开后继续调模型是纯浪费：这里只收尾，不当作错误上报
            log.info("客户端已断开，停止流式对话: configId={}", configId);
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.warn("流式对话失败: configId={}", configId, e);
            try {
                send(emitter, "error", errorPayload(e));
                emitter.complete();
            } catch (Exception ignored) {
                // 连错误事件都发不出去（客户端已走），只能按异常结束
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * 写一个 SSE 事件。
     *
     * <p>显式指定 {@code TEXT_PLAIN} 让 String 走 {@code StringHttpMessageConverter}，
     * JSON 文本原样落到 {@code data:} 行。不指定就得靠转换器顺序碰运气 ——
     * 万一被 Jackson 再序列化一次，前端收到的是带引号的字符串而不是对象。
     */
    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(JsonUtils.toJson(payload), MediaType.TEXT_PLAIN));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGoneException(e);
        }
    }

    private static Map<String, Object> errorPayload(Exception e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        int code = e instanceof BizException biz
                ? biz.getErrorCode().getCode()
                : ErrorCode.INTERNAL_ERROR.getCode();
        payload.put("code", code);
        payload.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return payload;
    }

    /** SSE 写失败（客户端断开 / emitter 已结束）。用来中断 ReAct 循环，不是业务错误。 */
    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }
}
