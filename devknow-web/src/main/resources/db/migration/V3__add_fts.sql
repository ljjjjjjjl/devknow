-- ============================================================================
--  V3__add_fts.sql  ·  全文检索列 + GIN 索引（第 3 周）
-- ============================================================================
--  执行者：Flyway（应用启动时自动执行）
--  前置版本：V2__add_pgvector.sql
--
--  ⚠️ 迁移文件一旦执行过就不能再改（Flyway checksum），要改新建 V4__xxx.sql
--
--  本迁移只做两件事：
--    1. 加生成列 content_tsv（自动维护，不用触发器）
--    2. 建 GIN 索引支撑 @@ 匹配
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 生成列
-- ---------------------------------------------------------------------------
--  ⚠️ 必须用【两参数形式】to_tsvector('english', x)
--     写成一参数 to_tsvector(x) 会直接报错：
--         ERROR: generation expression is not immutable
--     原因：一参数形式依赖 default_text_search_config（会话级 GUC），是 STABLE 不是 IMMUTABLE；
--           生成列要求表达式 IMMUTABLE。显式传 regconfig 才是 IMMUTABLE 的。
--
--  ⚠️ 两列都必须 coalesce，缺一不可：
--     tsvector 的 || 运算遇到 NULL 会传播成 NULL —— 只要有一个操作数是 NULL，
--     整个 content_tsv 就是 NULL，这一行对 @@ 永久不可见。
--     特别是 content 为 NULL 时：即使 heading_path 完好，它的权重 A 也会被一起丢掉，
--     表现为"按标题搜不到这条"——这是静默的数据丢失，只有对比加 coalesce 前后才看得出来。
--
--  为什么把 heading_path 也放进来（且权重给 A）：
--     issue 的标题只存在于 heading_path（如 'issue #1573 > Add a failure analyzer'），
--     正文不一定重复标题。若只索引 content，"按标题找 issue"的查询会漏。
--     setweight 让 ts_rank 给标题命中更高分。
--
--  ⚠️ 性能：ADD COLUMN ... GENERATED ... STORED 会【重写整张表】
--     （V2 那三列可空无 DEFAULT 是纯元数据操作，不重写；这次不一样）
--     当前 8580 行 / 57MB，几秒钟，但全过程持有 ACCESS EXCLUSIVE 锁。
-- ---------------------------------------------------------------------------
ALTER TABLE document_chunk
    ADD COLUMN content_tsv tsvector
        GENERATED ALWAYS AS (
            setweight(to_tsvector('english', coalesce(heading_path, '')), 'A') ||
            setweight(to_tsvector('english', coalesce(content, '')), 'B')
            ) STORED;

COMMENT ON COLUMN document_chunk.content_tsv IS '全文检索向量（生成列，随 heading_path/content 自动维护）。heading_path 权重 A、content 权重 B，两列均经 coalesce —— tsvector 的 || 遇 NULL 会整体传播为 NULL，导致该行对 @@ 永久不可见。分词配置 english（语料为 Spring Boot 英文文档与 issue）';

-- ---------------------------------------------------------------------------
-- 2. GIN 索引
-- ---------------------------------------------------------------------------
--  GIN 支撑 content_tsv @@ query 的匹配；ts_rank 排序仍要回表重算，
--  这是 PostgreSQL 全文检索的固有行为（GIN 只加速匹配，不加速排序）。
--  当前 8580 行，Bitmap Index Scan + Sort 完全够用。
-- ---------------------------------------------------------------------------
CREATE INDEX idx_chunk_content_tsv
    ON document_chunk USING gin(content_tsv);

COMMENT ON INDEX idx_chunk_content_tsv IS '全文检索 GIN 索引，支撑 content_tsv @@ plainto_tsquery 的匹配';