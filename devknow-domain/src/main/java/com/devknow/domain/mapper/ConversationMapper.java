package com.devknow.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devknow.domain.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 会话列表，按最近活跃倒序。
     *
     * <p><b>{@code status = 'ACTIVE'} 不能省</b>：{@code idx_conversation_agent_updated}
     * 是带这个条件的部分索引，漏掉它就退化成全表扫描 + 排序。
     *
     * <p>{@code agentConfigId} 可空表示不过滤。<b>必须写成 {@code CAST(#{...} AS BIGINT)}
     * 而不是裸参数</b>：参数为 null 时 PostgreSQL 无法推断占位符类型，
     * 会直接报 "could not determine data type of parameter"。
     */
    @Select("""
            SELECT * FROM conversation
            WHERE status = 'ACTIVE'
              AND (CAST(#{agentConfigId} AS BIGINT) IS NULL
                   OR agent_config_id = CAST(#{agentConfigId} AS BIGINT))
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<Conversation> listActive(@Param("agentConfigId") Long agentConfigId,
                                  @Param("limit") int limit);

    /**
     * 刷新"最近活跃时间"。
     *
     * <p>{@code updated_at} 没有触发器，不显式更新的话会话列表永远按创建时间排。
     */
    @Update("UPDATE conversation SET updated_at = now() WHERE id = #{id}")
    int touch(@Param("id") Long id);

    /**
     * 软删。带 {@code AND status = 'ACTIVE'} 保证重复删除幂等 ——
     * 第二次调用影响 0 行，调用方据此判断"本来就已删除或不存在"。
     */
    @Update("""
            UPDATE conversation SET status = 'DELETED', updated_at = now()
            WHERE id = #{id} AND status = 'ACTIVE'
            """)
    int softDelete(@Param("id") Long id);

    /**
     * 写入滚动摘要并推进水位线（V7）。
     *
     * <p><b>为什么带 {@code AND summary_upto_seq = #{expectedUpto}}：</b>这是 CAS。
     * 同一个会话若有两个请求同时触发压缩，只有水位仍然等于自己读到的那个值时才写得进去，
     * 另一个影响 0 行、调用方据此丢弃本次结果。不加这个条件就会互相覆盖 ——
     * 后写的那次是基于旧摘要算的，会把前一次刚合并进去的内容抹掉。
     *
     * @return 影响行数；0 表示水位已被别的请求推进，本次结果应丢弃
     */
    @Update("""
            UPDATE conversation
            SET summary = #{summary}, summary_upto_seq = #{uptoSeq}
            WHERE id = #{id} AND summary_upto_seq = #{expectedUpto}
            """)
    int updateSummary(@Param("id") Long id,
                      @Param("summary") String summary,
                      @Param("uptoSeq") int uptoSeq,
                      @Param("expectedUpto") int expectedUpto);
}
