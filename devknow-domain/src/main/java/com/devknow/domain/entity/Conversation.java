package com.devknow.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * 会话：一次多轮对话的容器。
 *
 * <p>没有 {@code user_id}：当前没有多用户体系，加了只能填常量。
 * 将来引入用户体系时再加列，不影响现在的读写路径。
 *
 * <p>{@code updatedAt} <b>没有触发器</b>，不会自己变。会话列表按它倒序，
 * 所以追加消息时必须显式 {@code touch()}，否则排序会退化成"按创建时间"。
 */
@Data
@TableName("conversation")
public class Conversation implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentConfigId;

    /** 列表展示用，取首条 user query 截断 */
    private String title;

    /** {@code ACTIVE | DELETED}，见 {@code ConversationEnums.ConversationStatus} */
    private String status;

    private Instant createdAt;

    private Instant updatedAt;

    /**
     * 更早对话的滚动摘要（V7）。null 表示还没压过。
     *
     * <p>只覆盖"被上下文窗口丢掉的那一段"，与原文窗口永不重叠 ——
     * 重叠会让模型对同一句话看到两遍，还可能自相矛盾。
     */
    private String summary;

    /**
     * 摘要已覆盖到的消息 seq（水位线），0 = 从未压缩。
     *
     * <p>它的唯一职责是<b>防止重复压缩</b>：每次只取 {@code seq > summaryUptoSeq}
     * 的那一段，已经在摘要里的消息不会再被送进去。
     */
    private Integer summaryUptoSeq;
}
