package com.devknow.domain.dto;

/**
 * Agent 对话请求。
 *
 * @param agentConfigId  智能体配置 id，对应 agent_config 表
 * @param conversationId 会话 id。<b>null 表示新建会话</b>，服务端生成后通过
 *                       流式的 {@code start} 事件、或非流式响应体里的
 *                       {@code conversationId} 返回，后续轮次带上它即可续聊
 * @param query          用户问题
 */
public record AgentChatRequest(Long agentConfigId, Long conversationId, String query) {}
