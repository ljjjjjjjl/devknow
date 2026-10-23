## 第六周 · SSE 流式与演示页

> 目标：把第 5 周"一次性返回"的 Agent 改成"边生成边推送"，并做一个能直观看到效果的页面。
> 范围：流式输出 + 静态演示页。**会话持久化不在本周范围内**。

### 事件协议

`POST /api/agent/chat/stream`，响应 `Content-Type: text/event-stream`。

| 事件 | data | 说明 |
|---|---|---|
| `start` | `{"conversationId":..}` | **最先发出**，新建会话时前端靠它拿到 id |
| `step` | `{"step":1}` | 一步 = 一次模型调用 |
| `delta` | `{"text":"根据"}` | 回答的增量片段，可多次 |
| `tool` | `{"name":"..","arguments":".."}` | 即将执行的工具 |
| `citation` | `{"documentId":..,"title":"..","url":".."}` | 已按 `documentId` 去重保序 |
| `done` | 完整答案 + `steps` + `model` + `elapsedMs` + `citations` | 唯一的正常终态 |
| `error` | `{"code":..,"message":".."}` | 与 `done` 互斥 |

- 只有 `delta` 承载正文；模型决定调工具时不产生文本
- `step` 在模型调用**之前**发出，所以前端立刻有反馈，而不是干等第一个 token
- 响应头早就发出去了，流里没法再用 HTTP 状态码报错，失败只能走 `error` 事件

### 上游流式解析

- `ChatClient.streamChat` 用 `stream: true` 请求，`BodyHandlers.ofInputStream()` 收，逐行读 `data:`，以 `[DONE]` 收尾
- **`tool_calls` 在流里是分片到达的**：首个分片带 `id` 和 `function.name`，后续分片只往 `function.arguments` 追加字符串。一次响应可能有多个工具调用且分片交错，**必须按 `index` 归并**
- `NullNode.asText(null)` 返回的是字符串 `"null"`，会把 `"content": null` 这类分片拼进正文。取值一律走显式判空
- 单个分片解析失败只跳过该分片并打 WARN：已经吐出去的正文仍然有效，为一条坏分片作废整轮代价过大
- 流式下 HTTP 状态码在响应头就到了、body 还没读，所以 `>= 400` 时必须主动把 body 读出来，否则错误信息只剩一个状态码

### 一个循环，两个入口

- `AgentRunner.run` 与 `AgentRunner.stream` 走同一个 `loop`，差别只有模型调用用 `chat` 还是 `streamChat`
- 非流式路径**保持走 `stream: false`**：它已经验收过，改成流式再自己拼回来等于换掉一个已验证路径，收益只是少几十行。两条路共享的只有请求体组装
- `AgentStreamListener` 只有 4 个回调（`step` / `delta` / `tool` / `citation`），**刻意没有 `onDone` / `onError`**：正常结束就是方法返回，失败就是抛异常。再加两个回调会导致"抛了异常又回调 onError"的双重通知，调用方要写两套收尾

### 传输层

- `SseEmitter` + 虚拟线程执行器：先返回 emitter 交还容器线程，真正执行丢给专用执行器
- 与采集的执行器分开：采集任务分钟级且要限并发，对话是秒级交互，两类负载混在一个池子里会互相拖累
- 每个事件显式指定 `TEXT_PLAIN`：让 String 走 `StringHttpMessageConverter`，JSON 原样落到 `data:` 行；不指定就得靠转换器顺序碰运气，万一被 Jackson 再序列化一次，前端收到的是带引号的字符串
- 客户端断开后 SSE 写会抛 `IOException`，包成 `ClientGoneException` **用来中断 ReAct 循环** —— 客户端都走了还继续调模型是纯浪费
- SSE 连接上限 5 分钟；一次多步 ReAct + 长回答可能跑几十秒

### 实测

| 项 | 值 |
|---|---|
| 首字节（TTFB） | **51 ms** |
| 完整回答 | 24.6 s |
| 响应体 | 20 KB |
| 事件数 | step 2 / tool 1 / citation 5 / delta 465 / done 1 |
| delta 粒度 | 词级（`"根据"` / `"检索"` / `"到的"`），不是整段 |
| 非流式回归 | `POST /api/agent/chat` HTTP 200、19.9 s，与改造前一致 |

- TTFB 与总耗时差三个数量级，是"真流式"而不是"攒完一次性发"

### 演示页

- `devknow-web/src/main/resources/static/agent-demo.html`（配 `agent-demo.css` / `agent-demo.js`），访问 `http://127.0.0.1:8080/agent-demo.html`，无构建步骤
- **不能用 `EventSource`**：它只支持 GET，而 `agentConfigId` + `query` 要走请求体。改用 `fetch` + `ReadableStream` 手动解析
- 一次 `read()` 不等于一个事件，必须按空行切块缓冲；`\r\n` 要先归一化，否则 `\r\n\r\n` 分隔符会被漏掉
- 正文一律用 `textContent` 渲染：模型输出属于不可信内容
- 右侧展示原始事件流，用来对照"推出去的是什么"和"渲染出来的是什么"
- 收到 `done` 时比对"流式拼接的文本"与 `done.answer`，不一致以后端为准并在事件日志里标出来
- **支持多轮**：第一轮不带 `conversationId`，从 `start` 事件拿到 id 后存在内存里，之后每轮都带上；顶部药丸显示 `会话 #N`。点"新会话"清掉 id 并恢复示例区 —— 示例按钮的点击是委托在 `#empty` 上的，所以是把原节点挂回去，而不是重建一份
- `start` 在 legend 与事件日志里用 `var(--dim)` 着色：它是会话记账，视觉上应该**次于** step / delta / tool，不抢注意力
- **示例问题从 13 条池子里随机抽 3 条**，刷新与"新会话"都换一批（Fisher-Yates 洗牌）。池子**不是随手写的**：每条都打过 `POST /api/admin/search`，只留 top-1 精排分 ≥ 0.6 的（拒答阈值 0.4，要留余量），并且确认过 top-5 里有对得上主题的文档。只看分数不够 —— 「@WebMvcTest 应该怎么用」的 top-1 是一条 AOP auto-configuration issue，权威文档（Testing Spring Boot Applications）排在它后面一位。淘汰了「Logback 升级要注意什么」0.367、「虚拟线程怎么开启」0.591、「怎么给接口加限流」0.027
- 配色统一走 `:root` 的调色板：示例按钮五档色取自事件配色（色档起点随机），页面背景两团淡辉光，头部渐变分隔线，右栏标题前加与对应事件同色的色条。`--cite` / `--done` 原先只以字面量出现在事件配色里，因为示例按钮要复用才提成变量

### 会话持久化

**表结构**（`V6__add_conversation.sql`）

- `conversation`：`agent_config_id` / `title` / `status`（`ACTIVE` | `DELETED`）/ 时间戳
- `conversation_message`：`conversation_id` / `seq` / `role` / `content` / `citations` JSONB / `error`
- **只存 user 与 assistant，不存 tool 往返**。一次 `search_knowledge` 的 tool 结果约 5×800 字符：存了它，`loadContext` 要么塞回上下文打爆预算，要么存了不读白占空间；回放工具原文还会让模型把"上次检索到的噪声"当依据
- **`V1__init.sql` 第 195–205 行预留的 `user_id` / `tool_calls` / `tool_call_id` / `token_in` / `token_out` / `step_index` 一个都没建**，逐条理由写在 V6 的文件头注释里。V1 不能改（Flyway 用 checksum 校验），所以差异记在 V6
- `citations` 是 jsonb，实体用 String 承载，写入必须手写 `CAST(#{...} AS jsonb)` 的 INSERT —— 和 `document_chunk.metadata` 同一个坑
- 软删而非物理删除；`idx_conversation_agent_updated` 是 `WHERE status='ACTIVE'` 的部分索引，所以列表查询**必须**带这个条件
- `updated_at` 没有触发器，追加消息时显式 `touch()`，否则会话列表排序退化成按创建时间

**上下文裁剪**（`ConversationService.trim`，静态纯函数，可脱离数据库单测）

- 从最近往前累加，**一旦某条超预算就停**：取连续窗口，不是"挑出所有塞得下的"。跳跃式挑选会把上下文打成碎片
- 双上限：token 预算 4000 + 最多 10 条。`TokenEstimator` 是启发式（中英混合与代码块会低估），单靠 token 不保险
- 窗口**必须以 user 开头**：截断点可能正好落在一条 assistant 上，那样模型会看到"没有提问的回答"
- 预算只约束历史，不含 system prompt、本轮 query，也**不含本轮 ReAct 里 tool 返回的检索结果**（那部分靠 `max_steps` 兜底）
- 失败占位在 SQL 层就被过滤，不进上下文；但消息列表接口仍返回它，供回看显示"这一轮失败了"

**编排顺序（踩过的坑）**

`loadContext` 必须在 `appendUser` **之前**。反过来的话 `loadContext` 会把本轮问题当成历史返回，而 `AgentRunner` 内部还会再追加一次本轮 query —— 同一条问题在 messages 里出现两次。

**新增 `start` 事件**

建立会话后**最先**推 `{"conversationId":..}`，让前端立刻拿到 id 才能续聊。它排在 `loadContext`/`appendUser` 之前 —— 那两步失败时前端至少知道自己该丢弃哪个会话。

**`tool_choice: required` 修掉了追问幻觉**

多轮实测发现：追问「那 Redis 呢？」时模型**一次检索都不发**就作答，还宣称"检索到了一些关于配置多个 Redis 数据源的信息"，引用 0 条。日志证据：

```
会话上下文: conversationId=1, 历史 2 条 → 取 2 条
流式调用模型: messages=4 条, tools=1 个
流式回合结束: content=780 字, toolCalls=0 个      ← 一次检索都没发
```

它把历史里上一轮自己的答案当成了知识来源，正好绕过已经建立的两层防护：相关度阈值管不到（压根没检索），拒答规则也管不到（没见过"检索结果为空"）。提示词里补的"历史不得作为知识来源"拦不住。

修法：**第一步下发 `tool_choice: "required"`**，把"必须先检索"从提示词请求升级成结构保证。实测 SiliconFlow 的 DeepSeek-V3 支持（返回 `content="" + tool_calls`）。修复后同一场景 `toolCalls=1`、检索参数 `如何配置多个Redis数据源`（指代解析正确）、引用 5 条。

副作用：打招呼类问题也会先检索一次，检索不到即按拒答规则回"知识库中没有找到相关内容"。对知识库助手来说这符合预期 —— DB 里的 system_prompt 本就写着"若检索不到相关内容，明确说明不知道"。

顺带堵掉了"首次调用返回空回合"的一个来源：第一步被强制调工具后，不可能再出现 `content=0 且 toolCalls=0`。

**实测**

| 场景 | 结果 |
|---|---|
| 多轮 | 第 2 轮 `toolCalls=1`、引用 5 条，两轮 `conversationId` 一致 |
| 刷新恢复 | `GET /messages` 返回 4 条，`citations` 是 JSON 数组（不是被转义的字符串） |
| 失败占位 | 中途断开 → `seq=2 role=assistant error="客户端断开"` |
| 软删 | `DELETE` 后列表不再出现；重复删除返回 `40004` |
| 全量测试 | 35 个用例全绿（6 + 11 + 11 + 7） |

**附带修掉的日志噪声**

客户端一刷新页面，SSE 写就抛 `IOException`。Spring 把它记成 async error 并做一次错误分发，落到 `GlobalExceptionHandler.handleUnknown` —— 那里要写 `ApiResponse`，而响应的 Content-Type 还停在 `text/event-stream`，于是再抛 `HttpMessageNotWritableException`。**一次断开在日志里留下 3 条 ERROR 堆栈**，演示页每刷新一次刷一屏。修法是给 `IOException` 加一个专门的 handler：记一行 WARN、返回空体（`void` 不经过消息转换器，绕开注定失败的 Content-Type 协商）。修完断开只剩 2 条 WARN。

### 未做

- **会话管理接口没做分页**：列表固定 `LIMIT 100`。当前数据量小，先不引入分页参数
- **多端并发写同一会话不安全**：`seq` 取 `MAX(seq)+1`，只保证单端串行。要做多端同步时改成 `INSERT ... SELECT COALESCE(MAX(seq),0)+1` 或对会话行 `FOR UPDATE`
- **演示页不持久化会话 id**：只存在内存里，刷新后回到"尚未开始"。后端有 `GET /conversations/{id}/messages` 可以恢复，但前端没接（要用 localStorage 存 id + 重渲染历史与引用）
- **上下文预算 4000 是拍的**：只按 `TokenEstimator` 的启发式估算，没有按真实 token 分布校准
- **JDK HttpClient 没有响应体读超时**：`HttpRequest.timeout` 只覆盖到响应头，流建立后上游若不回数据会一直挂着。当前靠"客户端断开导致写失败"兜底，是一条真实的债
- **空回合**（**第一步已修**）：`tool_choice: auto` 下模型偶尔既不给正文也不给 `tool_calls`。现在第一步被 `required` 强制调工具，不可能再出现该情况；残余风险只在第 2 步及以后。原始频率是流式首次调用 6 次里出现 1 次，相同请求重试即成功，属上游抖动而非解析问题。当前行为仍是直接失败（空回合继续只会在 `max_steps` 里空转 8 次），候选修法是空回合重试一次
- 演示页只做了浏览器手测，没有自动化

### 改动文件

| 文件 | 说明 |
|---|---|
| `devknow-agent/src/main/java/com/devknow/agent/stream/AgentStreamListener.java` | 新增，流式回调接口 |
| `devknow-agent/src/main/java/com/devknow/agent/client/ChatClient.java` | 加 `streamChat`，抽出请求体组装 |
| `devknow-agent/src/main/java/com/devknow/agent/engine/AgentRunner.java` | 循环收成一份，加 `stream` |
| `devknow-agent/src/test/java/com/devknow/agent/engine/AgentRunnerTest.java` | 新增，7 个用例，mock `ChatClient` |
| `devknow-web/src/main/java/com/devknow/web/config/AgentStreamConfig.java` | 新增，对话专用虚拟线程执行器 |
| `devknow-web/src/main/java/com/devknow/web/controller/AgentController.java` | 加 `POST /api/agent/chat/stream` |
| `devknow-web/src/main/resources/static/agent-demo.html` | 新增，演示页 |
| `devknow-web/src/main/resources/static/agent-demo.css` | 新增，演示页样式 |
| `devknow-web/src/main/resources/static/agent-demo.js` | 新增，SSE 解析与渲染 |

**会话持久化新增（11）**

| 文件 | 说明 |
|---|---|
| `devknow-web/src/main/resources/db/migration/V6__add_conversation.sql` | 新增，两张表 + CHECK 约束 + V1 预留字段的差异说明 |
| `devknow-domain/src/main/java/com/devknow/domain/entity/Conversation.java` | 新增，会话实体 |
| `devknow-domain/src/main/java/com/devknow/domain/entity/ConversationMessage.java` | 新增，消息实体（`citations` 用 String 承载 jsonb） |
| `devknow-domain/src/main/java/com/devknow/domain/enums/ConversationEnums.java` | 新增，`ConversationStatus` / `MessageRole` |
| `devknow-domain/src/main/java/com/devknow/domain/mapper/ConversationMapper.java` | 新增，列表（部分索引）/ touch / 软删 |
| `devknow-domain/src/main/java/com/devknow/domain/mapper/ConversationMessageMapper.java` | 新增，手写 INSERT + `CAST AS jsonb`、`nextSeq`、过滤失败占位 |
| `devknow-domain/src/main/java/com/devknow/domain/dto/AgentChatResult.java` | 新增，非流式返回会话 id 的包装（不让 Runner 认识会话） |
| `devknow-domain/src/main/java/com/devknow/domain/dto/ConversationMessageView.java` | 新增，把 jsonb 文本解析成 `List<Citation>` |
| `devknow-common/src/main/java/com/devknow/common/util/TokenEstimator.java` | 新增，token 估算收口到 common（原先有两份副本） |
| `devknow-agent/src/main/java/com/devknow/agent/conversation/ConversationService.java` | 新增，会话读写 + 上下文裁剪 |
| `devknow-agent/src/test/java/com/devknow/agent/conversation/ConversationServiceTest.java` | 新增，7 个裁剪边界用例，不连库 |

**会话持久化修改（11）**

| 文件 | 改动 |
|---|---|
| `devknow-agent/src/main/java/com/devknow/agent/engine/AgentRunner.java` | 加 `history` 参数 + 第三步硬约束（历史不是知识来源）+ 第一步 `tool_choice: required` |
| `devknow-agent/src/main/java/com/devknow/agent/client/ChatClient.java` | `chat` / `streamChat` 加 `requireToolCall` |
| `devknow-agent/src/main/java/com/devknow/agent/client/ChatMessage.java` | 加 `assistant(content)` 工厂，用于回放历史 |
| `devknow-agent/src/test/java/com/devknow/agent/engine/AgentRunnerTest.java` | 适配新签名，加 2 个历史拼装用例、2 个 `required` 用例（7 → 11） |
| `devknow-web/src/main/java/com/devknow/web/controller/AgentController.java` | 会话编排 + `start` 事件 + 3 个管理接口 |
| `devknow-web/src/main/java/com/devknow/web/config/GlobalExceptionHandler.java` | 兜客户端断开的 `IOException`，消掉 3 条 ERROR 堆栈 |
| `devknow-common/src/main/java/com/devknow/common/api/ErrorCode.java` | 加 `CONVERSATION_NOT_FOUND(40004)` |
| `devknow-domain/src/main/java/com/devknow/domain/dto/AgentChatRequest.java` | 加 `conversationId`（可空 = 新建） |
| `devknow-domain/src/test/java/com/devknow/domain/SchemaEnumConsistencyTest.java` | 支持多迁移文件，加 2 个 V6 约束用例（4 → 6） |
| `devknow-ingestion/src/main/java/com/devknow/ingestion/chunker/HeadingChunker.java` | `estimateTokens` 改为委托 `TokenEstimator` |
| `devknow-retrieval/src/main/java/com/devknow/retrieval/embedding/SiliconFlowEmbeddingService.java` | 同上，消掉"两处必须同步修改"的副本 |
