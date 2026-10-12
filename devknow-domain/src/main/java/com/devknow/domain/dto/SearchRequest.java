package com.devknow.domain.dto;

/**
 * 检索入参。
 *
 * <p>刻意不依赖 {@code BizException}/{@code ErrorCode}：本类在 domain 模块，
 * 参数校验放在 retrieval 层的 RetrievalService，避免为一个 trim 引入模块依赖。
 */
public record SearchRequest(String query, Integer topK) {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 50;

    /**
     * topK 归一化：默认 5，上限 50。
     *
     * <p>上限不是"怕性能"（HNSW 取 50 和取 5 是同一量级），
     * 而是防止一次把整个候选集拉回来，让接口的返回体大小不可控。
     */
    public int topKOrDefault() {
        return topK == null || topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
    }
}