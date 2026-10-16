package com.devknow.domain.dto;

import java.time.Instant;
import java.util.List;

/**
 * 会话消息的对外视图。
 *
 * <p>为什么不直接返回 {@code ConversationMessage} 实体：实体里的 {@code citations}
 * 是 {@code jsonb}，用 String 承载，直接序列化出去会变成<b>被转义的 JSON 字符串</b>
 * （{@code "citations": "[{\"documentId\":1}]"}）而不是数组 —— 前端得再 parse 一次。
 * 这里解析成 {@code List<Citation>}，前端拿到就是数组。
 *
 * @param seq       会话内序号
 * @param role      user | assistant
 * @param citations 该轮回答的引用来源；null = 无引用或该轮为 user
 * @param error     非空表示该轮失败（内容为失败原因），前端应渲染成失败气泡
 */
public record ConversationMessageView(
        Long id,
        Integer seq,
        String role,
        String content,
        List<Citation> citations,
        String error,
        Instant createdAt) {
}
