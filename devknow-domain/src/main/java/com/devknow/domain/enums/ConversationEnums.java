package com.devknow.domain.enums;

import lombok.Getter;

/**
 * 会话与消息的取值域。
 *
 * <p>和 {@link JobEnums} 一样集中放一个文件：这两组取值只服务于
 * {@code conversation} / {@code conversation_message} 两张表，
 * 拆成独立文件只会让"改一个状态要翻三个地方"。
 *
 * <p><b>取值必须与 {@code V6__add_conversation.sql} 里的 CHECK 约束一致</b>，
 * 由 {@code SchemaEnumConsistencyTest} 守住。
 */
public final class ConversationEnums {

    private ConversationEnums() {
    }

    /**
     * 会话状态。软删而非物理删除，所以 {@code DELETED} 的行仍在表里。
     *
     * <p>注意：{@code conversation_message} 没有自己的状态列 —— 消息随会话一起
     * 被逻辑删除，查询消息前必须先确认会话是 {@code ACTIVE}。
     */
    @Getter
    public enum ConversationStatus {
        ACTIVE("ACTIVE"),
        DELETED("DELETED");

        private final String value;

        ConversationStatus(String value) {
            this.value = value;
        }
    }

    /**
     * 消息角色。
     *
     * <p><b>只有 user 与 assistant 两种</b>：ReAct 的 tool 往返刻意不落库
     * （理由见 {@code V6__add_conversation.sql} 的差异说明）。
     * 这不是遗漏，是有意的范围裁剪 —— 加上 tool 角色就必须同时决定
     * "要不要把它回放进上下文"，而那个问题的答案是不放。
     */
    @Getter
    public enum MessageRole {
        USER("user"),
        ASSISTANT("assistant");

        private final String value;

        MessageRole(String value) {
            this.value = value;
        }
    }
}
