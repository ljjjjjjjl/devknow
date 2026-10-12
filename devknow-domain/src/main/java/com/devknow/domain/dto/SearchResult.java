package com.devknow.domain.dto;

/**
 * 检索结果（接口返回类型）。
 *
 * <p><b>为什么是 rrfScore + 两个 rank，而不是一个 similarity</b>：
 * 三者的量纲完全不同 —— 向量路的 {@code 1-cosine} 是 0~1，
 * 词法路的 {@code ts_rank} 是另一套，融合后的 RRF 分数是 {@code 1/(60+rank)}，
 * 只有 0.0125~0.0328。用同一个字段承载三者，会让读的人以为可以跨 query 比较。
 *
 * <p>{@code vectorRank}/{@code keywordRank} 是<b>该 chunk 在单路里的排名（从 1 开始）</b>，
 * {@code null} 表示这一路没命中它。这两个值比分数有用得多：
 * "这条向量第 3、词法第 1，所以融合排第一" 是可直接讲出来的结论。
 *
 * @param rrfScore    RRF 融合分数，<b>只在同一次 query 内可比</b>
 * @param vectorRank  向量路排名，null = 该路未命中
 * @param keywordRank 词法路排名，null = 该路未命中
 */
public record SearchResult(
        Long chunkId,
        Long documentId,
        String title,
        String headingPath,
        String content,
        double rrfScore,
        Integer vectorRank,
        Integer keywordRank,
        String documentUrl
) {}