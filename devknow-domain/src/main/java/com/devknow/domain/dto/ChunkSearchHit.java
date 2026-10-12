package com.devknow.domain.dto;

/**
 * 单路检索的原始命中（Mapper 返回类型）。
 *
 * <p>不含 title / url —— 那些在 {@code document} 表，由 RetrievalService 批量补齐，
 * 避免在检索 SQL 里 JOIN（JOIN 会让 LIMIT 的语义变复杂）。
 *
 * <p>{@code similarity} 是<b>本路自己的分数</b>，两路的量纲不同：
 * <ul>
 *   <li>向量路：{@code 1 - 余弦距离}，范围 0~1</li>
 *   <li>词法路：{@code ts_rank}，无固定上界</li>
 * </ul>
 * <b>不要跨路比较这个值</b>，它只在"同一路内排序"和"排查用"时有意义。
 * 融合后的分数看 SearchResult.rrfScore。
 */
public record ChunkSearchHit(
        Long chunkId,
        Long documentId,
        String headingPath,
        String content,
        double similarity
) {}