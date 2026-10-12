package com.devknow.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devknow.domain.dto.ChunkSearchHit;
import com.devknow.domain.entity.DocumentChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    /**
     * 写入向量。<b>只对尚未向量化的行生效</b>。
     *
     * <p><b>为什么必须 CAST(... AS vector)</b>：和 {@link #insertChunk} 处理 jsonb 同一个原因 ——
     * MyBatis 把 String 参数按 {@code varchar} 绑定，而 {@code embedding} 列是 pgvector 的
     * {@code vector} 类型，PostgreSQL 会拒绝隐式转换：
     * <pre>
     * ERROR: column "embedding" is of type vector but expression is of type character varying
     * </pre>
     *
     * <p><b>为什么 {@code AND embedding IS NULL} 不能省</b>：它是这次写入的幂等条件。
     * 多实例并发、或同一批任务被重入时，不加这个条件会把已经写好的向量覆盖成
     * "同一条文本的另一次计算结果" —— 数值可能略有差异，但更重要的是它掩盖了
     * "两个执行流在跑同一个任务"这个真实问题。
     *
     * <p><b>{@code embedding} 入参格式</b>：必须是 pgvector 能直接解析的数组字面量，
     * 如 {@code [0.1,0.2,0.3]}（不是 JSON 对象，也不要带空格以外的分隔符）。
     * 维度必须与列定义 {@code vector(1024)} 一致，否则 PostgreSQL 报维度不符。
     *
     * @return 受影响行数。<b>0 表示这行已被别人写过、或 id 不存在</b> ——
     *         调用方应据此判断是否发生了并发覆盖，而不是当成错误
     */
    @Update("""
            UPDATE document_chunk
               SET embedding       = CAST(#{embedding} AS vector),
                   embedding_model = #{model},
                   embedded_at     = now()
             WHERE id = #{id}
               AND embedding IS NULL
            """)
    int updateEmbedding(@Param("id") Long id,
                        @Param("embedding") String embedding,
                        @Param("model") String model);

    /**
     * 取一批尚未向量化的 chunk（回填任务的工作队列）。
     *
     * <p><b>为什么用子查询而不是直接过滤 source_id</b>：{@code document_chunk} 表本身
     * 没有 {@code source_id} 列，必须经 {@code document} 关联。
     * PostgreSQL 通常会把这种 {@code IN (子查询)} 改写成半连接，不需要手工优化。
     *
     * <p><b>为什么不在这里 JOIN 出字段而是用 SELECT c.*</b>：与
     * {@code IngestionJobMapper.selectNextPendingForUpdate} 保持一致；
     * 且本查询限定 {@code embedding IS NULL}，取出来的都是空向量，没有传输开销。
     *
     * <p><b>为什么要预过滤空白内容</b>：{@code content} 为空或纯空白的 chunk
     * 会让 embedding API 整批返回 400 —— <b>一条脏数据毒死一整批</b>。
     * 在这里过滤掉的代价是一次正则，比"整批失败后二分定位"便宜得多。
     *
     * <p>注意正则用 {@code [[:space:]]} 而不是反斜杠 s：本 SQL 写在 Java 文本块里，
     * 而反斜杠 s 在 Java 文本块中是一个合法转义（表示一个空格，JLS 3.10.7），
     * 会被提前吃掉，最终下发的 SQL 只会删掉空格、留下换行 —— 过滤形同虚设。
     *
     * <p><b>ORDER BY c.id</b> 配合 V2 迁移里建的
     * {@code idx_chunk_unembedded (id) WHERE embedding IS NULL} 部分索引，
     * 可以让 PostgreSQL 按 id 顺序走索引并在满足 LIMIT 后停止，不需要额外排序。
     *
     * @param sourceId 知识源 id
     * @param limit    单轮最多取多少条（有界工作，避免一轮跑太久）
     */
    @Select("""
            SELECT c.* FROM document_chunk c
             WHERE c.embedding IS NULL
               AND c.document_id IN (SELECT id FROM document WHERE source_id = #{sourceId})
               AND c.content IS NOT NULL
               AND length(regexp_replace(c.content, '[[:space:]]', '', 'g')) > 0
             ORDER BY c.id
             LIMIT #{limit}
            """)
    List<DocumentChunk> selectUnembeddedBySource(@Param("sourceId") Long sourceId,
                                                 @Param("limit") int limit);
    /**
     * 向量检索：按余弦距离取 top-K。
     *
     * <p>{@code CAST(#{queryVector} AS vector)} 不能省 —— 和 {@code insertChunk} 处理 jsonb
     * 是同一个原因：MyBatis 把 String 参数按 varchar 绑定，PostgreSQL 拒绝隐式转 vector。
     *
     * <p>别名一律用 snake_case（{@code chunk_id} 而非 {@code chunkId}）：PostgreSQL 会把
     * 未加引号的标识符折成小写（{@code chunkId} → {@code chunkid}），而 record 组件名是
     * {@code chunkId}。用 snake_case 配合已开启的 {@code map-underscore-to-camel-case=true}
     * 是确定能对上的写法。
     *
     * <p>{@code ORDER BY ... LIMIT} 这个形态是 pgvector HNSW 索引能生效的前提
     * （索引与操作符必须匹配：{@code vector_cosine_ops} ←→ {@code <=>}）。
     */
    @Select("""
            SELECT c.id           AS chunk_id,
                   c.document_id  AS document_id,
                   c.heading_path AS heading_path,
                   c.content      AS content,
                   1 - (c.embedding <=> CAST(#{queryVector} AS vector)) AS similarity
            FROM document_chunk c
            WHERE c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(#{queryVector} AS vector)
            LIMIT #{limit}
            """)
    List<ChunkSearchHit> searchByVector(@Param("queryVector") String queryVector,
                                        @Param("limit") int limit);

    /**
     * 词法检索：按 ts_rank 取 top-K。
     *
     * <p>{@code ts_rank} <b>不是 BM25</b> —— 它是 PostgreSQL 内置的词频类排序函数，
     * 和 BM25 的 idf/k1/b 无关。作用上等价于"精确词匹配"这一路，但不是同一个算法。
     *
     * <p><b>⚠️ 查询侧的点号归一化必须与 V4 迁移里生成列的表达式逐字一致。</b>
     * PostgreSQL 会把整条带点字符串（{@code spring.data.redis.host}）当成【一个 host token】，
     * 索引侧在 V4 里把它拆成了四段，查询侧不拆就永远对不上 ——
     * 表现为<b>静默的 0 命中</b>，不报错、不告警。
     * 两处表达式一旦漂移，词法路会整体失效且很难察觉。
     *
     * <p>{@code plainto_tsquery} 用 AND 连接词素：不加引号的多个词是"全部都要出现"。
     */
    @Select("""
            SELECT c.id           AS chunk_id,
                   c.document_id  AS document_id,
                   c.heading_path AS heading_path,
                   c.content      AS content,
                   ts_rank(c.content_tsv, query) AS similarity
            FROM document_chunk c,
                 plainto_tsquery('english', regexp_replace(#{query}, '\\.', '. ', 'g')) query
            WHERE c.content_tsv @@ query
            ORDER BY similarity DESC
            LIMIT #{limit}
            """)
    List<ChunkSearchHit> searchByKeyword(@Param("query") String query,
                                         @Param("limit") int limit);
}
