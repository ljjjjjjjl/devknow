package com.devknow.retrieval.eval;

import java.util.List;

/**
 * 评估集里的一条 query。
 *
 * <p>字段与 {@code devknow-retrieval/src/main/resources/eval/week4-queries.jsonl} 逐字对应。
 *
 * @param id                  唯一标识，用于在逐条明细里定位失败项
 * @param set                 子集：{@code known-item} / {@code chinese} / {@code symbol}
 * @param query               查询文本
 * @param expectedDocumentIds 正确文档，<b>document 级而非 chunk 级</b>
 * @param note                这条想考什么
 */
public record EvalQuery(
        String id,
        String set,
        String query,
        List<Long> expectedDocumentIds,
        String note
) {
}
