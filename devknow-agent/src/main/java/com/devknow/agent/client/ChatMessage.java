package com.devknow.agent.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话消息，{@code role} 覆盖 system / user / assistant / tool 四种。
 *
 * <p>function calling 的两条硬约束：
 * <ul>
 *   <li>assistant 带 tool_calls 时 content 为 null</li>
 *   <li>tool 消息必须带 tool_call_id，与上一条 assistant 的 tool_calls 一一对应</li>
 * </ul>
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", null, toolCalls, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }

    /** 转成 OpenAI 兼容的 messages 元素；null 字段一律不写，避免序列化成显式 null。 */
    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        if (content != null) {
            m.put("content", content);
        }
        if (toolCalls != null) {
            m.put("tool_calls", toolCalls.stream().map(ToolCall::toMap).toList());
        }
        if (toolCallId != null) {
            m.put("tool_call_id", toolCallId);
        }
        return m;
    }
}
