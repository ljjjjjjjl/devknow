package com.devknow.agent.client;

import com.devknow.agent.config.AgentChatProperties;
import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 chat completions 客户端（第 5 周最小闭环：非流式）。
 *
 * <p>手写而不是引 Spring AI：function calling 的请求/响应结构要自己掌控，
 * 这是 Agent 运行时最该讲清楚的一层。SSE 流式在后续迭代加。
 */
@Slf4j
@Component
public class ChatClient {

    private static final String CHAT_PATH = "/chat/completions";

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
     */
    public AssistantTurn chat(String model, double temperature,
                              List<ChatMessage> messages, List<Map<String, Object>> tools) {
        requireConfigured();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(ChatMessage::toMap).toList());
        body.put("temperature", temperature);
        body.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            // 显式 auto：部分模型在缺省 tool_choice 时不会主动调用工具
            body.put("tool_choice", "auto");
        }
        log.debug("调用模型: model={}, messages={} 条, tools={} 个",
                model, messages.size(), tools == null ? 0 : tools.size());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + CHAT_PATH))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
        String content = message.path("content").asText(null);
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
}
