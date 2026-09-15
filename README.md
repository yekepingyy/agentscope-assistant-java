# 小深 · AgentScope Java 2.0

面向**多副本生产部署**：AgentState、工作区文件、网页会话、写文件待审批都进 Redis。没有单机 JSON / 本地磁盘运行时模式。

用 [AgentScope Java 2.0 Harness](https://java.agentscope.io/v2/zh/docs/index.html) 的官方能力，而不是复刻原 LangGraph 项目的类结构。

记忆请看官方文档：[分层记忆](https://java.agentscope.io/v2/zh/docs/harness/memory.html)。接线入口：`AgentScopeConfig`。

## 官方已打开的核心能力

| 能力 | 框架入口 | 存储 |
|------|----------|------|
| 日流水 Flush | `.memory(MemoryConfig)` | Redis 工作区 `memory/YYYY-MM-DD.md` |
| 长期 Consolidation | 同上 | Redis 工作区 `MEMORY.md` |
| 记忆工具 | 默认注册 | `memory_search` / `memory_get` / `memory_save` / `session_search` |
| 上下文压缩 | `.compaction(CompactionConfig)` | 摘要进会话；原文卸到工作区 jsonl |
| Plan Mode | `.enablePlanMode()` | `workspace/plans/`（种子目录）+ Redis 运行时文件 |
| 技能 / 子 Agent | skills + `agent_spawn` | 工作区种子文件 + Redis 运行时文件 |
| 会话恢复 | `RedisAgentStateStore` | Redis `as:state:` / `as:list:` |
| 网页聊天记录 | `SessionStore` | Redis `as:web:` / `as:web-index:` |
| 写文件审批 | Permission ASK | Redis `as:pending:` |
| 多用户隔离 | `IsolationScope.USER` | 按 userId 分命名空间 |

所有运行时数据按 `(userId, sessionId)` 隔离。网页历史检索搜的是 `as:web:*`，和官方 `session_search`（jsonl）不是同一份。

`workspace/` 目录只放种子文件（`AGENTS.md`、技能、子 Agent 提示词）。对话状态、记忆文件、网页会话都在 Redis，任意副本可读。

## 启动

先有 Redis，再启动应用（**不需要** `distributed` profile）：

```bash
# 1. Redis（生产换成集群/哨兵地址，并设置 REDIS_PASSWORD）
redis-server --daemonize yes --port 6379

# 2. 应用
cd agentscope-assistant-java
export LLM_API_KEY=你的大模型Key
export MCP_API_KEY=你的智谱Key
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
# export REDIS_PASSWORD=...
mvn spring-boot:run
# 或：java -jar app.jar
```

打开 http://localhost:8089 。`GET /api/health` 应看到 `"storage":"redis"`。Redis ping 失败时进程不会起来。

多副本：每台机器同一套 `REDIS_HOST` / 密码 / database，不同 `server.port` 即可。

| 数据 | Redis key |
|------|-----------|
| AgentState | `as:state:{userId}:{sessionId}:{slot}` |
| 工作区文件 | `as:base:{namespace}/{fileKey}` |
| 网页会话 | `as:web:{userId}:{sessionId}` |
| 会话索引 | `as:web-index:{userId}` |
| 待审批 | `as:pending:{userId}:{sessionId}` |

实现见 `cn.deepassistant.redis` 和 `AgentScopeConfig`。官方要求 `filesystem(RemoteFilesystemSpec)` 必须配 `DistributedStore`，否则 `build()` 抛 `IllegalStateException`。

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/assistant/chat` | SSE 对话，body 带 `userId` |
| POST | `/api/assistant/resume` | 文件写入审批后续跑，body 带 `userId` |
| GET/POST | `/api/sessions` | 会话列表 / 新建 |
| GET/DELETE | `/api/sessions/{id}` | 详情 / 删除 |
| GET | `/api/history/search?q=&userId=` | 网页会话库关键词检索 |
| GET | `/api/memory?userId=` | 使用概况 + MEMORY.md + 日流水 |
| GET | `/api/agents` | 子 Agent / 官方工具说明 |
| GET | `/api/health` | 健康检查 |
