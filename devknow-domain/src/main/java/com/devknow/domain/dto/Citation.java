package com.devknow.domain.dto;

/**
 * 回答引用来源：一条被检索并用于生成回答的文档。
 *
 * @param documentId 文档 id，可回跳知识库定位原文
 * @param title      文档标题
 * @param url        原文链接（issue / html 页面）
 */
public record Citation(long documentId, String title, String url) {}
