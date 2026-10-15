package com.devknow.agent.tool;

import com.devknow.domain.dto.Citation;

import java.util.List;

/**
 * 工具执行结果。
 *
 * <p><b>把"给模型看的"和"给前端看的"分开</b>：{@code textForLlm} 受 token 预算约束
 * 要截断，而 {@code citations} 是结构化引用要原样带回前端。
 * 混在一个字符串里，前端就得从文本里抠 URL，那是本末倒置。
 */
public record ToolResult(String textForLlm, List<Citation> citations) {

    public static ToolResult error(String message) {
        return new ToolResult(message, List.of());
    }
}
