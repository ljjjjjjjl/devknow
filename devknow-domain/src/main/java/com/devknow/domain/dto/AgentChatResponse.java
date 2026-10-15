package com.devknow.domain.dto;

import java.util.List;

/**
 * Agent 对话响应（第 5 周最小闭环为非流式；SSE 流式在后续迭代）。
 *
 * @param answer    最终回答
 * @param citations 本次回答引用的来源
 * @param steps     实际执行的 ReAct 步数（诊断用）
 * @param model     实际使用的模型
 */
public record AgentChatResponse(String answer, List<Citation> citations, int steps, String model) {}
