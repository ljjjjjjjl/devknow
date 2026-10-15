package com.devknow.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 工具。注册进 Spring 容器的实现会被 {@code AgentRunner} 自动收集。
 *
 * <p>{@code parameters()} 返回给模型看的 JSON Schema，{@code description()} 是
 * "什么时候用这个工具"的提示词 —— 两者共同决定模型会不会正确调用。
 */
public interface AgentTool {

    /** 工具名，对应 agent_config.tool_names 里的字符串 */
    String name();

    /** 给模型看的描述：什么时候用、怎么用 */
    String description();

    /** 给模型看的参数 JSON Schema（{@code type: object}） */
    JsonNode parameters();

    /**
     * 执行工具。
     *
     * <p><b>不要在正常路径抛异常</b>：执行失败应作为"给模型看的结果"返回，
     * 让模型自己决定换参数还是放弃。意外抛出的异常由 Runner 统一兜底。
     */
    ToolResult execute(JsonNode arguments);
}
