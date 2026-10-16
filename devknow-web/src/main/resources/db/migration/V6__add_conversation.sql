-- ============================================================================
--  V6__add_conversation.sql  ·  会话持久化（第 6 周）
-- ============================================================================
--  目的：多轮对话的上下文恢复。前端带上 conversationId 就能接着聊；
--        刷新页面也能把历史拉回来重新渲染。
--
--  ⚠️ 与 V1 预留字段的差异（V1__init.sql 第 195–205 行那段注释已过时）
--  ---------------------------------------------------------------------------
--  V1 预留了 user_id / tool_calls / tool_call_id / token_in / token_out / step_index，
--  本次全部不建，逐条说明：
--
--   1. user_id
--      当前没有多用户体系，加了只能填常量，是假字段。等真有用户体系时再加列。
--
--   2. tool_calls / tool_call_id
--      一次 search_knowledge 的 tool 结果约 5 段 × 800 字符。存了它，
--      loadContext 要么把它塞回上下文（token 预算瞬间打爆），要么存了不读（纯占空间）。
--      更麻烦的是：回放工具原文会让模型把"上次检索到的噪声"当成依据。
--      回看只需要 user + assistant 两条。
--
--   3. token_in / token_out
--      LLM 返回的 usage 当前只用于打日志，没有按会话计费或配额的功能，先不落库。
--
--   4. step_index
--      Agent 的步骤展示靠 SSE 实时推，回看不需要逐步还原 ReAct 过程。
--
--  Flyway 用 checksum 校验已执行的迁移，V1 不能改，所以把差异记在这里。
--
--  两个设计取舍：
--   · 软删（status='DELETED'）而非物理删除，可审计。代价是所有列表查询必须
--     显式带 status='ACTIVE'，否则走不到下面的部分索引。
--   · conversation_message.error 非空 = 该轮失败（如客户端断开）。
--     失败占位必须能被 loadContext 识别并跳过，否则模型的失败回复会被当成
--     正常历史回放，污染上下文。
-- ============================================================================


CREATE TABLE conversation (
    id              BIGSERIAL    PRIMARY KEY,
    agent_config_id BIGINT       NOT NULL REFERENCES agent_config(id),
    title           VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_conversation_status CHECK (status IN ('ACTIVE', 'DELETED'))
);

COMMENT ON TABLE  conversation            IS '会话：一次多轮对话的容器';
COMMENT ON COLUMN conversation.title      IS '列表展示用，取首条 user query 截断到 200 字符';
COMMENT ON COLUMN conversation.status     IS 'ACTIVE | DELETED（软删）';
COMMENT ON COLUMN conversation.updated_at IS '由应用在追加消息时显式更新，没有触发器，不会自己变';

-- 会话列表按"最近活跃"排序。部分索引只覆盖未删除的会话。
CREATE INDEX idx_conversation_agent_updated
    ON conversation (agent_config_id, updated_at DESC)
    WHERE status = 'ACTIVE';


CREATE TABLE conversation_message (
    id              BIGSERIAL    PRIMARY KEY,
    conversation_id BIGINT       NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    seq             INT          NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    content         TEXT,
    citations       JSONB,
    error           VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_message_conversation_seq UNIQUE (conversation_id, seq),
    CONSTRAINT ck_message_role CHECK (role IN ('user', 'assistant'))
);

COMMENT ON TABLE  conversation_message           IS '会话消息：只存 user 与 assistant，不存 tool 往返';
COMMENT ON COLUMN conversation_message.seq       IS '会话内序号，从 1 开始；(conversation_id, seq) 唯一';
COMMENT ON COLUMN conversation_message.citations IS '本次回答的引用来源（JSON 数组），刷新恢复时前端据此重渲染';
COMMENT ON COLUMN conversation_message.error     IS '非空表示该轮失败；loadContext 会跳过这类消息';

CREATE INDEX idx_message_conversation_seq
    ON conversation_message (conversation_id, seq);
