-- ============================================================================
--  V2__add_pgvector.sql  ·  为 document_chunk 增加向量列（第 2 周）
-- ============================================================================
--  执行者：Flyway（应用启动时自动执行），不要手动改这个文件
--  前置版本：V1__init.sql（已在新库 devknow_utf8 执行，flyway_schema_history 有 1 条记录）
--
--  ⚠️ 迁移文件一旦提交过，就不要再改它！任何调整都新建 V3__xxx.sql
--     原因：Flyway 用 checksum 校验已执行过的迁移，改动会让启动直接失败。
--
--  本迁移只做三件事：
--    1. 确认 pgvector 扩展存在（已由超级用户预装，这里是断言而不是安装）
--    2. 给 document_chunk 加 3 个可空列
--    3. 建一个部分索引，把"待向量化"的 chunk 变成 O(待办数) 可查
--
--  本迁移**刻意不做**的事：
--    · 不建 HNSW 索引 —— 理由见文件末尾
--    · 不把 embedding 设为 NOT NULL —— NULL 就是这个系统的"待办标记"
--    · 不回填任何数据 —— 回填由 EmbeddingBackfillService 负责
--
--  ⚠️ 书写约定：本文件里所有 COMMENT 一律用**单个字符串字面量**，
--     不要拆成跨行的多个相邻字面量。PostgreSQL 虽然支持相邻字面量隐式拼接
--     （前提是分隔空白含换行），但那对"重排"零容忍 —— 任何格式化工具折掉换行
--     就会变成 syntax error，且 Flyway 的解析器未必按 PG 的词法处理。
--     单字符串没有这个风险，也没有任何代价。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 0. pgvector 扩展：确认存在
-- ---------------------------------------------------------------------------
--  ⚠️ 这里用的是 IF NOT EXISTS，而它**不能省略**。
--
--  为什么不能直接写 CREATE EXTENSION vector：
--    pgvector 的控制文件声明了 superuser = true，而 **Flyway 用的连接用户是 devknow，
--    不是超级用户**。以 devknow 执行真正的 CREATE EXTENSION 会直接失败：
--        ERROR:  permission denied to create extension "vector"
--        HINT:  Must be superuser to create this extension.
--
--  而 IF NOT EXISTS 的**存在性检查发生在权限检查之前**，所以当扩展已装好时，
--    以 devknow 执行只是一次 no-op（返回 NOTICE: extension "vector" already exists, skipping）。
--
--  ⚠️ 由此带来的部署要求（必须写进 README）：
--    在全新环境建库后，必须先用超级用户执行一次：
--        psql -h 127.0.0.1 -U postgres -d <dbname> -c "CREATE EXTENSION vector;"
--    否则本迁移会在这一行失败 —— 因为 IF NOT EXISTS 把"装扩展"这件事
--    藏到了 Flyway 的管理范围之外，Flyway 自己装不了。
--
--  验证扩展版本：
--    SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
--    期望：vector | 0.8.2
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;


-- ---------------------------------------------------------------------------
-- 1. document_chunk 增加 3 个可空列
-- ---------------------------------------------------------------------------
--  性能说明：三列都是**可空且无 DEFAULT**，因此在 PostgreSQL 11+ 上是
--  纯元数据操作（不重写表、不扫描数据），1823 行和 1 亿行都是瞬间完成。
--  如果这里给某个列加了 DEFAULT，就会触发一次全表重写 —— 不要加。
-- ---------------------------------------------------------------------------
ALTER TABLE document_chunk
    ADD COLUMN embedding       vector(1024),
    ADD COLUMN embedding_model VARCHAR(64),
    ADD COLUMN embedded_at     TIMESTAMPTZ;

COMMENT ON COLUMN document_chunk.embedding IS '文本块的向量表示（bge-m3，1024 维）。NULL = 尚未向量化，这个状态本身即回填任务的工作队列；查询模式统一为 WHERE embedding IS NULL，待办、进度、断点位置都由这一条条件表达';

COMMENT ON COLUMN document_chunk.embedding_model IS '产出该向量的模型标识，如 BAAI/bge-m3。必须写，它是三件事的唯一依据：(1) 换模型后全量重嵌的工作队列（WHERE embedding_model <> ''新模型''）；(2) 按 content_hash 复用旧向量时的判断条件（模型不同则向量空间不同，绝不可复用）；(3) 排查"检索为什么不准"时确认有没有混入不同模型的向量';

COMMENT ON COLUMN document_chunk.embedded_at IS '向量写入时间（成功时才写）。用于观察回填推进节奏、排查"某批数据是不是漏嵌了"。失败时不写此列、也不写 embedding_model —— 保持 embedding IS NULL 让该 chunk 自动回到待办队列';


-- ---------------------------------------------------------------------------
-- 2. 部分索引：把"找未嵌入的 chunk"从全表扫描变成 O(待办数)
-- ---------------------------------------------------------------------------
--  索引列为什么是 (id)：
--    回填的扫描 SQL 是
--        SELECT ... FROM document_chunk
--         WHERE embedding IS NULL AND document_id IN (...)
--         ORDER BY id LIMIT ?
--    以 id 为索引列，可以让 PostgreSQL 直接按 id 顺序走索引并在满足 LIMIT 后停止，
--    不需要额外的 Sort 节点。
--    （用 (document_id, id) 反而会让 ORDER BY id 需要排序，因为索引先按 document_id 有序。）
--
--  为什么用部分索引：
--    只索引 embedding IS NULL 的行。回填推进时索引**自行缩小**，
--    全部嵌完后索引为空 —— 索引体积与 chunk 总量无关，只与"还剩多少待办"有关。
--    这与 idx_job_pick（只索引 PENDING 任务）是同一个设计模式。
--
--  ⚠️ 实际收益的诚实评估：
--    当前 1823 行，全表扫描本来就是亚毫秒级，这个索引的收益可以忽略。
--    它的价值在两个方面：
--      (1) 统计接口的 countUnembeddedChunks()（WHERE embedding IS NULL）
--          可以走 index-only scan；全部嵌完后几乎是 O(1)，而全表扫描是 O(总行数)
--      (2) chunk 涨到百万级时，"取一批待办"从 O(N log N) 降到 O(待办数)
--    因此现在建它是廉价保险。但**不要因为 EXPLAIN 显示 Seq Scan 就以为它坏了** ——
--    见验证 §6。
-- ---------------------------------------------------------------------------
CREATE INDEX idx_chunk_unembedded
    ON document_chunk (id)
    WHERE embedding IS NULL;

COMMENT ON INDEX idx_chunk_unembedded IS '部分索引：只覆盖尚未向量化的 chunk。支撑回填扫描与 countUnembeddedChunks()；随回填推进自动缩小，全部完成后为空';


-- ============================================================================
--  本迁移不建 HNSW 索引 —— 理由与后续操作
-- ============================================================================
--  为什么分开做：
--
--    1. 回填期间索引维护是**无价值的开销**。HNSW 索引的更新发生在每一行 UPDATE 上，
--       而回填期间没有任何查询会用到它。等数据稳定后一次性构建，同样的工作在
--       批量路径下可以用并行维护 worker 完成，且不与堆元组写入/WAL 挤在一起。
--
--    2. 避免索引膨胀。HNSW 对 UPDATE 的处理是"插入新节点 + 标记旧节点删除"，
--       而不是原地修改。如果第 2 周之后实现了"按 content_hash 复用向量"，
--       重嵌会频繁 UPDATE embedding 列，先建索引会把这个膨胀提前引入。
--
--  注意：两者都是 O(n log n) 量级的构建工作，量级相同（10000 个 1024 维向量
--       大约都在秒到分钟级）。所以这不是"先建慢、后建快"的问题，
--       而是"晚一点付、且付得更划算"的问题。不要把它当成一个需要论证的设计点。
--
--  ────────────────────────────────────────────────────────────────────────
--  回填完成后（countUnembeddedChunks() = 0 且验证集全部通过），单独执行：
--
--    SET maintenance_work_mem = '256MB';            -- 索引约 42MB，256MB 保证不落盘
--    SET max_parallel_maintenance_workers = 4;      -- 默认 2
--
--    CREATE INDEX idx_chunk_embedding_hnsw
--        ON document_chunk USING hnsw (embedding vector_cosine_ops);
--
--    RESET maintenance_work_mem;
--    RESET max_parallel_maintenance_workers;
--
--  ⚠️ 操作符类必须与查询用的操作符一致，否则索引不会被使用：
--        vector_cosine_ops  ←→  <=>   （余弦距离，bge-m3 官方推荐）
--        vector_l2_ops      ←→  <->   （欧氏距离）
--        vector_ip_ops      ←→  <#>   （内积）
--     本项目统一用余弦距离 <=>，所以这里是 vector_cosine_ops。
--     如果建了 cosine 索引却用 <-> 查询，PostgreSQL 会静默退化为全表扫描 ——
--     能返回正确结果，只是慢。所以验证必须包含 EXPLAIN。
--
--  验证索引确实被使用（回填后执行）：
--    EXPLAIN (ANALYZE, BUFFERS)
--    SELECT id FROM document_chunk
--     WHERE embedding IS NOT NULL
--     ORDER BY embedding <=> (SELECT embedding FROM document_chunk
--                              WHERE embedding IS NOT NULL LIMIT 1)
--     LIMIT 5;
--    期望出现 "Index Scan using idx_chunk_embedding_hnsw"
--
--  ⚠️ 索引版本注意事项：HNSW 索引绑定建索引时的 pgvector 版本。
--     将来升级 pgvector 后需要重建索引（REINDEX INDEX idx_chunk_embedding_hnsw）。
-- ============================================================================
