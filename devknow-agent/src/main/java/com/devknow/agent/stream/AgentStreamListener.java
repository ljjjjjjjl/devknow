package com.devknow.agent.stream;

import com.devknow.domain.dto.Citation;

/**
 * Agent 执行过程的回调。Runner 只负责把"过程中发生的事"推出来，
 * 具体怎么传输（SSE / WebSocket / 只写日志）由调用方决定。
 *
 * <p><b>刻意没有"结束"和"失败"回调</b>：这两件事调用方本来就知道 ——
 * 正常结束就是 {@code run}/{@code stream} 返回，失败就是它抛异常。
 * 在接口里再加 {@code onDone}/{@code onError}，会出现"Runner 抛了异常
 * 又回调了一次 onError"的双重通知，调用方反而要写两套收尾逻辑。
 *
 * <p>所有方法都有默认空实现，调用方只覆写关心的那几个。
 */
public interface AgentStreamListener {

    /** 什么都不做的实现，用于非流式路径 */
    AgentStreamListener NOOP = new AgentStreamListener() {
    };

    /**
     * 开始第 {@code step} 步（从 1 起）。
     *
     * <p>一步 = 一次模型调用。模型决定调工具就继续下一步，直接给回答就结束，
     * 所以"步数"同时是前端的进度指示和 {@code max_steps} 熔断的计数。
     */
    default void onStep(int step) {
    }

    /**
     * 模型输出的增量文本片段。
     *
     * <p>注意是<b>片段</b>不是句子：一整段回答会被切成很多次回调，拼起来才完整。
     * 只有最终回答的文本会走这里；模型决定调工具时不产生文本。
     */
    default void onDelta(String text) {
    }

    /**
     * 即将执行工具。前端据此显示"正在检索知识库…"。
     *
     * @param arguments 模型给的参数 JSON 字符串（可能为 null）
     */
    default void onTool(String name, String arguments) {
    }

    /**
     * 工具检索到的一条引用来源，已按 documentId 去重。
     *
     * <p>与最终响应里的 {@code citations} 顺序一致：同一个 documentId 只在
     * 首次出现时回调一次。
     */
    default void onCitation(Citation citation) {
    }
}
