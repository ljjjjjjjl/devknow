package com.devknow.agent.client;

import java.util.List;

/**
 * 一次模型调用返回的 assistant 回合：要么给出最终回答（content），
 * 要么请求调用工具（toolCalls），两者至少一个非空。
 */
public record AssistantTurn(String content, List<ToolCall> toolCalls) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
