package com.devknow.agent.client;

import com.devknow.agent.config.AgentChatProperties;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容的 chat completions 客户端，支持非流式与 SSE 流式两种调用。
 *
 * <p>手写而不是引 Spring AI：function calling 的请求/响应结构要自己掌控，
 * 这是 Agent 运行时最该讲清楚的一层。
 *
 * <p><b>为什么保留两个方法而不是让非流式走流式</b>：非流式路径是第 5 周验收过的
 * 行为，上行从 {@code stream:false} 改成 {@code stream:true} 再自己拼回完整响应，
 * 等于把一个已验证的路径换掉，收益只是少几十行代码。所以两条路各自独立，
 * 共享的只有请求体组装。
 */
@Slf4j
@Component
public class ChatClient {

    private static final String CHAT_PATH = "/chat/completions";

    /** SSE 的数据行前缀 */
    private static final String DATA_PREFIX = "data:";

    /** 流结束哨兵 */
    private static final String DONE_SENTINEL = "[DONE]";

    private final AgentChatProperties properties;
    private final HttpClient httpClient;

    public ChatClient(AgentChatProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 一次非流式对话。
     *
     * @param model       模型标识（agent_config 为空时调用方已回退默认值）
     * @param temperature 温度
     * @param messages    完整历史消息
     * @param tools       OpenAI tools 数组；空列表时模型只能直接回答，不能调工具
     * @param requireToolCall 为 true 时下发 {@code tool_choice: "required"}，<b>强制</b>本回合
     *                    必须调用工具。ReAct 循环用它保证第一步一定发生检索，见
     *                    {@code AgentRunner} 的说明
     */
    public AssistantTurn chat(String model, double temperature,
                              List<ChatMessage> messages, List<Map<String, Object>> tools,
                              boolean requireToolCall) {
        requireConfigured();

        Map<String, Object> body = buildBody(model, temperature, messages, tools, false, requireToolCall);
        log.debug("调用模型: model={}, messages={} 条, tools={} 个",
                model, messages.size(), tools == null ? 0 : tools.size());

        HttpResponse<String> response;
        try {
            response = httpClient.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new BizException(ErrorCode.AGENT_LLM_ERROR, "模型请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_LLM_ERROR, "模型请求被中断", e);
        }

        if (response.statusCode() >= 400) {
            log.error("模型返回 HTTP {}: body={}", response.statusCode(), abbreviate(response.body()));
            throw new BizException(ErrorCode.AGENT_LLM_ERROR,
                    "模型返回 HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        log.debug("模型响应: {}", response.body());
        return parse(response.body());
    }

    /**
     * 一次流式对话：边收边把增量文本交给 {@code onDelta}，返回时给出<b>完整</b>回合。
     *
     * <p>返回值与非流式同构，所以上层 ReAct 循环不需要区分两种模式 ——
     * 它拿到的都是"这一回合模型想干什么"。
     *
     * <p><b>tool_calls 在流里是分片到达的</b>：首个分片带 {@code id} 和
     * {@code function.name}，后续分片只往 {@code function.arguments} 上追加字符串。
     * 按 {@code index} 归并是唯一正确的做法 —— 一次响应可能有多个工具调用，
     * 它们的分片是交错的。
     *
     * @param requireToolCall 见 {@link #chat}，语义一致
     */
    public AssistantTurn streamChat(String model, double temperature,
                                    List<ChatMessage> messages, List<Map<String, Object>> tools,
                                    Consumer<String> onDelta, boolean requireToolCall) {
        requireConfigured();

        Map<String, Object> body = buildBody(model, temperature, messages, tools, true, requireToolCall);
        log.debug("流式调用模型: model={}, messages={} 条, tools={} 个",
                model, messages.size(), tools == null ? 0 : tools.size());

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(buildRequest(body), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new BizException(ErrorCode.AGENT_LLM_ERROR, "模型请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_LLM_ERROR, "模型请求被中断", e);
        }

        // 流式下 HTTP 状态码在响应头就到了，此时 body 还没读 ——
        // 必须把 body 读出来，否则错误信息只剩一个状态码
        if (response.statusCode() >= 400) {
            String errorBody = readQuietly(response.body());
            log.error("模型返回 HTTP {}: body={}", response.statusCode(), abbreviate(errorBody));
            throw new BizException(ErrorCode.AGENT_LLM_ERROR,
                    "模型返回 HTTP " + response.statusCode() + ": " + abbreviate(errorBody));
        }

        StringBuilder content = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolBuilders = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String payload = ssePayload(line);
                if (payload == null) {
                    continue;
                }
                if (DONE_SENTINEL.equals(payload)) {
                    break;
                }
                accumulate(payload, content, toolBuilders, onDelta);
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.AGENT_LLM_ERROR, "读取模型流失败: " + e.getMessage(), e);
        }

        AssistantTurn turn = new AssistantTurn(
                content.isEmpty() ? null : content.toString(), toToolCalls(toolBuilders));
        log.debug("流式回合结束: content={} 字, toolCalls={} 个",
                content.length(), turn.toolCalls().size());
        return turn;
    }

    // ------------------------------------------------------------------

    private Map<String, Object> buildBody(String model, double temperature,
                                          List<ChatMessage> messages, List<Map<String, Object>> tools,
                                          boolean stream, boolean requireToolCall) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(ChatMessage::toMap).toList());
        body.put("temperature", temperature);
        body.put("stream", stream);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            // 显式 auto：部分模型在缺省 tool_choice 时不会主动调用工具。
            // required：强制本回合必须调工具，用于 ReAct 的第一步 —— 详见 AgentRunner。
            // 实测 SiliconFlow 的 DeepSeek-V3 支持 required（会返回 content="" + tool_calls）。
            body.put("tool_choice", requireToolCall ? "required" : "auto");
        }
        return body;
    }

    private HttpRequest buildRequest(Map<String, Object> body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + CHAT_PATH))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body)))
                .build();
    }

    /** 取出一行的 SSE 载荷；不是 data 行（空行、注释、心跳）返回 null。 */
    private static String ssePayload(String line) {
        if (line == null || line.isEmpty() || !line.startsWith(DATA_PREFIX)) {
            return null;
        }
        String payload = line.substring(DATA_PREFIX.length()).trim();
        return payload.isEmpty() ? null : payload;
    }

    /** 累积一个流分片：文本回调出去，工具调用按 index 归并。 */
    private void accumulate(String payload, StringBuilder content,
                            Map<Integer, ToolCallBuilder> toolBuilders, Consumer<String> onDelta) {
        JsonNode chunk;
        try {
            chunk = JsonUtils.mapper().readTree(payload);
        } catch (IOException e) {
            // 单个分片解析失败不该拖垮整轮：已经吐出去的内容仍然有效
            log.warn("跳过无法解析的流分片: {}", abbreviate(payload));
            return;
        }
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode delta = choices.get(0).get("delta");
        if (delta == null || delta.isNull()) {
            return;
        }

        String piece = textOrNull(delta.get("content"));
        if (piece != null && !piece.isEmpty()) {
            content.append(piece);
            onDelta.accept(piece);
        }

        JsonNode calls = delta.get("tool_calls");
        if (calls == null || !calls.isArray()) {
            return;
        }
        for (JsonNode call : calls) {
            int index = call.path("index").asInt(0);
            ToolCallBuilder builder = toolBuilders.computeIfAbsent(index, k -> new ToolCallBuilder());
            String id = textOrNull(call.get("id"));
            if (id != null && !id.isEmpty()) {
                builder.id = id;
            }
            JsonNode function = call.get("function");
            if (function == null || function.isNull()) {
                continue;
            }
            String name = textOrNull(function.get("name"));
            if (name != null && !name.isEmpty()) {
                builder.name = name;
            }
            String arguments = textOrNull(function.get("arguments"));
            if (arguments != null) {
                builder.arguments.append(arguments);
            }
        }
    }

    private static List<ToolCall> toToolCalls(Map<Integer, ToolCallBuilder> builders) {
        if (builders.isEmpty()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>(builders.size());
        for (ToolCallBuilder builder : builders.values()) {
            // 只有参数分片、没有名字的，不构成一次调用（多半是被截断的流）
            if (builder.name == null) {
                log.warn("流里出现没有函数名的 tool_call，已丢弃");
                continue;
            }
            calls.add(new ToolCall(builder.id, builder.name, builder.arguments.toString()));
        }
        return calls;
    }

    /**
     * 解析响应。用 readTree 而不是强类型 DTO：tool_calls 结构多变，
     * JsonNode 遍历更直接，也更容易定位"哪一层缺字段"。
     */
    private AssistantTurn parse(String body) {
        JsonNode root;
        try {
            root = JsonUtils.mapper().readTree(body);
        } catch (IOException e) {
            throw new BizException(ErrorCode.AGENT_LLM_ERROR, "模型响应解析失败", e);
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new BizException(ErrorCode.AGENT_LLM_ERROR, "模型响应缺少 choices");
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            throw new BizException(ErrorCode.AGENT_LLM_ERROR, "模型响应缺少 message");
        }
        String content = textOrNull(message.get("content"));
        if (content != null && content.isBlank()) {
            content = null;
        }
        return new AssistantTurn(content, parseToolCalls(message.get("tool_calls")));
    }

    private List<ToolCall> parseToolCalls(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : node) {
            JsonNode fn = c.get("function");
            String id = c.path("id").asText(null);
            String name = fn == null ? null : fn.path("name").asText(null);
            String args = fn == null ? null : fn.path("arguments").asText(null);
            calls.add(new ToolCall(id, name, args));
        }
        return calls;
    }

    /**
     * 取节点的文本；节点缺失或值是 JSON {@code null} 时返回 null。
     *
     * <p>不能直接用 {@code node.asText(null)}：Jackson 的 {@code NullNode.asText()}
     * 返回的是字符串 {@code "null"}，会把 {@code "content": null} 这种分片
     * 拼进正文里。
     */
    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String readQuietly(InputStream in) {
        if (in == null) {
            return "";
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void requireConfigured() {
        if (!properties.hasApiKey()) {
            throw new BizException(ErrorCode.AGENT_LLM_ERROR,
                    "未配置 SILICONFLOW_API_KEY，无法调用模型");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    /** 流式 tool_call 的累积器：id / name 取首次出现的，arguments 一直追加。 */
    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
