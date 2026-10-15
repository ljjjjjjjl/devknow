package com.devknow.retrieval.eval;

import java.util.List;

/**
 * 单条 query 的评估结果。
 *
 * <p>{@code foundDocumentIds} 存的是<b>按 documentId 去重并保序</b>的文档列表，
 * 不是原始 chunk 列表 —— 一个文档可能被多个 chunk 命中，只有去重后
 * "第几个文档命中"才有意义。
 *
 * @param hitRank 第几个<b>文档</b>命中正确文档（从 1 开始）；{@code null} 表示返回结果里全未命中
 * @param error   该条执行失败时的异常摘要；正常时为 {@code null}
 */
public record EvalItemResult(
        String id,
        String set,
        String query,
        List<Long> expectedDocumentIds,
        List<Long> foundDocumentIds,
        Integer hitRank,
        long elapsedMs,
        String error
) {

    public boolean hit() {
        return hitRank != null;
    }
}
