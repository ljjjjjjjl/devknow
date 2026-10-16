package com.devknow.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devknow.domain.entity.ConversationMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {

    /**
     * 追加一条消息。
     *
     * <p><b>为什么必须手写 INSERT 而不能用 MyBatis-Plus 的 {@code insert}</b>：
     * 和 {@code DocumentChunkMapper.insertChunk} 处理 {@code metadata} 完全同一个原因 ——
     * {@code citations} 是 {@code jsonb}，自动生成的 INSERT 会把参数当 {@code varchar}
     * 绑定，PostgreSQL 直接拒绝。
     *
     * <p>{@code created_at} 传值而非用 DEFAULT：便于排查"这条消息是哪次请求写的"。
     */
    @Insert("""
            INSERT INTO conversation_message (
                conversation_id, seq, role, content, citations, error, created_at
            ) VALUES (
                #{m.conversationId}, #{m.seq}, #{m.role}, #{m.content},
                CAST(#{m.citations} AS jsonb), #{m.error}, #{m.createdAt}
            )
            """)
    int insertMessage(@Param("m") ConversationMessage message);

    /**
     * 取下一个序号。
     *
     * <p>用 {@code MAX(seq)+1} 而不是序列：seq 是"会话内"序号，每个会话都要从 1 开始。
     *
     * <p><b>单端串行才安全</b>。当前没有多端同时写同一会话的场景，所以不引入锁；
     * 真要做多端同步时，这里换成 {@code INSERT ... SELECT COALESCE(MAX(seq),0)+1}
     * 或者对 conversation 行加 {@code FOR UPDATE}。
     */
    @Select("""
            SELECT COALESCE(MAX(seq), 0) + 1
            FROM conversation_message
            WHERE conversation_id = #{conversationId}
            """)
    int nextSeq(@Param("conversationId") Long conversationId);

    /**
     * 拉取可用于上下文的历史消息：<b>跳过失败占位</b>。
     *
     * <p>失败那条的 {@code role} 也是 assistant，如果不过滤，模型会把自己上一轮
     * 的"（回答失败）"当成正常对话历史回放，污染上下文。
     */
    @Select("""
            SELECT * FROM conversation_message
            WHERE conversation_id = #{conversationId}
              AND error IS NULL
            ORDER BY seq
            """)
    List<ConversationMessage> listForContext(@Param("conversationId") Long conversationId);

    /** 完整消息列表（含失败占位），给前端回看用。 */
    @Select("""
            SELECT * FROM conversation_message
            WHERE conversation_id = #{conversationId}
            ORDER BY seq
            """)
    List<ConversationMessage> listAll(@Param("conversationId") Long conversationId);
}
