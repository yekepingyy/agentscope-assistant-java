# 小深 · AgentScope Java 2.0 Harness

面向**多副本生产部署**的私人助手示例：用 [AgentScope Java 2.0 Harness](https://java.agentscope.io/v2/zh/docs/index.html) 接线，而不是复刻 LangGraph 类结构。

| 数据 | 位置 |
|------|------|
| AgentState（推理上下文） | 官方 `MysqlAgentStateStore` → MySQL `agentscope_sessions` |
| 工作区文件（MEMORY / 日流水 / 压缩卸载） | Redis `RemoteFilesystemSpec` + `DistributedStore` |
| 网页会话 / 索引 | Redis `SessionStore` |
| 写文件待审批 | Redis `RedisPendingApprovalStore` |
| 同会话互斥 | Redis `RedisSessionRunLock` |

记忆请读官方文档：[分层记忆](https://java.agentscope.io/v2/zh/docs/harness/memory.html)。装配入口：`cn.deepassistant.config.AgentScopeConfig`。

## 官方能力怎么打开

| 能力 | 框架入口 | 本项目要点 |
|------|----------|------------|
| 日流水 Flush | `.memory(MemoryConfig)` | Redis `memory/YYYY-MM-DD.md` |
| 长期 Consolidation | 同上 | Redis `MEMORY.md` |
| 上下文压缩 | `.compaction(CompactionConfig)` | 压缩前 flush；大结果 eviction |
| Plan Mode | `.enablePlanMode()` | `workspace/plans/` 种子 + Redis 运行时 |
| 技能 | skill manage / curator | `workspace/skills/` |
| 子 Agent | **不** `.subagents(...)` | 官方自动加载 `workspace/subagents/*.md` |
| 智谱 MCP | `workspace/tools.json` | `build()` 前 `McpServerRegistrar.register` |
| 会话恢复 | `MysqlAgentStateStore` | `(userId, sessionId)` |
| 多用户隔离 | `IsolationScope.USER` | 记忆 / 工作区 / 网页会话按用户分桶 |
| 写文件 HITL | write/edit → ASK | SSE `interrupt` + `/api/assistant/resume` |

`workspace/` 只放种子文件（`AGENTS.md`、`tools.json`、skills、subagents）。运行时状态在 MySQL / Redis，任意副本可读。

### 子 Agent（官方配置）

- 声明文件：`workspace/subagents/<name>.md`（YAML frontmatter + 正文）
- 本仓库示例：`research-agent.md`（`isolated`、工具白名单、`maxIters: 25`、ephemeral leaf）
- Harness `DynamicSubagentsMiddleware` Layer 2 会扫本地 `subagents/*.md`，**无需**在 Java 里 `.subagents(...)`
- isolated 子工作区没有 `tools.json`：父 Agent 必须在 `build()` 前把 MCP 挂进 Toolkit，子 Agent 才能继承 `webSearchPrime` / `webReader`
- 长期记忆只留给父 Agent「小深」；research-agent 提示词要求不要写 `MEMORY.md` / 日流水

### 对话流（SSE）

- `AssistantChatService.chat` / `resume` 返回 `Flux`，直接订阅 `HarnessAgent.streamEvents`，**不用** `toIterable()` 阻塞
- 同会话互斥：`RedisSessionRunLock`
- 审批续跑：先 `claim` 待审批单，再带 confirm metadata 续跑

## 启动

目录名是 **`agentscope-assistant-java-2`**（Maven `artifactId` 可能仍是 `agentscope-assistant-java`）。

```bash
# 1. MySQL（官方 store 可自动建表；库名与 MYSQL_DATABASE 一致）
# export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=root MYSQL_PASSWORD=... MYSQL_DATABASE=agentscope_assistant

# 2. Redis
redis-server --daemonize yes --port 6379

# 3. 应用（Working directory = 本模块根，保证有 workspace/tools.json）
cd agentscope-assistant-java-2
export LLM_API_KEY=你的大模型Key
export MCP_API_KEY=你的智谱Key   # 须是 open.bigmodel.cn 的 API Key
export REDIS_HOST=127.0.0.1
mvn spring-boot:run
```

打开 http://localhost:8089 。`GET /api/health` 应看到 `"storage":"mysql+redis"`。MySQL 或 Redis 连不上时进程直接退出。

默认配置见 `application.yml`：`llm.model=qwen3.8-max`，`agent.max-iters=25`，`agent.auto-allow-tools=webReader,webSearchPrime`。

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/assistant/chat` | SSE 真流式对话，body 带 `userId` |
| POST | `/api/assistant/resume` | 写文件审批后续跑 |
| GET/POST | `/api/sessions` | 会话列表 / 新建 |
| GET/DELETE | `/api/sessions/{id}` | 详情 / 删除 |
| GET | `/api/history/search?q=&userId=` | 网页会话库关键词检索 |
| GET | `/api/memory?userId=` | 使用概况 + MEMORY.md + 日流水 |
| GET | `/api/agents` | 子 Agent / Toolkit 真实工具名 |
| GET | `/api/health` | 健康检查 |

## 学习文档

| 文档 | 内容 |
|------|------|
| [博客上篇](docs/小深-AgentScope-Java-2.0-Harness架构博客-01.md) | 架构、存储、装配、记忆、多用户 |
| [博客下篇](docs/小深-AgentScope-Java-2.0-Harness架构博客-02.md) | 工具、MCP、SSE、子 Agent、踩坑 |
| [测试用例](docs/test-cases.md) | 单测 / HTTP / 手工场景 |

> 博客里若仍贴有大段历史源码，**以仓库当前文件为准**；文首「与仓库对齐」小节列出了相对旧稿的关键变更。
