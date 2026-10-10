package com.devknow.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devknow.domain.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * 幂等写入：冲突时更新内容与元数据，不新增行。
     *
     * <p>这是"重跑采集不产生脏数据"的实现基础。冲突目标为唯一键
     * {@code (source_id, doc_type, external_id, version)}。
     *
     * <p>{@code updated_at} 显式写 now()：本项目不使用数据库触发器，
     * 避免"字段悄悄变化"这种难以排查的行为。
     *
     * @return 受影响行数（PostgreSQL 下 INSERT 与 UPDATE 都记为 1）
     */
    @Update("""
            INSERT INTO document (
                source_id, doc_type, external_id, url, title,
                version, version_order, path, raw_content,
                content_hash, lang, published_at, status, metadata
            ) VALUES (
                #{d.sourceId}, #{d.docType}, #{d.externalId}, #{d.url}, #{d.title},
                #{d.version}, #{d.versionOrder}, #{d.path}, #{d.rawContent},
                #{d.contentHash}, #{d.lang}, #{d.publishedAt}, #{d.status}, CAST(#{d.metadata} AS jsonb)
            )
            ON CONFLICT (source_id, doc_type, external_id, version)
            DO UPDATE SET
                url           = EXCLUDED.url,
                title         = EXCLUDED.title,
                version_order = EXCLUDED.version_order,
                path          = EXCLUDED.path,
                raw_content   = EXCLUDED.raw_content,
                content_hash  = EXCLUDED.content_hash,
                lang          = EXCLUDED.lang,
                published_at  = EXCLUDED.published_at,
                status        = EXCLUDED.status,
                metadata      = EXCLUDED.metadata,
                updated_at    = now()
            """)
    int upsert(@Param("d") Document document);

    /**
     * 按自然键取 id。
     *
     * <p>upsert 之后需要 documentId 才能写 chunk。
     *
     * <p><b>不要加 {@code version IS NULL} 条件</b>：文档的 version 存的是来源分支
     * （如 {@code main}），并非 NULL。曾经因为多写了这个条件，导致取不到 id、
     * 切分被静默跳过 —— 表现为"216 个文档入库、chunk 却为 0"，且日志被刷屏难以发现。
     *
     * <p>同一 {@code (source_id, doc_type, external_id)} 只会有一条记录：
     * 文档版本由 {@code knowledge_source.branch} 决定，每次同步只处理一个分支。
     */
    @Select("""
            SELECT id FROM document
             WHERE source_id = #{sourceId}
               AND doc_type = #{docType}
               AND external_id = #{externalId}
             LIMIT 1
            """)
    Long selectIdByNaturalKey(@Param("sourceId") Long sourceId,
                              @Param("docType") String docType,
                              @Param("externalId") String externalId);

    /**
     * 判断内容是否已存在且未变化，用于增量跳过。
     *
     * @return 1 表示已存在同内容记录，可跳过后续切分与向量化
     */
    @Select("""
            SELECT COUNT(1) FROM document
             WHERE source_id = #{sourceId}
               AND doc_type = #{docType}
               AND external_id = #{externalId}
               AND content_hash = #{contentHash}
            """)
    int existsUnchanged(@Param("sourceId") Long sourceId,
                        @Param("docType") String docType,
                        @Param("externalId") String externalId,
                        @Param("contentHash") String contentHash);
}
