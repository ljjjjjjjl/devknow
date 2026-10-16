package com.devknow.web.controller;

import com.devknow.agent.client.ChatMessage;
import com.devknow.agent.config.AgentConfigService;
import com.devknow.agent.conversation.ConversationService;
import com.devknow.agent.engine.AgentRunner;
import com.devknow.agent.stream.AgentStreamListener;
import com.devknow.common.api.ApiResponse;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.JsonUtils;
import com.devknow.domain.dto.AgentChatRequest;
import com.devknow.domain.dto.AgentChatResult;
import com.devknow.domain.dto.AgentChatResponse;
import com.devknow.domain.dto.Citation;
import com.devknow.domain.dto.ConversationMessageView;
import com.devknow.domain.entity.AgentConfig;
import com.devknow.domain.entity.Conversation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Agent 对话接口：非流式 + SSE 流式两个入口，外加会话管理。
 *
 * <p><b>两个入口，一个循环</b>：都调 {@code AgentRunner}，差别只在传输方式。
 * 非流式留着不删 —— 前端要"一次性拿完整结果"（如服务端渲染、批处理）时，
 * 用 SSE 再自己拼起来是多余的一层。
 *
 * <p><b>会话编排在这一层，不在 Runner</b>：Runner 是纯 ReAct 引擎，不认识"会话"。
 * 读写历史、落库回答、失败占位都在 Controller 里做，见 {@link #runStream}。
 *
 * <p><b>⚠️ 调用顺序：loadContext 必须在 appendUser 之前。</b>
 * 反过来的话 loadContext 会把本轮问题当成历史返回，而 Runner 内部还会再追加一次
 * 本轮 query，同一条问题会在 messages 里出现两次。
 *
 * <p>事件协议（{@code event:} / {@code data:} 都是 JSON）：
 * <ul>
 *   <li>{@code start} — {@code {"conversationId":..}}，<b>最先发出</b>，新建会话时前端靠它拿到 id</li>
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
    private final ConversationService conversationService;
    private final ExecutorService streamExecutor;

    public AgentController(AgentConfigService configService, AgentRunner runner,
                           ConversationService conversationService,
                           ExecutorService agentStreamExecutor) {
        this.configService = configService;
        this.runner = runner;
        this.conversationService = conversationService;
        this.streamExecutor = agentStreamExecutor;
    }

    @PostMapping("/chat")
    public ApiResponse<AgentChatResult> chat(@RequestBody AgentChatRequest request) {
        AgentConfig config = configService.getRequired(request.agentConfigId());
        Long conversationId = conversationService.resolve(
                config.getId(), request.conversationId(), request.query());

        // 顺序不能反，见类注释
        List<ChatMessage> history = conversationService.loadContext(conversationId);
        conversationService.appendUser(conversationId, request.query());

        try {
            AgentChatResponse response = runner.run(config, history, request.query());
            conversationService.appendAssistant(
                    conversationId, response.answer(), response.citations());
            log.info("Agent 对话完成: configId={}, conversationId={}, steps={}, 引用 {} 条",
                    config.getId(), conversationId, response.steps(),
                    response.citations() == null ? 0 : response.citations().size());
            return ApiResponse.ok(new AgentChatResult(conversationId, response));
        } catch (RuntimeException e) {
            recordFailure(conversationId, e.getMessage());
            throw e;
        }
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
        Long conversationId = null;

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
            conversationId = conversationService.resolve(
                    config.getId(), request.conversationId(), request.query());

            // start 尽早发：新建会话时前端要立刻拿到 id 才能续聊。
            // 它必须排在 loadContext/appendUser 之前 —— 那两步失败时前端至少知道自己该丢弃哪个会话。
            send(emitter, "start", Map.of("conversationId", conversationId));

            // 顺序不能反，见类注释
            List<ChatMessage> history = conversationService.loadContext(conversationId);
            conversationService.appendUser(conversationId, request.query());

            AgentChatResponse response = runner.stream(config, history, request.query(), listener);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            conversationService.appendAssistant(
                    conversationId, response.answer(), response.citations());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answer", response.answer());
            payload.put("steps", response.steps());
            payload.put("model", response.model());
            payload.put("elapsedMs", elapsedMs);
            payload.put("citations", response.citations());
            send(emitter, "done", payload);
            emitter.complete();

            log.info("流式对话完成: configId={}, conversationId={}, steps={}, 引用 {} 条, {} ms",
                    configId, conversationId, response.steps(), response.citations().size(), elapsedMs);
        } catch (ClientGoneException e) {
            // 客户端断开后继续调模型是纯浪费：这里只收尾，不当作错误上报。
            // 仍要记一条失败占位 —— 否则回看时这一轮是"有问无答"。
            log.info("客户端已断开，停止流式对话: configId={}, conversationId={}", configId, conversationId);
            recordFailure(conversationId, "客户端断开");
            // 用 complete() 而不是 completeWithError()：连接已经断了，没有任何人能收到这个错误，
            // 再声明一次错误只是多说一句废话。真正的重复报错在 GlobalExceptionHandler 里兜
            // （写失败已被 Spring 记成 async error，会触发一次错误分发）。
            emitter.complete();
        } catch (Exception e) {
            log.warn("流式对话失败: configId={}, conversationId={}", configId, conversationId, e);
            recordFailure(conversationId, e.getMessage());
            try {
                send(emitter, "error", errorPayload(e));
                emitter.complete();
            } catch (Exception ignored) {
                // 连错误事件都发不出去（客户端已走），只能按异常结束
                emitter.completeWithError(e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 会话管理
    // ------------------------------------------------------------------

    /** 会话列表，按最近活跃倒序。{@code agentConfigId} 可空表示不过滤。 */
    @GetMapping("/conversations")
    public ApiResponse<List<Conversation>> conversations(
            @RequestParam(required = false) Long agentConfigId) {
        return ApiResponse.ok(conversationService.list(agentConfigId));
    }

    /** 消息列表，含失败占位，供刷新后恢复。 */
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<ConversationMessageView>> messages(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.messages(id));
    }

    /** 软删除会话。重复删除返回 404（{@code CONVERSATION_NOT_FOUND}）。 */
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(@PathVariable Long id) {
        conversationService.delete(id);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------------

    /**
     * 记一条失败占位，供回看时显示"这一轮失败了"。
     *
     * <p>这里吞掉异常是<b>刻意</b>的：它总是被调在 catch 块里，
     * 落库再失败不能盖掉原始异常 —— 否则"模型调用失败"会被报成"数据库写失败"，排查方向直接跑偏。
     */
    private void recordFailure(Long conversationId, String reason) {
        if (conversationId == null) {
            return;
        }
        try {
            conversationService.appendFailure(conversationId, reason);
        } catch (Exception e) {
            log.warn("写入失败占位消息出错: conversationId={}", conversationId, e);
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
