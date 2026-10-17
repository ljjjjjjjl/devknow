# devknow · 研发知识智能体平台

把开源项目的**文档**与 **issue/PR** 变成可检索、可被 Agent 调用的知识库。

解决的真实痛点：查技术文档时经常搜到旧版本的答案 —— 而"看起来正确"的错误答案比检索不到更糟。
本项目在数据模型里保留 `version` 与 `version_order`，让检索可以按版本硬过滤。

> 目标岗位方向：Java 后端 / Java + 智能体。
> 8 周计划，第 1–6 周已完成，当前处于 **第 7 周（评测 + 压测 + 部署）**。

---

## 当前状态

| 项 | 状态 |
|---|---|
| 构建 | ✅ `mvn test` 全绿（39 个用例），可执行包 `devknow.jar` 已产出 |
| 模块 | ✅ 6 个模块齐备（common / domain / ingestion / retrieval / agent / web），主代码 91 个 Java 文件 |
| 数据库迁移 | ✅ Flyway V1–V6 已执行：建表 / pgvector / 全文索引 / 点号归一化 / Agent 配置 seed / 会话表 |
| 采集 | ✅ GitHub issue + PR + 仓库文档三路，任务状态机 + 幂等落库 + 死信回收 |
| 语料规模 | ✅ 5344 篇 document（216 Doc + 4318 Issue + 810 PR），8580 个 chunk |
| 切分与向量化 | ✅ `HeadingChunker` 按标题层级切分并维护 `heading_path`；bge-m3 1024 维，覆盖率 100% |
| 检索 | ✅ PostgreSQL 全文（`ts_rank`）+ pgvector（HNSW）+ RRF(k=60) + cross-encoder 精排 |
| Agent | ✅ ReAct 循环（`max_steps=8`）+ 工具调用 + 相关度阈值拒答 + 会话持久化 + SSE 7 类事件 |
| 前端 | ✅ `static/agent-demo.html` 演示页，纯 HTML/CSS/JS 无构建步骤，支持多轮 |
| 评测 | ✅ 50 条检索评估集 + 20 条库外集 + 3 个 Python 评估/压测脚本（检索 A/B、并发压测、Agent 端到端） |

### 代码规模

| 模块 | main | test | 说明 |
|---|---|---|---|
| devknow-common | 10 | 0 | 统一返回体、错误码、异常、`EmbeddingService` 接口、`PgVector`、工具类 |
| devknow-domain | 29 | 1 | 7 个实体 + 7 个 Mapper + 11 个 DTO + 4 个枚举 + **迁移一致性测试** |
| devknow-ingestion | 22 | 1 | GitHub 采集、仓库文档同步、解析、切分、索引、向量回填、任务队列 |
| devknow-retrieval | 10 | 0 | 混合检索、嵌入客户端、精排客户端、评估跑批 |
| devknow-agent | 12 | 2 | ReAct 引擎、LLM 客户端、工具、会话服务、流式回调 |
| devknow-web | 8 | 0 | 启动类、4 个 Controller、全局异常、SSE 配置、迁移脚本、演示页 |
| **合计** | **91** | **4** | 39 个测试用例 |

---

## 架构

### 六个模块

```
┌──────────────────────────────────────────────────────────────────────┐
│ 接口层                                              devknow-web       │
│   DevknowApplication · AdminController · AgentController              │
│   EvalController · HealthController · SourceService                   │
│   GlobalExceptionHandler · AgentStreamConfig                          │
│   application.yml · db/migration V1~V6 · static/agent-demo.*          │
└──────────────┬─────────────────────────────────┬─────────────────────┘
               │                                 │
┌──────────────▼───────────────┐   ┌─────────────▼────────────────────┐
│ 采集层                        │   │ Agent 层                          │
│   devknow-ingestion          │   │   devknow-agent                   │
│   GitHubClient               │   │   AgentRunner      （ReAct 循环）  │
│   GitHubIssueSyncer          │   │   ChatClient       （LLM 调用）    │
│   GitRepoCloner              │   │   ChatMessage / AssistantTurn      │
│   TarballDownloader          │   │   SearchKnowledgeTool              │
│   RepoFetcher                │   │   ToolResult / AgentTool           │
│   GitRepoDocSyncer           │   │   AgentStreamListener              │
│   AsciiDocParser /           │   │   ConversationService              │
│   MarkdownParser             │   │   AgentConfigService               │
│   HeadingChunker             │   │                                    │
│   IndexWriter                │   │                                    │
│   ChunkBackfillService       │   │                                    │
│   EmbeddingBackfillService   │   │                                    │
│   IngestionJobService        │   │                                    │
│   JobWorker                  │   │                                    │
└──────┬────────────────┬──────┘   └─────────────────┬──────────────────┘
       │                │                            │
       │       ┌────────▼────────────────────────────▼───────────────┐
       │       │ 检索层                              devknow-retrieval │
       │       │   RetrievalService（向量 + 全文 + RRF + rerank）      │
       │       │   SiliconFlowEmbeddingService   bge-m3               │
       │       │   SiliconFlowRerankService      bge-reranker-v2-m3   │
       │       │   EvaluationService（评估跑批）                       │
       │       │   RerankProperties / EmbeddingProperties             │
       │       └────────┬────────────────────────────────────────────┘
       │                │
       │       ┌────────▼────────────────────────────────────────────┐
       └───────► 数据层                                devknow-domain  │
               │   entity ×7   dto ×11   enums ×4   mapper ×7         │
               │   （MyBatis-Plus，不含 Spring Web 依赖）              │
               └────────┬────────────────────────────────────────────┘
               ┌────────▼────────────────────────────────────────────┐
               │ 底座                                 devknow-common   │
               │   ApiResponse / ErrorCode / BizException             │
               │   EmbeddingService / PgVector / JsonUtils            │
               │   HashUtils / PathUtils / FileUtils / TokenEstimator │
               └─────────────────────────────────────────────────────┘
```

依赖边（按各模块 `pom.xml` 实际声明，不是按分层推断）：

| 模块 | 依赖 |
|---|---|
| `devknow-web` | `ingestion`, `agent` |
| `devknow-ingestion` | `domain`, `retrieval` |
| `devknow-agent` | `retrieval` |
| `devknow-retrieval` | `domain` |
| `devknow-domain` | `common` |
| `devknow-common` | — |

两点容易记错：`web` **不直接**依赖 `retrieval`（经 ingestion / agent 传递）；`agent` 不依赖 `ingestion`，采集细节不泄漏到运行时。

### RAG 检索链路

```
POST /api/admin/search {"query": "...", "topK": 20}
        │
        ▼
RetrievalService.search()
        │
        ├─ ① 嵌入：embedOne(query) → SiliconFlow bge-m3（1024 维）   ~250 ms
        │
        ├─ ② 候选窗口 window = max(rerank 可用 ? 50 : 20, topK)
        │      窗口开大是为了召回：@WebMvcTest 的权威文档在词法路排第 27，
        │      窗口 20 时它进不了候选池，rerank 再准也看不到
        │
        ▼  （两路串行执行）
   ┌────────────────────┐        ┌────────────────────────────┐
   │ 向量路               │        │ 词法路                       │
   │ pgvector HNSW        │        │ PostgreSQL 全文检索           │
   │ cosine 距离          │        │ ts_rank（注意：不是 BM25）     │
   │ 取 window 条         │        │ 取 window 条                 │
   └─────────┬──────────┘        └──────────────┬─────────────┘
             │                                   │
             └──────────────┬────────────────────┘
                            ▼
                  ③ RRF 融合：score = Σ 1/(60 + rank)
                     参数 k=60、RRF_CANDIDATES=20
                     同时记录 vectorRank / keywordRank（诊断用）
                            │
                            ▼
                  ④ rerank 开关 devknow.rerank.enabled
                     │
        ┌────────────┴─────────────┐
        │ 开                        │ 关
        ▼                          ▼
   bge-reranker-v2-m3          直接用 RRF 顺序
   候选 50 条 → 每条截 800 字符
   精排 → 列表顺序=精排顺序        rerankScore 为 null
   rerankScore 有值             （JSON 里字段整个消失）
        │                          │
        │   ⚠️ rerank 调用失败时     │
        │   不报错，log.warn 后      │
        └───────► 降级回 RRF ◄──────┘
                            │
                            ▼
                  ⑤ 截断 topK（上限 50）→ List<SearchResult>
```

### Agent 的 ReAct 循环

```
POST /api/agent/chat            （非流式，返回 AgentChatResult）
POST /api/agent/chat/stream     （SSE，7 类事件）
        │
        ▼
AgentController.chat / runStream
   ① configService.getRequired(agentConfigId)   取 提示词 / 模型 / maxSteps=8 / tool_names
   ② conversationService.resolve(...)           会话不存在则新建一条
   ③ loadContext(conversationId)     ← 必须早于 ④，否则本轮问题在 messages 里出现两次
   ④ appendUser(conversationId, query)
        │
        ▼
AgentRunner.loop(config, history, query, listener)
        │
        messages = [ system(基础提示词 + 三条硬约束) ]
                 + [ history（仅多轮时，提示词追加"历史不得作为知识来源"） ]
                 + [ user(query) ]
        │
        ▼
   ┌───────────────  for step = 1 .. 8  ──────────────────────────────┐
   │                                                                  │
   │   ChatClient.chat(messages, tools, requireToolCall)               │
   │        requireToolCall = (step == 1)     ← 把"必须先检索"从        │
   │                                            提示词请求升级成结构保证 │
   │                │                                                  │
   │                ▼                                                  │
   │         AssistantTurn                                             │
   │                │                                                  │
   │      ┌─────────┴──────────┐                                       │
   │      │                    │                                       │
   │  hasToolCalls        hasContent                                   │
   │      │                    │                                       │
   │      ▼                    ▼                                       │
   │  执行工具            return AgentChatResponse(                     │
   │  SearchKnowledgeTool    answer, citations, steps=step, model )    │
   │  （检索 topK=5）              │                                   │
   │      │                       └──► 正常结束，最短 2 步            │
   │      ▼                                                            │
   │  相关度判定：top1 < min-relevance-score(0.4) 或 0 命中            │
   │      │                                                            │
   │      ├ 有结果 → ToolResult(文本 + citations) → messages += tool   │
   │      └ 不相关 → ToolResult.empty("未检索到相关内容") → 同上        │
   │      │                                                            │
   │      └──► continue 下一 step（模型据此走拒答分支）                 │
   │                                                                   │
   │  工具和内容都没有（空回合）→ 抛 AGENT_LLM_ERROR(40003)             │
   └───────────────────────────────────────────────────────────────────┘
        │
        ▼
   超过 maxSteps → 抛 AGENT_MAX_STEPS_EXCEEDED(40001)

        ▼
AgentController 收尾
   ⑤ appendAssistant(conversationId, answer, citations)
   流式：send("done", {answer, steps, model, elapsedMs, citations}) → emitter.complete()
   非流式：ApiResponse<AgentChatResult{conversationId, response}>
```

### 一次流式对话的事件时序

```
客户端                              服务端
  │  POST /api/agent/chat/stream {"agentConfigId":1,"query":"..."}
  ├──────────────────────────────────►│
  │                                   │ ① resolve 会话（不存在则新建）
  │◄──── event: start ────────────────│ {"conversationId": 12}   ← 最先发，前端靠它续聊
  │                                   │ ② loadContext → appendUser
  │◄──── event: step ─────────────────│ {"step": 1}
  │                                   │ ③ 第 1 步强制 tool_choice="required"
  │◄──── event: tool ─────────────────│ {"name":"search_knowledge","arguments":"..."}
  │                                   │ ④ 执行检索（约 1.1 s）
  │◄──── event: citation ─────────────│ {"documentId":..}  ×N（按 id 去重）
  │◄──── event: step ─────────────────│ {"step": 2}
  │◄──── event: delta ────────────────│ {"text":"..."}   ×465（增量片段）
  │◄──── event: done ─────────────────│ {answer, steps:2, model, elapsedMs, citations}
  │                                   │ ⑤ appendAssistant → emitter.complete()

  error 事件与 done 互斥；客户端断开时抛 ClientGoneException，只记 WARN + 落一条失败占位
```

### 两条链路全景

```
【采集链路】离线，JobWorker 每 5 秒轮询 ingestion_job
  GitHub API ──► issue / PR ─────────────┐
  Tarball 下载 / Git Clone ──► .adoc/.md ─┴─► Parser ──► HeadingChunker ──► IndexWriter
                                                                              │
                                                    ┌─────────────────────────┘
                                                    ▼
                                          document (5344) / document_chunk (8580)
                                                    │
                                          EmbeddingBackfillService（每 10 分钟扫 1000 条）
                                                    │
                                                    ▼
                                          bge-m3 → embedding vector(1024)

【查询链路】在线，三条独立入口
  浏览器 / 评估脚本
    ├─► POST /api/admin/search        ──► RetrievalService ──► pgvector + 全文 + RRF [+rerank]
    ├─► POST /api/admin/eval/run      ──► EvaluationService（服务端同口径评估，50 条）
    └─► POST /api/agent/chat[/stream] ──► AgentRunner ──► SearchKnowledgeTool
                                                          └─► RetrievalService（复用同一条链路）
```

---

## 快速开始

### 1. 启动依赖（PostgreSQL + Redis）

**本项目当前使用本机已安装的 PostgreSQL 18.4 + pgvector 0.8.2**（无需 Docker）。
`D:\pgsql\pgsql\bin` 已加入用户 PATH，以下命令在新开的窗口里可直接使用：

```powershell
# 启动 PostgreSQL（每次开机后执行一次）
pg_ctl start -D D:\pgsql\data -l D:\pgsql\data\log\server.log

# 判断是否在运行 —— 用 pg_isready，不要用 pg_ctl status（原因见下）
pg_isready -h 127.0.0.1 -p 5432
#   → 127.0.0.1:5432 - accepting connections

# 验证 pgvector 已启用
psql -U devknow -h 127.0.0.1 -d devknow -c "SELECT extversion FROM pg_extension WHERE extname='vector';"
```

> ⚠️ **`pg_ctl status` 不可信**：实测 PG 正在运行、`psql` 连接正常时，它仍报 `no server running`
> （它靠 `postmaster.pid` 里的 PID 核对进程，data 目录移动过的残留信息会误导判断）。
> **判断 PG 存活一律用 `pg_isready` 或直接 `psql` 连一次。**

> 💡 设置 `PGDATA` 后连 `-D` 都能省：
> ```powershell
> [Environment]::SetEnvironmentVariable('PGDATA', 'D:\pgsql\data', 'User')   # 重开窗口生效
> ```

首次使用需建库建用户并启用 pgvector：

```powershell
psql -U postgres -h 127.0.0.1 -c "CREATE USER devknow WITH PASSWORD 'devknow';"

# 必须显式指定 UTF8 + ICU 中文排序。默认按系统 locale 建库会得到 SQL_ASCII，
# 中文入库直接报 invalid byte sequence，且事后迁移的代价远高于一次建对。
psql -U postgres -h 127.0.0.1 -c "CREATE DATABASE devknow_utf8 OWNER devknow TEMPLATE template0 ENCODING 'UTF8' LOCALE_PROVIDER icu ICU_LOCALE 'zh-Hans';"

psql -U postgres -h 127.0.0.1 -d devknow_utf8 -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

`application.yml` 默认连的就是 `devknow_utf8`。早期建过一个 SQL_ASCII 的 `devknow` 库，已被弃用。

Redis 已在本机运行（`D:\Redis`，`127.0.0.1:6379`），但**当前仍未用到** ——
限流是单机实现，依赖留在 POM 里是为后续做分布式限流。

<details>
<summary>替代方案（Docker Compose / 云数据库）</summary>

仓库内已备好 `docker-compose.yml`，装了 Docker 后 `docker compose up -d` 即可。
注意它会把 Redis 也起在 6379，与本机 Redis 冲突，可注释掉该 service 或改端口映射。

或用云 PostgreSQL（**Neon** / **Supabase**）免费额度，但需自行执行 `CREATE EXTENSION vector`，
并相应修改 `application.yml` 的 `DB_HOST` 等默认值。

</details>

### 2. 配置环境变量

> ⚠️ **Spring Boot 不会读取 `.env` 文件。** `.env` 是 Docker Compose 的约定，
> 只在 `docker compose up` 时生效。用 `java -jar` 直接跑时，必须设置真正的环境变量。

**方式 A：在当前 PowerShell 窗口临时设置**（最简单）

```powershell
$env:GITHUB_TOKEN = 'ghp_你的真实token'
$env:SILICONFLOW_API_KEY = 'sk-你的硅基流动key'
java "-Dfile.encoding=UTF-8" -jar devknow-web/target/devknow.jar
```

数据库无需设置 —— `application.yml` 的默认值（`localhost:5432/devknow_utf8`，用户 `devknow`）已与本机 PostgreSQL 一致。

`SILICONFLOW_API_KEY` 一个 key 打通三处：向量化（`bge-m3`）、精排（`bge-reranker-v2-m3`）、对话（`DeepSeek-V3`）。**缺了它应用能启动，但检索会报 `30001 EMBEDDING_NOT_CONFIGURED`，精排静默退回 RRF。**

**方式 B：永久设为 Windows 用户环境变量**（推荐，一劳永逸）

```powershell
[Environment]::SetEnvironmentVariable('GITHUB_TOKEN', 'ghp_你的真实token', 'User')
# 设完需重开 PowerShell 窗口才生效
```

**方式 C：本地 profile 文件**（不进 git）

建 `devknow-web/src/main/resources/application-local.yml`：

```yaml
devknow:
  github:
    token: ghp_你的真实token
```

启动时加 `--spring.profiles.active=local`。

> ⚠️ 用方式 C 务必确认该文件被 `.gitignore` 忽略 —— 当前 `.gitignore` 只忽略了 `.env`，
> **没有忽略 `application-local.yml`**，需自行补充，否则 token 会被提交。

**`.env` / `.env.example` 的作用**：给 `docker compose up` 使用，以及作为 token 的记录处
（已在 `.gitignore` 中，不会被提交）。

申请 token：**必须用 classic token**（不是 fine-grained）。

👉 https://github.com/settings/tokens/new → 选 `Generate new token (classic)` → 勾 **`repo`** scope。

> ⚠️ **为什么不能用 fine-grained token**（实测踩过）：
> fine-grained 的 `Only select repositories` 白名单**只能选你自己有写权限的仓库**，
> 搜不到 `spring-projects/spring-boot`；而改选 `Public repositories` 时，
> `Repository permissions` 区块**根本不渲染**，无法授予 `Issues` 权限。
> **采集第三方公开仓库的 issue，只有 classic token 的 `repo` scope 走得通。**

验证 token 是否生效：

```powershell
$env:GITHUB_TOKEN = 'ghp_你的token'
(Invoke-RestMethod -Headers @{Authorization="Bearer $env:GITHUB_TOKEN"} https://api.github.com/rate_limit).resources.core
# 输出 limit: 5000 表示生效（匿名只有 60）
```

**免费**，Free 账号即有 5000 次/小时额度。

> 不配 token 也能启动，但限流只有 **60 次/小时**（配了是 5000），采集必然中途失败。

### 3. 启动应用

```powershell
mvn clean package -DskipTests
java "-Dfile.encoding=UTF-8" -jar devknow-web/target/devknow.jar
# 或开发期： mvn spring-boot:run -pl devknow-web
```

> `-Dfile.encoding=UTF-8` 不能省：项目源码、SQL 注释、API 返回文案都含中文，
> Windows 默认编码会让返回的 JSON 出现乱码（表现为 `æåå` 这类字符）。
> 已在 POM 里锁定 `project.build.sourceEncoding=UTF-8`，但 JVM 运行期编码需显式指定。

Flyway 会在启动时自动执行 `V1`–`V6` 六个迁移。

### 4. 验证

```bash
# ① 服务活着
curl http://localhost:8080/api/health
# → {"code":0,"message":"成功","data":{"status":"UP",...}}

# ② 配置生效、token 就位、磁盘目录正确
curl http://localhost:8080/api/health/selfcheck

# ③ 数据库表已建好（应有 7 张表 + flyway_schema_history）
psql -U devknow -h 127.0.0.1 -d devknow_utf8 -c "\dt"
```

---

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| GET | `/api/health/selfcheck` | 配置自检：磁盘路径、token、限流余量 |
| POST | `/api/admin/sources` | 新建知识源（自动解析 owner/repo） |
| GET | `/api/admin/sources` | 知识源列表 |
| DELETE | `/api/admin/sources/{id}` | 删除（级联清理文档与 chunk） |
| POST | `/api/admin/sources/{id}/sync?scope=all\|issues\|docs\|chunk` | 触发同步，创建任务 |
| GET | `/api/admin/jobs?status=PENDING&limit=50` | 查看采集任务 |
| GET | `/api/admin/stats` | 入库统计（各类型文档数、chunk 数、死信任务数） |
| GET | `/api/admin/github/status` | GitHub API 配额（验证 token 是否生效） |
| POST | `/api/admin/search` | 混合检索入参 `{query, topK}`，返回 20 条以内带分数与双路排名 |
| POST | `/api/admin/eval/run` | 跑 50 条评估集，返回 Hit@1 / Recall@5 / Recall@20 / MRR@5 与逐条明细 |
| POST | `/api/agent/chat` | 非流式对话入参 `{agentConfigId, conversationId?, query}` |
| POST | `/api/agent/chat/stream` | SSE 流式对话，7 类事件：`start`/`step`/`delta`/`tool`/`citation`/`done`/`error` |
| GET | `/api/agent/conversations?agentConfigId=1` | 会话列表，按最近活跃倒序 |
| GET | `/api/agent/conversations/{id}/messages` | 消息列表，含失败占位，供刷新恢复 |
| DELETE | `/api/agent/conversations/{id}` | 软删除会话，重复删除返回 `40004` |

### 端到端示例

```bash
# 建源
curl -X POST http://localhost:8080/api/admin/sources \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Spring Boot 官方文档",
    "type": "GITHUB_REPO",
    "repoUrl": "https://github.com/spring-projects/spring-boot",
    "branch": "main",
    "docPathPattern": "documentation/**/*.adoc,build-plugin/**/*.adoc,*.adoc",
    "license": "Apache-2.0"
  }'

# 触发同步（后台 Worker 会在 5 秒内领走任务）
curl -X POST 'http://localhost:8080/api/admin/sources/1/sync?scope=all'

# 看任务状态
curl 'http://localhost:8080/api/admin/jobs?limit=10'

# 看统计数字（简历素材）
curl http://localhost:8080/api/admin/stats
```

---

## 关键设计决策

### 1. 幂等采集：`UNIQUE(source_id, doc_type, external_id, version)` + `ON CONFLICT DO UPDATE`

重跑采集不会产生重复行。这是从"脚本"到"系统"的分界线，也是 Day 5 的验收项
（连跑两次，`document` 行数不变）。

### 2. 增量更新：`content_hash` 比对

sha256 未变则跳过切分与向量化，能省 90% 算力。用 sha256 而不是 `hashCode()`：
后者碰撞概率高，会导致"内容变了却判定未变"这类静默数据错误。

### 3. 任务队列：`SELECT ... FOR UPDATE SKIP LOCKED`

`ingestion_job` 兼作任务队列，配合部分索引 `WHERE status='PENDING'` 让取任务 O(1)，
多实例天然安全。

**为什么不上 Kafka**：任务粒度粗（一次同步一个仓库）、小时级频率，Kafka 的吞吐优势用不上，
反而多一个运维组件。代价是要自己实现锁超时回收（`reclaimStaleJobs`）——
这在 Kafka 里由 session timeout 负责。

**关键实现细节**：取任务用**独立短事务**，执行任务在事务外。
把两者放进同一个长事务会让锁持有数分钟、MVCC 快照无法回收 —— 这是"把数据库当队列用"的经典反模式。

### 4. 版本感知：`version` + `version_order`

**禁止用字符串比较版本号**：`'3.10' < '3.9'` 结果是 `true`。
第 4 周的版本过滤依赖整数 `version_order`。

### 5. 标题路径 `heading_path`

切分时记录 `"Spring Boot > Data > Redis > 配置"`，用于：
- 检索时关键词加权
- 生成时给 LLM 上下文定位
- 引用展示可读性

从第一天就填，**事后补元数据是灾难**。

### 6. 数据只在 PostgreSQL，`data/` 可随时删除

磁盘上只有临时 clone 的仓库，解析完即清理。正式数据全在 PG。

**验证准则**：删掉整个 `data/` 目录，应用应能正常启动和检索。
删了就起不来，说明把 PG 当缓存用了 —— 属于设计错误。

### 7. 精排的收益是局部的，不是全局的

50 条评估集实测（窗口 20，同一套语料）：

| 子集 | 条数 | 关 rerank · R@5 | 开 rerank · R@5 |
|---|---|---|---|
| overall | 50 | 0.90 | **0.96** |
| known-item | 30 | 1.00 | 1.00 |
| chinese | 10 | 0.80 | 0.80 |
| symbol | 10 | 0.70 | **1.00** |

总体的 +6pt **全部来自符号类查询**（`@WebMvcTest` 第 15→4、`@ServiceConnection` 未命中→第 2）。中文提问和短标题 issue 上精排反而是负收益（`cn-01` / `cn-03` 从第 1 掉到第 2）。代价是延迟 270 ms → 1116 ms。

**结论**：不是"rerank 更好"，而是"rerank 买的是符号/API 类查询的召回，代价是中文与短标题查询的排序"。候选送进精排时截断 800 字符（`max-document-chars`），是这部分损失的来源之一。

### 8. 相关性判断交给确定性代码，不交给模型

检索"有结果"不等于"结果相关"。没有下限时，top-1 = 0.31 和 0.99 一样被包装成「检索到 5 条结果」，模型于是得自己判断相关性 —— 而提示词只写了「检索到 0 条」和「检索到相关内容」两条分支，"检索到但不相关"这一支没有任何规则，模型就掉进默认的"乐于助人"行为。

修法：`SearchKnowledgeTool` 里加 `devknow.rerank.min-relevance-score`，top-1 低于阈值按 0 命中处理，让模型只面对"有结果/没结果"两个都已写好规则的分支。

**阈值必须用 Agent 改写后的 query 标定**：用户问「如何实现异步任务」，实际发起检索的是「实现异步任务」，原始措辞 0.318、改写后 **0.079**。用原始输入标定会得出错误结论。

### 9. "必须先检索"用结构保证，不用提示词请求

多轮追问「那 Redis 呢？」时，模型看到历史里上一轮的答案，直接凭它作答并宣称"检索到了一些…"，一次工具都没调（日志 `toolCalls=0`）。提示词里补的"历史不得作为知识来源"拦不住。

修法：第一步下发 `tool_choice: "required"`，把"必须先检索"从**提示词请求**升级成**结构保证**。这是和"相关度下限"同一个思路 —— 能把判断交给确定性代码的，就不要指望模型自觉。副作用是打招呼也会先检索一次，检索不到即按拒答规则回固定句，对知识库助手符合预期。

### 10. 会话编排在 Controller，`AgentRunner` 不认识"会话"

`AgentRunner` 是纯 ReAct 引擎，只多一个 `history` 参数；历史读写、落库、失败占位、会话归属校验全在 `AgentController`。代价是 `AgentChatResult` 要包一层 `conversationId`，收益是引擎保持纯净，单测传 `List.of()` 即可。

配套的一条硬约束：**`loadContext` 必须排在 `appendUser` 之前**。反过来的话 `loadContext` 会把本轮问题当历史返回，而 Runner 内部还会再追加一次 query，同一条问题在 messages 里出现两次。

---

## 踩过的坑（值得记住）

### ⚠️ 内部类不要命名为 `Data`

`DevknowProperties` 里原本有个内部类叫 `Data`，同时该类用了 Lombok 的 `@Data`。
在类作用域内，`@Data` 会**优先解析到内部类**而不是 `lombok.Data`，导致：

- 本类 Lombok 失效：`不兼容的类型: DevknowProperties.Data无法转换为java.lang.annotation.Annotation`
- **级联污染**：同一模块内**所有**使用 Lombok 的类都报"找不到符号: 变量 log / 方法 getXxx()"，
  看起来像 Lombok 坏了，实际只错了一处

已改名为 `DataConfig`。**内部类命名请避开 Lombok 注解名**（Data / Builder / Value / Getter / Setter / AllArgsConstructor 等）。

### ⚠️ 忘了 `import lombok.Data` 的报错极具误导性

`GitHubClient` 里用了 `@Data` 但漏了 import，报的是
`找不到符号: 类 Data`（而不是"未导入 lombok.Data"）。
排查时容易被误判为"Lombok 依赖问题"。

**建议**：写完后跑一遍自查（本项目已用它定位到该问题）：

```powershell
Get-ChildItem -Recurse -Filter *.java | ForEach-Object {
  $t = Get-Content $_.FullName -Raw
  if ($t -match '@Data\b' -and $t -notmatch 'import\s+lombok\.Data\s*;') { "缺 import: $($_.FullName)" }
}
```

### 关于 Lombok 版本

父 POM 显式锁定 `lombok.version=1.18.42`。Lombok 需要 hook javac 内部 API，
JDK 每个大版本都可能需要新版配合，显式锁定可避免"依赖一个能编译但注解不生效的版本"。

---

## 目录约定

```
devknow/
├── data/                    # 纯临时工作区，整个 .gitignore，可随时删除
│   ├── repos/{owner}/{repo} # git clone 的仓库（按 owner 分层，避免同名冲突）
│   ├── tmp/{jobId}          # 任务临时文件，按 jobId 隔离
│   └── logs/                # 应用日志
├── docs/                    # 各周设计文档（第三~六周）
├── devknow-common/          # 通用能力（无业务依赖）
├── devknow-domain/          # 实体 + Mapper + DTO + 枚举
├── devknow-ingestion/       # 采集 + 解析 + 切分 + 向量回填 + 任务队列
├── devknow-retrieval/       # 混合检索 + 精排 + 评估跑批 + 评估集
├── devknow-agent/           # ReAct 引擎 + 工具 + 会话 + 流式回调
└── devknow-web/             # 启动模块 + Controller + 配置 + 迁移脚本 + 演示页
```

评估脚本独立于本仓库，放在 `D:\nodejs\workspace\`（`eval_retrieval.py` / `benchmark_search.py` / `eval_agent.py`），通过 HTTP 打本服务，不引入 Python 到 Maven 构建里。

---

## 验收清单

按顺序执行，任何一步失败都先检查上一步：

1. `pg_isready -h 127.0.0.1 -p 5432` → `accepting connections`
2. `mvn -B clean package` → 应看到 `Tests run: 39, Failures: 0, Errors: 0`
3. `java "-Dfile.encoding=UTF-8" -jar devknow-web/target/devknow.jar` → Flyway 执行到 V6
4. `curl http://localhost:8080/api/health` → `status: UP`
5. `curl http://localhost:8080/api/admin/github/status` → `remaining` 应接近 5000
6. `POST /api/admin/search` body `{"query":"如何配置日志级别","topK":20}` → 返回 20 条，且带 `rerankScore`
7. `POST /api/admin/eval/run` → Hit@1 0.80 / R@5 0.96 / R@20 0.98 / MRR@5 0.8597
8. `POST /api/agent/chat` body `{"agentConfigId":1,"query":"如何配置多个数据源"}` → `steps=2`、`citations` 非空
9. 打开 `http://127.0.0.1:8080/agent-demo.html` → 选一个示例问题，观察右侧原始事件流

---

## 后续计划

- **第 7 周（收尾）**：评测数字定稿、并发压测、部署说明
- **第 8 周**：README 与各周文档校订、技术博客

已识别但未做的改动：

- 候选池按"单路最好名次"截断，可把 100 候选压到 40 左右而不丢召回，预计省 300 ms
- Query 向量缓存（端到端 270 ms 里有约 250 ms 花在在线嵌入，是唯一有数量级收益的优化点）
- 用 token 数替代字符数做 chunk 硬上限
- 用 50 条评估集的实际 top-1 分布重新校准拒答阈值（当前 0.4 来自 9 条抽样）
- 会话列表分页（当前固定 `LIMIT 100`）、演示页把 `conversationId` 存 localStorage

---

## 铁律

1. **每天必须有一个"能跑的东西"** —— 宁可功能少，不可进度不可见
2. **每个技术选型当天定稿** —— 纠结超过 30 分钟就抛硬币，第 7 周可以改
3. **每天写日报，记下"为什么这么选"** —— 代码会忘，理由不会
