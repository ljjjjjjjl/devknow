-- ============================================================================
--  V1__init.sql  ·  研发知识智能体平台 (devknow) 初始化迁移
-- ============================================================================
--  执行者：Flyway（应用启动时自动执行），不要手动改这个文件
--  放置位置：devknow-web/src/main/resources/db/migration/V1__init.sql（已就位）
--
--  ⚠️ 迁移文件一旦提交过，就不要再改它！任何调整都新建 V2__xxx.sql
--     原因：Flyway 用 checksum 校验已执行过的迁移，改动会让启动直接失败。
--
--  设计要点见 docs/week1-plan.md 第五节。三句话概括：
--   1. 幂等靠 document 的唯一键 + ON CONFLICT DO UPDATE
--   2. 增量靠 content_hash 比对，没变就跳过切分与向量化
--   3. 任务队列靠 ingestion_job + SELECT ... FOR UPDATE SKIP LOCKED
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. knowledge_source · 知识源（一个仓库 / 一个文档站 = 一行）
-- ---------------------------------------------------------------------------
CREATE TABLE knowledge_source (
    id                  BIGSERIAL      PRIMARY KEY,
    name                VARCHAR(128)   NOT NULL,
    type                VARCHAR(32)    NOT NULL,
    repo_url            VARCHAR(512),
    repo_owner          VARCHAR(128),
    repo_name           VARCHAR(128),
    branch              VARCHAR(128)   DEFAULT 'main',
    doc_path_pattern    VARCHAR(256),
    license             VARCHAR(64),
    last_synced_commit  VARCHAR(64),
    last_synced_at      TIMESTAMPTZ,
    enabled             BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_source_type CHECK (type IN ('GIT_REPO', 'GITHUB_REPO', 'WEBSITE'))
);

COMMENT ON TABLE  knowledge_source                     IS '知识源：一个可同步的仓库或文档站';
COMMENT ON COLUMN knowledge_source.name                IS '展示名，如 "Spring Boot 官方文档"';
COMMENT ON COLUMN knowledge_source.type                IS 'GIT_REPO | GITHUB_REPO | WEBSITE';
COMMENT ON COLUMN knowledge_source.doc_path_pattern    IS '文档文件匹配模式，如 src/main/antora/modules/ROOT/pages/**/*.adoc';
COMMENT ON COLUMN knowledge_source.license             IS '开源许可证，合规留痕，公开演示项目必须有';
COMMENT ON COLUMN knowledge_source.last_synced_commit  IS '上次同步到的 commit，增量同步锚点';
COMMENT ON COLUMN knowledge_source.last_synced_at      IS '上次同步完成时间，配合 GitHub ?since= 做增量';

CREATE INDEX idx_source_enabled ON knowledge_source(enabled) WHERE enabled = TRUE;


-- ---------------------------------------------------------------------------
-- 2. document · 文档（一次抓取的一条记录：一个 issue / 一个文档文件）
-- ---------------------------------------------------------------------------
CREATE TABLE document (
    id              BIGSERIAL    PRIMARY KEY,
    source_id       BIGINT       NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
    doc_type        VARCHAR(32)  NOT NULL,
    external_id     VARCHAR(256) NOT NULL,
    url             VARCHAR(1024),
    title           TEXT,
    version         VARCHAR(64),
    version_order   INT,
    path            VARCHAR(512),
    raw_content     TEXT,
    content_hash    CHAR(64)     NOT NULL,
    lang            VARCHAR(16)  DEFAULT 'en',
    published_at    TIMESTAMPTZ,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- 幂等采集的基石：重跑不会产生重复行
    CONSTRAINT uk_document UNIQUE (source_id, doc_type, external_id, version),
    CONSTRAINT ck_document_type   CHECK (doc_type IN ('DOC', 'ISSUE', 'PR', 'RELEASE_NOTE', 'SOURCE_CODE')),
    CONSTRAINT ck_document_status CHECK (status IN ('ACTIVE', 'DELETED', 'OUTDATED'))
);

COMMENT ON TABLE  document                 IS '文档：抓取到的原始内容，切分前的完整单元';
COMMENT ON COLUMN document.doc_type        IS 'DOC | ISSUE | PR | RELEASE_NOTE | SOURCE_CODE';
COMMENT ON COLUMN document.external_id     IS 'issue number / 文件相对路径 / tag 名，取自数据源的稳定 ID';
COMMENT ON COLUMN document.version         IS '版本标识，如 v3.2.1 / 3.1.x；技术文档答案是强版本相关的';
COMMENT ON COLUMN document.version_order   IS '版本排序用整数。禁止用字符串比版本：''3.10'' < ''3.9'' 是 true';
COMMENT ON COLUMN document.path            IS '文档在仓库内的路径，用于生成 breadcrumb';
COMMENT ON COLUMN document.content_hash    IS 'sha256(title+body)，增量更新时 hash 未变则跳过后续处理';
COMMENT ON COLUMN document.metadata        IS '数据源特有字段：labels / state / state_reason / comment_count 等';

CREATE INDEX idx_document_source_type ON document(source_id, doc_type);
CREATE INDEX idx_document_version     ON document(version);
CREATE INDEX idx_document_hash        ON document(content_hash);
-- 支持 "查这个源下最近更新的文档"
CREATE INDEX idx_document_updated_at  ON document(source_id, updated_at DESC);


-- ---------------------------------------------------------------------------
-- 3. document_chunk · 知识单元（切分后的检索基本单位）
-- ---------------------------------------------------------------------------
--  注意：向量列（embedding vector(1024)）在第 2 周由 V2__add_pgvector.sql 添加，
--        不要现在加。V1 只保证"数据进得来、元数据全"。
-- ---------------------------------------------------------------------------
CREATE TABLE document_chunk (
    id              BIGSERIAL   PRIMARY KEY,
    document_id     BIGINT      NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    chunk_index     INT         NOT NULL,
    chunk_type      VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    heading_path    TEXT,
    heading_level   SMALLINT,
    content         TEXT        NOT NULL,
    content_tokens  INT,
    content_hash    CHAR(64)    NOT NULL,
    metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uk_chunk UNIQUE (document_id, chunk_index),
    CONSTRAINT ck_chunk_type CHECK (chunk_type IN ('TEXT', 'CODE', 'TABLE', 'LIST'))
);

COMMENT ON TABLE  document_chunk                IS '知识单元：切分产物，检索与向量化的基本单位';
COMMENT ON COLUMN document_chunk.heading_path   IS '标题路径，如 "Spring Boot > Data > Redis > 配置"。RAG 效果的隐形冠军：用于加权、上下文定位、引用展示';
COMMENT ON COLUMN document_chunk.heading_level  IS '标题层级，1=一级标题';
COMMENT ON COLUMN document_chunk.content_tokens IS 'token 数估算，用于控制 chunk 大小与 LLM 上下文预算';
COMMENT ON COLUMN document_chunk.content_hash   IS 'chunk 内容 sha256，用于跨文档去重';
COMMENT ON COLUMN document_chunk.metadata       IS '类型特有字段：代码语言 / 表格行列数 / 原文行号区间等';

CREATE INDEX idx_chunk_document ON document_chunk(document_id);
CREATE INDEX idx_chunk_hash     ON document_chunk(content_hash);


-- ---------------------------------------------------------------------------
-- 4. ingestion_job · 采集任务状态机（本项目的轻量任务队列）
-- ---------------------------------------------------------------------------
--  消费方式：SELECT ... FOR UPDATE SKIP LOCKED，多实例安全
--  为什么不上 Kafka：任务粒度粗（一次同步一个仓库）、小时级频率，
--  数据库表 + 部分索引已足够，少一个运维组件，且原理更好讲。
-- ---------------------------------------------------------------------------
CREATE TABLE ingestion_job (
    id              BIGSERIAL    PRIMARY KEY,
    source_id       BIGINT       NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
    job_type        VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    cursor          TEXT,
    progress_done   INT          NOT NULL DEFAULT 0,
    progress_total  INT,
    retry_count     INT          NOT NULL DEFAULT 0,
    max_retry       INT          NOT NULL DEFAULT 3,
    error_message   TEXT,
    locked_by       VARCHAR(128),
    locked_at       TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_job_type   CHECK (job_type IN ('SYNC_ISSUES', 'SYNC_DOCS', 'PARSE', 'CHUNK', 'EMBED')),
    CONSTRAINT ck_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'DEAD'))
);

COMMENT ON TABLE  ingestion_job               IS '采集任务状态机，同时充当轻量任务队列';
COMMENT ON COLUMN ingestion_job.status        IS 'PENDING|RUNNING|SUCCESS|FAILED|DEAD；DEAD 是超过 max_retry 的死信';
COMMENT ON COLUMN ingestion_job.cursor        IS '分页游标，进程重启后从游标继续，实现断点续跑';
COMMENT ON COLUMN ingestion_job.locked_by     IS 'Worker 实例标识，便于排查"谁卡住了这个任务"';
COMMENT ON COLUMN ingestion_job.error_message IS '最后一次失败的异常摘要，排查用';

-- 部分索引：只索引 PENDING 行，取任务 O(1)，且索引体积极小
CREATE INDEX idx_job_pick ON ingestion_job(status, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_job_source ON ingestion_job(source_id, created_at DESC);


-- ---------------------------------------------------------------------------
-- 5. agent_config · 智能体配置（本周建表，第 5 周才用）
-- ---------------------------------------------------------------------------
--  提前建表的价值：第 5 周写 Agent 时不用再改库、不用加班做 migration
-- ---------------------------------------------------------------------------
CREATE TABLE agent_config (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    system_prompt   TEXT,
    model           VARCHAR(64),
    temperature     NUMERIC(3,2) NOT NULL DEFAULT 0.20,
    max_steps       INT          NOT NULL DEFAULT 8,
    tool_names      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    kb_scope        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_agent_name        UNIQUE (name),
    CONSTRAINT ck_agent_temperature CHECK (temperature >= 0 AND temperature <= 2),
    CONSTRAINT ck_agent_max_steps   CHECK (max_steps > 0 AND max_steps <= 30)
);

COMMENT ON TABLE  agent_config              IS '智能体配置：提示词 + 模型 + 工具集 + 知识范围';
COMMENT ON COLUMN agent_config.max_steps    IS 'ReAct 循环最大步数，熔断用，防止无限工具调用';
COMMENT ON COLUMN agent_config.tool_names   IS '可用工具名单，如 ["search_issues","search_docs"]';
COMMENT ON COLUMN agent_config.kb_scope     IS '知识范围过滤，如 {"source_ids":[1,3],"versions":["3.2.x"]}';


-- ---------------------------------------------------------------------------
-- 预留说明（不建表，第 5 周由 V3__conversation.sql 创建）
-- ---------------------------------------------------------------------------
-- conversation          (id, agent_config_id, title, user_id, created_at, updated_at)
-- conversation_message  (id, conversation_id, role, content, tool_calls JSONB,
--                        tool_call_id, token_in, token_out, step_index, created_at)
--
-- 现在就写清字段意图的目的：第 5 周你不会因为"要不要存 tool_calls"纠结半天。
-- 关键决策：消息表要存 tool_calls 的 JSONB 原始记录 + step_index，
--          这样 Agent 执行到一半中断时可以恢复上下文（第 6 周的功能）。
-- ---------------------------------------------------------------------------


-- ============================================================================
--  初始化数据：一条知识源，用来验证 ORM 与采集链路
--  （真正生产环境应该走 POST /api/admin/sources 接口，这里只是让 V1 跑完就有数据）
-- ============================================================================
INSERT INTO knowledge_source (name, type, repo_url, repo_owner, repo_name, branch, doc_path_pattern, license)
VALUES ('Spring Boot 官方文档', 'GITHUB_REPO',
        'https://github.com/spring-projects/spring-boot',
        'spring-projects', 'spring-boot', 'main',
        'src/main/antora/modules/ROOT/pages/**/*.adoc',
        'Apache-2.0');
