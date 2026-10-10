# data 目录 —— 纯临时工作区

本目录只存放**运行期临时产物**，正式数据全部在 PostgreSQL 的
`document` / `document_chunk` 表中（设计见 docs/week1-plan.md）。

- `repos/`  git clone 下来的仓库，解析后即可整目录删除
- `tmp/`    任务运行期临时文件，按 jobId 隔离，任务结束即清理
- `logs/`   应用日志输出

**验证准则：把整个 data/ 删掉，应用应能正常启动和检索。**
如果删了就起不来，说明把 PG 当缓存用了，属于设计错误。

本目录已在 .gitignore 中排除。
