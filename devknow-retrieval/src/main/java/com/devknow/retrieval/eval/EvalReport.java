package com.devknow.retrieval.eval;

import java.util.List;

/**
 * 一次评估跑批的完整结果。
 *
 * <p>{@code bySet} 必须单独看，不能只看 {@code overall}：
 * {@code known-item} 子集的 query 词直接来自目标文档，词法路几乎必中、Hit@1 接近 1.0，
 * 混进总分会把 {@code chinese} / {@code symbol} 的真实表现掩盖掉。
 *
 * @param items 逐条明细。总分只能看趋势，明细才能说明"为什么这条没命中"
 */
public record EvalReport(
        EvalSummary overall,
        List<EvalSummary> bySet,
        List<EvalItemResult> items
) {
}
