## 第五周 · Agent 引擎设计

> 最小闭环：ReAct 循环 + 工具调用 + 引用来源，非流式。第 6 周在其上叠加流式输出。

### 链路

- `AgentController` → `AgentConfigService`（读 `agent_config` 表拿到提示词 / 模型 / 步数 / 工具名单）→ `AgentRunner`
- `AgentRunner` 每步调 `ChatClient`（OpenAI 兼容 chat，手写 JDK HttpClient）
- 模型要调工具 → `AgentTool` 实现（本周只有 `SearchKnowledgeTool`，内部包 `RetrievalService`）→ 结果回填 → 再决策
- 模型给正文 → 结束，返回 `AgentChatResponse`（answer + citations + steps + model）

### 关键设计

- **工具失败喂回给模型**：`safeExecute` 把异常转成"工具执行失败"的 tool 结果，让模型自己决定换参数还是放弃，而不是炸掉整轮
- **`max_steps` 硬熔断**：默认 8；模型返回空回合（既无正文也无工具调用）直接失败，防静默空转
- **提示词分两层**：`agent_config.system_prompt` 是业务可改的，代码里再追加一条硬约束（祈使句 + 明确禁止项）。实测"回答前先检索"这种软性建议模型不照做
- **`tool_choice: auto` 显式下发**：缺省时部分模型不会主动调工具
- **`ToolResult` 拆两份**：`textForLlm` 受 token 预算约束要截断（单条 800 字符），`citations` 是结构化引用原样给前端。混在一个字符串里，前端就得从文本里抠 URL
- **`AgentTool` + Spring 自动收集**：注册进容器的实现被 `List<AgentTool>` 自动注入，不需要手写注册表；`agent_config.tool_names` 决定本次用哪几个，配置了未注册的名字只打 WARN 不报错
- **表驱动配置**：一行 `agent_config` = 一个 Agent，改配置不重启

### 实测

| 项 | 值 |
|---|---|
| 模型 | `deepseek-ai/DeepSeek-V3` |
| 平台 | SiliconFlow，与向量化 / 精排同平台同 key，不新增 provider |
| 闭环 | "如何配置多个数据源" → steps=2、5 条引用 |
| 命中 | 中文 query 检索到 id=2079「Data Access」（评估集 cn-01 的标准答案） |

### 当周未做，后续补

- SSE 流式 → 第 6 周完成
- 单元测试 → 第 6 周补上（`AgentRunnerTest`，7 个用例，mock `ChatClient` 不耗 API）
- 会话持久化 → 仍未做；两张表在 `V1__init.sql` 第 195–205 行预留了字段
