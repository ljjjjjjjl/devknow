## 第六周 · SSE 流式与演示页

> 目标：把第 5 周"一次性返回"的 Agent 改成"边生成边推送"，并做一个能直观看到效果的页面。
> 范围：流式输出 + 静态演示页。**会话持久化不在本周范围内**。

### 事件协议

`POST /api/agent/chat/stream`，响应 `Content-Type: text/event-stream`。

| 事件 | data | 说明 |
|---|---|---|
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

### 未做

- **会话持久化**：`V1__init.sql` 第 195–205 行预留了两张表的字段（含 `tool_calls` JSONB 与 `step_index`，原意是支持"执行到一半中断可恢复"），但表没建。本周按需求跳过
- **JDK HttpClient 没有响应体读超时**：`HttpRequest.timeout` 只覆盖到响应头，流建立后上游若不回数据会一直挂着。当前靠"客户端断开导致写失败"兜底，是一条真实的债
- 演示页只做了浏览器手测，没有自动化

### 改动文件

| 文件 | 说明 |
|---|---|
| `devknow-agent/.../stream/AgentStreamListener.java` | 新增，流式回调接口 |
| `devknow-agent/.../client/ChatClient.java` | 加 `streamChat`，抽出请求体组装 |
| `devknow-agent/.../engine/AgentRunner.java` | 循环收成一份，加 `stream` |
| `devknow-agent/src/test/.../AgentRunnerTest.java` | 新增，7 个用例，mock `ChatClient` |
| `devknow-web/.../config/AgentStreamConfig.java` | 新增，对话专用虚拟线程执行器 |
| `devknow-web/.../controller/AgentController.java` | 加 `POST /api/agent/chat/stream` |
| `devknow-web/src/main/resources/static/agent-demo.html` | 新增，演示页 |
| `devknow-web/src/main/resources/static/agent-demo.css` | 新增，演示页样式 |
| `devknow-web/src/main/resources/static/agent-demo.js` | 新增，SSE 解析与渲染 |
