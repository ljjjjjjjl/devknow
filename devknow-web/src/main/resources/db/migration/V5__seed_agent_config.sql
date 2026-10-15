-- ============================================================================
--  V5__seed_agent_config.sql  ·  seed 一条默认智能体配置（第 5 周）
-- ============================================================================
--  目的：让 Agent 对话接口（POST /api/agent/chat）开箱即用，无需手工建配置。
--
--  ⚠️ 幂等：按 uk_agent_name(name) 冲突时跳过，重复执行安全。
--
--  说明：conversation / conversation_message 两张表（V1 注释里预留的）
--  属于第 6 周"会话持久化 + 上下文恢复"，本周（最小闭环）暂不建。
-- ============================================================================

INSERT INTO agent_config
    (name, description, system_prompt, model, temperature, max_steps, tool_names, kb_scope, enabled)
VALUES (
    'devknow-default',
    '默认知识库问答 Agent',
    '你是研发知识库助手。回答前先调用 search_knowledge 检索知识库，并基于检索到的内容回答；若检索不到相关内容，明确说明"不知道"，不要编造。',
    'deepseek-ai/DeepSeek-V3',
    0.20,
    8,
    '["search_knowledge"]',
    '{}',
    TRUE
)
ON CONFLICT (name) DO NOTHING;
