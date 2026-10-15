package com.devknow.domain.dto;

/**
 * Agent 对话请求。
 *
 * @param agentConfigId 智能体配置 id，对应 agent_config 表
 * @param query         用户问题
 */
public record AgentChatRequest(Long agentConfigId, String query) {}
