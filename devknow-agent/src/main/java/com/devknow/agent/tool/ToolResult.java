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

    /**
     * 执行失败：工具压根没跑出结论（参数非法、下游异常、工具不存在）。
     *
     * <p>模型应把它理解成"这次检索没有结论"，<b>而不是</b>"知识库里没有" ——
     * 两者的正确后续动作相反：前者可以重试或换个问法，后者必须拒答。
     */
    public static ToolResult error(String message) {
        return new ToolResult(message, List.of());
    }

    /**
     * 成功但无结果：工具正常跑完，结论就是"没有"。
     *
     * <p>两种情况归到这里 —— 检索 0 命中；或命中了但 top-1 精排分低于
     * {@code devknow.rerank.min-relevance-score}（命中了一堆噪声，等价于没命中）。
     *
     * <p>与 {@link #error} 的区别在<b>语义</b>而不在返回结构：本方法是权威的
     * "知识库中没有相关内容"，模型必须据此拒答；{@code error} 则只表示这次调用失败，
     * 不代表知识库没有。两者共用同一个 record，所以靠文本区分是有意为之 ——
     * 真正需要区分时（比如前端要分别统计）再加字段，现在不提前设计。
     */
    public static ToolResult empty(String message) {
        return new ToolResult(message, List.of());
    }
}
