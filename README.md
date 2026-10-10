# devknow · 研发知识智能体平台

把开源项目的**文档**与 **issue/PR** 变成可检索、可被 Agent 调用的知识库。

解决的真实痛点：查技术文档时经常搜到旧版本的答案 —— 而"看起来正确"的错误答案比检索不到更糟。
本项目在数据模型里保留 `version` 与 `version_order`，让检索可以按版本硬过滤。

> 目标岗位方向：Java 后端 / Java + 智能体。
> 8 周计划，当前处于 **第 1 周（数据接入）** 完成状态。

---

## 当前状态

| 项 | 状态 |
|---|---|
| 构建 | ✅ `mvn test` 全绿（4/4 通过），`devknow.jar` 43 MB 可执行包已产出 |
| 模块 | ✅ 6 个模块齐备（common / domain / ingestion / retrieval / agent / web） |
| 数据库迁移 | ✅ `V1__init.sql`（5 张表 + 注释 + 部分索引）已就位，Flyway 自动执行 |
| 采集能力 | ✅ GitHub issue/PR 采集、Git 仓库文档采集、任务状态机、幂等落库 |
| 切分能力 | ⚠️ `HeadingChunker` 已实现（原计划第 2 周，提前完成） |
| 向量化 | ❌ 第 2 周：加 `V2__add_pgvector.sql` + `EmbeddingService` 实现 |
| 检索 | ❌ 第 3-4 周：BM25 + 向量 + RRF + Rerank |
| Agent | ❌ 第 5-6 周：ReAct + 工具调用 + SSE |
| **启动验证** | ✅ **已实测启动成功**：Tomcat 8080、Spring 上下文加载、`/api/health` 返回 200 且中文正常 |
| **连库验证** | ⚠️ **尚未连过数据库** —— 本机 PostgreSQL 18.4 + pgvector 0.8.2 已就绪，待执行"待验证清单" |

### 代码规模

| 模块 | main | test | 说明 |
|---|---|---|---|
| devknow-common | 8 | 0 | 统一返回体、异常、错误码、哈希、路径、文件工具、EmbeddingService 接口 |
| devknow-domain | 16 | 1 | 5 个实体 + 5 个 Mapper + DTO + 枚举 + **迁移一致性测试** |
| devknow-ingestion | 18 | 0 | GitHub 客户端、限流器、任务状态机、解析器、切分器 |
| devknow-retrieval | 0 | 0 | 第 3 周填充 |
| devknow-agent | 0 | 0 | 第 5 周填充 |
| devknow-web | 5 | 0 | 启动类、全局异常处理、健康检查、Admin 接口 |

---

## 架构

```
数据源 ──→ 模块1 接入流水线 ingestion ──→ PostgreSQL ──→ 模块2 检索 ──→ 模块3 Agent
  │            SourceSync → Parser              │            │              │
  │            → Chunker → IndexWriter          │        版本过滤         ReAct
  │                                             │        BM25+向量        工具调用
GitHub API                              knowledge_source  RRF融合         SSE流式
Git 仓库                                document          reranker
                                        document_chunk
                                        ingestion_job
                                        agent_config
```

**依赖方向**：`web → {ingestion, retrieval, agent} → domain → common`
（`agent` 不依赖 `ingestion`，保证采集细节不泄漏到 Agent 运行时）

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
psql -U postgres -h 127.0.0.1 -c "CREATE DATABASE devknow OWNER devknow;"
psql -U postgres -h 127.0.0.1 -d devknow -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

Redis 已在本机运行（`D:\Redis`，`127.0.0.1:6379`）。**第 1 周其实用不到它** ——
限流是单机实现，Redis 到第 3 周做分布式限流才会用到。

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
java "-Dfile.encoding=UTF-8" -jar devknow-web/target/devknow.jar
```

数据库无需设置 —— `application.yml` 的默认值（`localhost:5432/devknow`，用户 `devknow`）已与本机 PostgreSQL 一致。

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

Flyway 会在启动时自动执行 `V1__init.sql` 建表。

### 4. 验证（Day 1 验收标准）

```bash
# ① 服务活着
curl http://localhost:8080/api/health
# → {"code":0,"message":"成功","data":{"status":"UP",...}}

# ② 配置生效、token 就位、磁盘目录正确
curl http://localhost:8080/api/health/selfcheck

# ③ 数据库表已建好（应有 5 张表 + flyway_schema_history）
docker exec -it devknow-postgres psql -U devknow -d devknow -c '\dt'
```

---

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查（Day 1 验收点） |
| GET | `/api/health/selfcheck` | 配置自检：磁盘路径、token、限流余量 |
| POST | `/api/admin/sources` | 新建知识源（自动解析 owner/repo） |
| GET | `/api/admin/sources` | 知识源列表 |
| DELETE | `/api/admin/sources/{id}` | 删除（级联清理文档与 chunk） |
| POST | `/api/admin/sources/{id}/sync?scope=all\|issues\|docs` | 触发同步，创建任务 |
| GET | `/api/admin/jobs?status=PENDING&limit=50` | 查看采集任务 |
| GET | `/api/admin/stats` | 入库统计（各类型文档数、chunk 数、死信任务数） |
| GET | `/api/admin/github/status` | GitHub API 配额（验证 token 是否生效） |

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
    "docPathPattern": "src/main/antora/modules/ROOT/pages/**/*.adoc",
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
├── docs/                    # 架构图、周计划、开发日报
├── devknow-common/          # 通用能力（无业务依赖）
├── devknow-domain/          # 实体 + Mapper + DTO + 枚举
├── devknow-ingestion/       # 模块1：采集 + 解析 + 切分
├── devknow-retrieval/       # 模块2（第3周）
├── devknow-agent/           # 模块3（第5周）
└── devknow-web/             # 启动模块 + Controller + 配置 + 迁移脚本
```

**多版本怎么处理**（第 2 周）：不要每个版本 clone 一份，用一份仓库 + `git checkout <tag>`
或 `git worktree` 检出只读工作区。

---

## 待验证清单（环境已就绪，按顺序执行）

> 本机 PostgreSQL 18.4 + pgvector 0.8.2 + Redis 均已运行，数据库 `devknow` 与用户已创建。

按顺序执行，把报错贴回来即可：

1. `$env:GITHUB_TOKEN = 'ghp_...'` → 设置 token（见"配置环境变量"）
2. `mvn clean package` → 应看到 `Tests run: 4, Failures: 0, Errors: 0`
3. `java "-Dfile.encoding=UTF-8" -jar devknow-web/target/devknow.jar` → Flyway 执行 V1 迁移
4. `curl http://localhost:8080/api/health` → 返回 `status: UP`
5. `curl http://localhost:8080/api/health/selfcheck` → 确认 `githubTokenConfigured: true`
6. `curl http://localhost:8080/api/admin/github/status` → `remaining` 应接近 5000
7. 建源 + 触发同步 + 看统计（用上面的 curl 示例）
8. **幂等验证**：同步完成后重新触发一次，确认 `document` 行数不变

---

## 第 2 周计划

- `V2__add_pgvector.sql`：`document_chunk` 增加 `embedding vector(1024)` + HNSW 索引
- `EmbeddingService` 实现：先接 SiliconFlow（bge-m3）跑通，再考虑 DJL 本地推理
- `V2` 增量更新：按 `content_hash` 与 commit diff 只重建变化部分
- 多版本：`git tag` 拉取 v3.1/v3.2，写入 `version` 与 `version_order`
- 目标：**1 万 chunk 入库且带完整 `heading_path`**

---

## 铁律

1. **每天必须有一个"能跑的东西"** —— 宁可功能少，不可进度不可见
2. **每个技术选型当天定稿** —— 纠结超过 30 分钟就抛硬币，第 7 周可以改
3. **每天写日报，记下"为什么这么选"** —— 代码会忘，理由不会
