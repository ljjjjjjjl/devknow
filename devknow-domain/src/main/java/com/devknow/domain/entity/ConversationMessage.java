package com.devknow.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * 会话消息，只存 user 与 assistant 两种角色。
 *
 * <p>ReAct 的 tool 往返刻意不落库 —— 理由见
 * {@code V6__add_conversation.sql} 顶部的差异说明。
 *
 * <p>{@code citations} 是 PostgreSQL {@code jsonb}，实体里用 String 承载，
 * 和 {@link DocumentChunk#getMetadata()} 同一套路。写入必须手写
 * {@code CAST(... AS jsonb)} 的 INSERT，否则 MyBatis 按 varchar 绑定会被数据库拒绝。
 *
 * <p>{@code error} 非空表示该轮失败（如客户端断开）。这类消息在
 * {@code loadContext} 里会被跳过，不能当作正常历史回放。
 */
@Data
@TableName("conversation_message")
public class ConversationMessage implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** 会话内序号，从 1 开始；(conversation_id, seq) 唯一 */
    private Integer seq;

    /** {@code user | assistant}，见 {@code ConversationEnums.MessageRole} */
    private String role;

    private String content;

    /** JSON 数组文本；写入时 CAST 成 jsonb */
    private String citations;

    /** 非空 = 该轮失败，内容为失败原因的简短描述 */
    private String error;

    private Instant createdAt;
}
