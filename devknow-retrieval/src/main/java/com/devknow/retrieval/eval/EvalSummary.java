package com.devknow.retrieval.eval;

/**
 * 某一组 query 的汇总指标。口径全部是 <b>document 级</b>，且<b>截止位置固定</b>。
 *
 * <p><b>为什么所有指标都必须固定在 1 / 5 / 20 这三个位置</b>：MRR 如果对任意名次都计分，
 * 就会随检索窗口变化 —— 同一个答案在窗口 5 时算未命中、在窗口 20 时算 0.125 分，
 * 两次跑批的数字不可比。返回窗口固定为 20 后，这一点才不会踩。
 *
 * <p>{@code recallAt20 - recallAt5} 就是"答案已经在候选池里、只是排得靠后"的条数 ——
 * 也就是 rerank 有可能救回来的上限。它单独记成 {@code inPoolNotTop5}，
 * 免得每次都要心算，也避免把"候选池里没有答案"的召回问题误判成排序问题。
 *
 * @param set           {@code overall} 或子集名
 * @param total         条数
 * @param hitAt1       第 1 个结果就是正确文档的比例
 * @param recallAt5    前 5 个结果里出现正确文档的比例
 * @param recallAt20   前 20 个结果里出现正确文档的比例（候选池覆盖率）
 * @param inPoolNotTop5 正确文档在池里但排在第 6~20 位的条数 —— <b>rerank 的收益上限</b>
 * @param mrrAt5       前 5 位内命中排名的倒数均值（第 6 位及以后记 0）
 * @param avgElapsedMs 单条平均耗时
 */
public record EvalSummary(
        String set,
        int total,
        double hitAt1,
        double recallAt5,
        double recallAt20,
        int inPoolNotTop5,
        double mrrAt5,
        double avgElapsedMs
) {
}
