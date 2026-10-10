package com.devknow.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devknow.domain.entity.DocumentChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 插入一个知识块。
     *
     * <p><b>为什么必须手写 INSERT 而不能用 MyBatis-Plus 的 {@code insert}</b>：
     * {@code metadata} 列是 PostgreSQL 的 {@code jsonb} 类型，而实体里用 String 承载。
     * 自动生成的 INSERT 会把参数当作 {@code varchar} 绑定，PostgreSQL 直接拒绝：
     * <pre>
     * ERROR: column "metadata" is of type jsonb but expression is of type character varying
     * 建议：You will need to rewrite or cast the expression.
     * </pre>
     * 这类错误会让每一块都写不进去 —— 表现为"文档入库正常、chunk 却始终为 0"，
     * 而且错误被逐文件的 try/catch 吞掉，极难定位。
     *
     * <p>解决方式是显式写出 {@code CAST(#{c.metadata} AS jsonb)}。
     * {@code document} 表没有这个问题，正是因为它的 upsert 本来就是手写 SQL 并带了转换。
     *
     * <p>另外 {@code created_at} 传值而非用 DEFAULT：批量插入时由应用统一时间戳，
     * 便于排查"这批 chunk 是哪次同步产生的"。
     */
    @Insert("""
            INSERT INTO document_chunk (
                document_id, chunk_index, chunk_type, heading_path, heading_level,
                content, content_tokens, content_hash, metadata, created_at
            ) VALUES (
                #{c.documentId}, #{c.chunkIndex}, #{c.chunkType}, #{c.headingPath}, #{c.headingLevel},
                #{c.content}, #{c.contentTokens}, #{c.contentHash}, CAST(#{c.metadata} AS jsonb), #{c.createdAt}
            )
            """)
    int insertChunk(@Param("c") DocumentChunk chunk);
}
