package com.devknow.domain.dto;

/**
 * 非流式对话的完整返回：会话 id + Agent 回答。
 *
 * <p>为什么不把 {@code conversationId} 直接塞进 {@link AgentChatResponse}：
 * 那个 record 是 {@code AgentRunner} 的返回值，而 Runner 是纯 ReAct 引擎，
 * 不应该知道"持久化"这件事。加字段会让引擎依赖会话概念，还会连带改
 * {@code AgentRunnerTest} 的全部用例。
 *
 * <p>包一层是刻意的：<b>会话编排属于 Controller 层</b>，Runner 只管算。
 */
public record AgentChatResult(Long conversationId, AgentChatResponse response) {
}
