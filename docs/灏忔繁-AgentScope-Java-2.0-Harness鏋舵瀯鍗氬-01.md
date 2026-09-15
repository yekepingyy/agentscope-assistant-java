# 小深：用 AgentScope Java 2.0 Harness 做私人助手（上）

> 本篇是系列 **上篇**：为什么用官方 Harness、六层架构、存储最佳实践（MySQL AgentState + Redis 工作区）、装配层、记忆与多用户隔离。  
> 下篇：[小深-AgentScope-Java-2.0-Harness架构博客-02.md](./小深-AgentScope-Java-2.0-Harness架构博客-02.md)（工具、官方 tools.json MCP、SSE、子 Agent、踩坑笔记）  
> 项目目录：`agentscope-assistant-java-2` · 技术栈：**Spring Boot 3.2.5 + AgentScope Java 2.0.1 `HarnessAgent` + MCP SDK 0.17.0**  
> 装配入口：`cn.deepassistant.config.AgentScopeConfig` · 端口默认 **8089**

## 与仓库对齐（2026-09-15）

学习时请 **以仓库源码为准**；下文若仍夹有早期整文件粘贴，把它当成「读过一遍的注释」，不要当可复制的当前实现。

| 主题 | 当前做法 |
|------|----------|
| 子 Agent | **官方自动加载** `workspace/subagents/*.md`，Java **不要**再 `.subagents(...)` |
| research-agent | `workspace/subagents/research-agent.md`：`isolated` + `maxIters: 25` + ephemeral（不写 MEMORY） |
| MCP | `workspace/tools.json`；`build()` 前 `McpServerRegistrar.register`，isolated 子 Agent 才能继承工具 |
| 只读 MCP | `agent.auto-allow-tools=webReader,webSearchPrime`，强制 `readOnly`，避免 ASK |
| 记忆 | `.memory(MemoryConfig)` Flush → `memory/YYYY-MM-DD.md`，Consolidation → `MEMORY.md`（Redis 工作区） |
| 对话 SSE | `AssistantChatService` 订阅 `HarnessAgent.streamEvents` 的 **Flux**，禁止 `toIterable()` |
| 同会话互斥 | `RedisSessionRunLock` |
| 审批续跑 | `RedisPendingApprovalStore.claim` 后再 resume |
| 迭代上限 | `application.yml` → `agent.max-iters: 25`（不是 80） |
| 模型示例 | `llm.model: qwen3.8-max` |
| 存储 | AgentState → MySQL；工作区 / 网页会话 / pending / run-lock → Redis。不要 `MysqlDistributedStore.create()` |

对照阅读：[README](../README.md) · [下篇](./小深-AgentScope-Java-2.0-Harness架构博客-02.md) · [测试用例](./test-cases.md)

## 0. 写在前面

如果你做过 LLM Agent，大概经历过这些坑：

- 长任务中途「忘了自己在干什么」
- 调研过程把统筹上下文撑爆
- 自己写一套记忆抽取，和框架后台 Consolidation 抢 `MEMORY.md`
- 多副本部署后，工作区文件和对话状态各活各的
- MCP SDK 和 json-schema-validator 版本一拧，启动直接 `NoClassDefFoundError`

**AgentScope Java 2.0** 的答案同样不是「再换一个更强的模型」，而是用官方 **HarnessAgent**：规划、文件系统、子 Agent、HITL、分层记忆、压缩、技能，都已经在框架里。应用层要做的是 **接线**，而不是再造一套中间件。

| 能力 | 作用 | 本项目怎么打开 |
|------|------|----------------|
| 规划（todos / Plan Mode） | 多步任务显式化；复杂事先写方案 | `TodoTools` + `enablePlanMode()` |
| 文件系统（带隔离） | 中间产物进 Redis 工作区；按用户分命名空间 | `RemoteFilesystemSpec.isolationScope(USER)` |
| 子 Agent | 专科活隔离上下文 | 不 disable；官方扫 `workspace/subagents/*.md` + `agent_spawn` |
| 人工审批（HITL） | 写文件中断，等人批准后再续跑 | `PermissionBehavior.ASK` + Redis `as:pending:` + `/api/assistant/resume` |
| 分层记忆 | 日流水 Flush + 长期 Consolidation | `.memory(MemoryConfig)`，不要自己抽 JSON |
| 上下文压缩 | 超长摘要前缀，原文卸到 jsonl | `.compaction(...)` + `session_search` |
| 技能 | 目录渐进加载；可自进化 | `enableSkillManageTool` + `enableSkillCurator` |
| 会话恢复 | 任意副本用 (userId, sessionId) 续跑 | 官方 `MysqlAgentStateStore` |
| 多用户 | 记忆 / 工作区 / 网页会话互不可见 | `RuntimeContext.userId` + `IsolationScope.USER` |
| 多副本 | 状态、工作区、网页会话、审批单共享 | AgentState 走 MySQL，其余 Redis；**没有** `distributed` profile |

本项目是这套思想的 **Spring 接线示例**：统筹助手「小深」+ 联网调研工具 + 官方 research/general-purpose 子 Agent，前端通过 SSE 看工具轨迹并处理审批卡片。

和 `deepagents-assistant-java` 的差别一句话：**那边用 LangGraph4j 自己实现 Harness；这边把同类产品能力接到 AgentScope 官方 API 上。**

---

## 1. 系统架构

### 1.1 六层结构

```
┌─────────────────────────────────────────────────────────────────┐
│  L6  产品层                                                      │
│      AssistantController（SSE /chat + /resume，全部带 userId）     │
│      AssistantChatService（RuntimeContext + Redis pending）        │
│      AgentEventMapper（token / thinking / status / tool / interrupt）│
│      AgentActivityLogger（后台打工具参数、搜索词、思考与作答）        │
│      SessionStore（网页聊天 JSON，Redis as:web: / as:web-index:）   │
├─────────────────────────────────────────────────────────────────┤
│  L5  生产存储（混搭：MySQL AgentState + Redis 工作区，无单机 JSON） │
│      官方 MysqlAgentStateStore + RedisBaseStore + RedisPending     │
│      RemoteFilesystemSpec + DistributedStore                       │
│      ConversationMdc / SessionMdcInterceptor（日志 SESSION_ID）    │
├─────────────────────────────────────────────────────────────────┤
│  L4  统筹 HarnessAgent                                           │
│      ModelRegistry.resolve("openai:"+model) 流式                  │
│      maxIters=25 · PermissionMode.BYPASS + write_file ASK         │
│      MemoryConfig（Flush / Consolidation）                        │
│      CompactionConfig + ToolResultEviction + Plan Mode + Skills    │
│      stopOnReject / disableShellTool / enableAgentTracingLog=false │
├─────────────────────────────────────────────────────────────────┤
│  L3  工具                                                         │
│      官方：todo_write、filesystem、agent_spawn、memory_*、          │
│            session_search、skill_manage                           │
│      本项目：calculate、getCurrentDateTime、search_conversation_history │
│      智谱 MCP（tools.json）：webSearchPrime / webReader            │
│      build() 前 McpServerRegistrar + auto-allow-tools 强制只读     │
├─────────────────────────────────────────────────────────────────┤
│  L2  子 Agent（框架内置 general-purpose + 工作区 subagents/）      │
│      research-agent.md · DynamicSubagents Layer2 自动加载          │
│      isolated 工作区继承父 Toolkit 的 MCP，不写长期记忆            │
├─────────────────────────────────────────────────────────────────┤
│  L1  外部世界                                                     │
│      OpenAI 兼容大模型 · 智谱 MCP 0.17 · 必连 MySQL + Redis        │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 一次用户消息怎么走

```mermaid
sequenceDiagram
    participant U as 浏览器
    participant C as AssistantController
    participant S as AssistantChatService
    participant H as HarnessAgent
    participant F as MemoryFlushMiddleware
    participant M as 智谱 MCP（tools.json）

    U->>C: POST /api/assistant/chat (SSE, userId)
    C->>S: chat(userId, sessionId, message)
    S->>S: SessionStore 落 user 消息
    S->>H: streamEvents(UserMessage, RuntimeContext)
    H->>H: 注入 MEMORY.md / AGENTS.md / 技能
    alt 模型调 webSearchPrime / webReader
        H->>M: 官方 MCP tools/call（tools.json mcpServers）
        M-->>H: 检索 / 网页正文
    end
    alt write_file 命中 ASK
        H-->>S: RequireUserConfirmEvent
        S-->>U: SSE interrupt
        U->>C: POST /api/assistant/resume
        C->>S: resume(approved)
        S->>H: ConfirmResult 续跑
    end
    H-->>S: Thinking / ToolCall / TextBlockDelta ...
    S-->>S: AgentActivityLogger 打工具/搜索/思考日志（无 userId）
    S-->>U: SSE status / thinking / token / tool / plan / agent / done
    S->>S: SessionStore 落 assistant
    Note over H,F: 流结束后后台 Flush → memory/YYYY-MM-DD.md
```

### 1.3 与官方 Harness / 原 Java 项目的对应

| 概念 | AgentScope 官方 | 本项目 | 原 `deepagents-assistant-java` |
|------|-----------------|--------|-------------------------------|
| 工厂 | `HarnessAgent.builder()` | `AgentScopeConfig` | `CreateDeepAgent.create` |
| 运行时 | Harness ReAct 循环 | 同一个 `HarnessAgent` | LangGraph4j `AgentExecutorEx` |
| 记忆 | Flush + Consolidation | `.memory(MemoryConfig)` | 自研 `MemoryStore` + `update_memory` |
| 文件 | `FilesystemSpec` | 仅 `RemoteFilesystemSpec` + 隔离 USER | `WorkspaceFileOperations` |
| 子 Agent | `agent_spawn` | 不 disable | `task` / `task_batch` |
| HITL | Permission ASK | `approval-tools` + Redis pending | `approvalOn(...)` |
| 压缩 | `CompactionConfig` | 显式打开 | `SummarizingConversationContextPolicy` |
| 状态 | `AgentStateStore` | 官方 MySQL（工作区仍 Redis） | `FileSystemSaver` checkpoint |
| 联网 | `workspace/tools.json` `mcpServers` | Harness 构建期注册 MCP；无 REST 回退 | MCP 0.14.1 路径 |

### 1.4 角色分工

| 角色 | 职责 | 典型工具 |
|------|------|----------|
| **统筹（小深）** | 理解目标、规划、委派、对用户答复 | todos、files、agent_spawn、memory_*、时间/计算、网页检索 |
| **research-agent** | 多角度联网调研 | 工作区 `subagents/research-agent.md`；工具 `webSearchPrime` / `webReader` |
| **网页用户** | 看自己的会话和档案 | `/api/history/search`、`/api/memory`，与官方 jsonl 不是同一份 |

两份「历史」不要混：

| 数据 | 路径 | 谁写 | 谁读 |
|------|------|------|------|
| 网页聊天 JSON | Redis `as:web:{userId}:{sessionId}` | `SessionStore` | 侧栏、`search_conversation_history` |
| 压缩卸载日志 | Redis 工作区 `sessions/*.log.jsonl` | 官方 Compaction | 官方 `session_search` |
| 日流水 | Redis 工作区 `memory/YYYY-MM-DD.md` | 官方 Flush | 官方 `memory_search`、档案页 |
| 长期记忆 | Redis 工作区 `MEMORY.md` | 官方 Consolidator | 每轮注入 prompt、档案页 |

### 1.5 关键运行配置

- 端口：`8089`
- 模型：OpenAI 兼容（`llm.*`，示例 `qwen3.8-max` / 百炼 MaaS）
- **思考模式**：yml 当前 `llm.enable-thinking: true`，前端可折叠展示「已思考」。Qwen 开思考时流式 tool_call 可能不稳；若工具调不起来可改回 `false`。即使关掉，发送后仍会显示「思考中」（`ModelCallStart`）
- 联网：官方 `workspace/tools.json` 的 `mcpServers`。智谱 `/mcp` 是 Streamable HTTP（只接受 POST）；本项目按文档给 Java SDK 配 SSE：`/sse?Authorization=${MCP_API_KEY}`。SDK **必须 0.17.0+**。密钥用智谱 API Key（不要用百炼 Key）：优先 `MCP_API_KEY`，否则 yml `mcp.api-key`
- 迭代：`agent.max-iters=25`（yml；`AgentScopeConfig` 默认值已对齐）
- 审批：`write_file` / `edit_file`；只读 MCP：`auto-allow-tools=webReader,webSearchPrime`
- 工作区种子：`workspace/`（`AGENTS.md`、`tools.json`、skills、**subagents**）。IDEA Working directory 指错时 `WorkspaceRoots` 回退。运行时 MEMORY.md / 日流水 / jsonl 在 Redis
- MySQL：默认库 `agentscope_assistant`（utf8mb4），**启动必连**；官方 store 可自动建表 `agentscope_sessions`
- Redis：默认 `127.0.0.1:6379` db=0，**启动必 ping**；生产给工作区开 AOF/RDB。不需要 `distributed` profile
- 日志：`logback.xml` 打印 `%X{SESSION_ID}`，**不打印 userId**。`AgentActivityLogger` 会打模型调用、思考摘要、工具参数、搜索词/结果、作答预览（过长截断，密钥打码）

`${user.dir}` 是 **JVM 工作目录**（IDEA 跑该模块时一般是 `agentscope-assistant-java-2/`）：

| 配置 / 存储 | 位置 | 用途 |
|------|------|------|
| `agent.workspace` | `workspace/` | 种子：AGENTS.md、tools.json、skills、subagents；IDEA cwd 不对时 `WorkspaceRoots` 回退到模块根 |
| MySQL `agentscope_sessions` | AgentState | 官方 `MysqlAgentStateStore`；对话上下文、权限、计划；框架 `streamEvents` 自动 load/save |
| Redis `as:base:` | RemoteFilesystem | MEMORY.md、日流水、压缩卸载 jsonl |
| Redis `as:web:` / `as:web-index:` | 网页会话 | 侧栏列表与聊天全文 |
| Redis `as:pending:` | HITL | 写文件审批单，TTL 1 小时 |

### 1.6 存储怎么分（最佳实践）

官方文档里 Redis 是多副本默认；MySQL 适合「状态要进关系库、要备份」。`DistributedStore` **允许混搭**：`agentStateStore` 和 `baseStore` 可以不是同一个后端。本项目按「稳 / 热 / 短命」切开，这就是推荐用法。

| 数据 | 放哪 | 实现 | 为什么 |
|------|------|------|--------|
| 对话状态（messages、权限、计划） | **MySQL** | 官方 `MysqlAgentStateStore` | 每轮 load/save；要扛 Redis 重启 / `FLUSHDB`；备份、按用户查都方便 |
| 工作区（MEMORY.md、日流水、jsonl） | **Redis** | 本项目 `RedisBaseStore` | 每轮开头都读 MEMORY.md，要低延迟；写文件靠 Hash + Lua 做版本 CAS |
| 网页侧栏会话 | **Redis** | `SessionStore` | 列表 / 检索很勤，JSON 小对象 |
| 写文件审批 | **Redis + TTL** | `RedisPendingApprovalStore` | 批准可能打到另一台副本；1 小时无人点就该消失。MySQL 没有同等便宜的过期 |
| Docker 沙箱快照 / 锁 | **不用** | — | 已 `.disableShellTool()`，也没开容器沙箱 |

**不要用 `MysqlDistributedStore.create(dataSource)`。** 它是「全套进 MySQL、不跑 Redis」的一键工厂，会一次装上：

1. `MysqlAgentStateStore`（本项目已经单独接了）
2. `JdbcStore` —— 工作区也进 MySQL，每轮读 MEMORY.md 变慢，大 jsonl 撑库
3. `JdbcSnapshotSpec` / `JdbcSandboxExecutionGuard` —— 本项目用不到

它还管不到网页 `SessionStore` 和 pending。另外 `create()` 内部是 `new MysqlAgentStateStore(ds, true)`，默认库名 **`agentscope`**，和 JDBC URL 里的 `agentscope_assistant` 对不上——官方 SQL 用 `` `库`.`表` ``，会写到别的库或再新建一个 `agentscope`。

什么时候才换方案：

- 连 Redis 都不想运 → 再考虑 `create()`，并接受工作区变慢、pending 要自己做超时
- 只要最低延迟、能接受 Redis 丢数据 → AgentState 也回 Redis（官方多副本默认）
- 文件特别大、要归档 → 工作区再考虑 OSS，不是 MySQL

运维记住四条：`mysql.database` 必须和 JDBC URL 库名一致；Redis 给工作区开 AOF/RDB，否则 Java 重启对话还在、长期记忆可能没了；生产不要 `FLUSHDB` db=0；备份以 MySQL `agentscope_sessions` 为主。

### 1.7 多用户与 HITL（必读）

**（1）隔离靠二元组，不是靠「别传错 sessionId」**

所有寻址都是 `(userId, sessionId)`：

- HTTP：POST body.`userId`；GET/DELETE 用 `X-User-Id` 或 `?userId=`
- 空白 userId → `"local"`（兼容原来的单用户）
- `UserIds` / `SessionIds` 挡住 `../`、`/`、空字节（会拼进 Redis key 和 MySQL 列）
- Harness：`RuntimeContext.userId` + `IsolationScope.USER`，MEMORY.md 按用户分命名空间
- HITL：`as:pending:{userId}:{sessionId}`，避免审批打到另一副本丢单

**（2）写文件会停，必须 resume**

权限默认 `BYPASS`，只给 `write_file` / `edit_file` 加 ASK。框架抛 `RequireUserConfirmEvent` → SSE `interrupt` → 前端点批准/拒绝 → `ConfirmResult` 续跑同一条统筹图。

**（3）不要自己再抽一轮记忆**

流结束后 `MemoryFlushMiddleware` 已经在后台写日流水。本项目的 `HarnessMemoryCatalog` **只读**。若再调模型覆盖 `MEMORY.md`，会和 `MemoryConsolidator` 打架。

### 1.8 用本文源码复现项目

仓库里的 **主代码 + 测试 + 工作区模板 + `pom.xml` + `application.yml` + `index.html` + `.gitignore`** 下文均有对应内容（测试与工作区模板在下篇）；Java 已**省略 `import`**。

1. 准备 **JDK 17** 与 **Maven 3.8+**。
2. 建目录 `agentscope-assistant-java/`，把每个 `### \`path\`` 下的代码块存成该相对路径。
3. 只改配置里的密钥与模型接入点（本文已脱敏，**不能直接拿占位符去调模型**）：
   - `llm.base-url` / `llm.model`
   - `export LLM_API_KEY=...`（或写在 yml）
   - 智谱搜索：`export MCP_API_KEY=...`（须是 open.bigmodel.cn 的 Key，不要用百炼 Key）；IDEA 可写 yml `mcp.api-key`。保持 MCP **0.17.0**。Working directory 设为本模块根
   - MySQL：库名 `MYSQL_DATABASE`（默认 `agentscope_assistant`）。官方 `MysqlAgentStateStore(..., createIfNotExist=true)` 可自动建库建表
   - Redis：先 `redis-server`（默认 db=0）。**不要**再加 `distributed` profile
4. `mvn spring-boot:run`，打开 http://localhost:8089 ，侧栏填「用户」。MySQL 或 Redis ping 失败进程不会起来。

`workspace/` 启动时会建种子子目录。对话状态在 MySQL `agentscope_sessions`，记忆 / 网页会话在 Redis。Java 重启不丢对话；Redis 无持久化重启会丢 MEMORY.md。清表或 `FLUSHDB` db=0 才会把对应后端清掉。Redis 集成测试只清 **db=15**。免费额度耗尽时百炼会 403 `insufficient_quota`，与框架无关。

---

## 2. 模块详解与源码

下面按「阅读顺序」展开每个模块：**先说明职责，再贴核心源码**（Java 已省略 `import`，与仓库逻辑一致；配置中的密钥已脱敏）。

## 一、项目入口与总览

### `src/main/java/cn/deepassistant/DeepAssistantApplication.java`

**作用：** Spring Boot 启动入口。官方 Harness 能力都在 AgentScopeConfig 打开

```java
package cn.deepassistant;

/**
 * Spring Boot 入口。所有 AgentScope 官方能力都在 {@link cn.deepassistant.config.AgentScopeConfig} 里打开。
 */
@SpringBootApplication
public class DeepAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeepAssistantApplication.class, args);
    }
}
```

## 二、Harness 装配层

### `src/main/java/cn/deepassistant/config/AgentScopeConfig.java`

**作用：** 唯一装配入口。AgentState 走官方 `MysqlAgentStateStore`，工作区走 Redis RemoteFilesystem；再配 DistributedStore、分层记忆、压缩、Plan Mode、技能、写文件 ASK。启动连不上 MySQL 或 Redis 则进程退出。MCP 在 `build()` 前用 `McpServerRegistrar` 挂到父 Toolkit（Harness 2.0.1 会先快照子 Agent 再读 `tools.json`；isolated `research-agent` 工作区没有该文件）。`toolsConfig()` 只注入 deny，避免二次连 MCP。工作区路径经 `WorkspaceRoots` 解析。已删除自研 `MysqlAgentStateStore` / `RedisAgentStateStore` / `JsonFileAgentStateStore`。

```java
package cn.deepassistant.config;

/**
 * 把 AgentScope Java 2.0 Harness 接到 Spring。AgentState 进 MySQL，
 * 工作区文件进 Redis {@link RemoteFilesystemSpec} + {@link DistributedStore}。
 *
 * <p>对照官方文档（<a href="https://java.agentscope.io/v2/zh/docs/harness/memory.html">记忆</a>），
 * 不要再自己写一套「对话结束调模型抽 JSON」——框架已经有完整管线。
 *
 * <h2>官方核心能力在本 Bean 里怎么打开</h2>
 * <ol>
 *   <li><b>分层记忆</b>：{@link #memoryConfig()}。每次对话结束后后台 Flush 到
 *       {@code memory/YYYY-MM-DD.md}，再周期性 Consolidation 进 {@code MEMORY.md}。</li>
 *   <li><b>上下文压缩</b>：{@code compaction(...)}。消息太多时摘要前缀；压缩前会再 flush 一次。</li>
 *   <li><b>大工具结果卸载</b>：{@code toolResultEviction}。单次工具输出太长时落盘，上下文只留预览。</li>
 *   <li><b>Plan Mode</b>：{@code enablePlanMode()}。只读规划，方案写到 {@code workspace/plans/}。</li>
 *   <li><b>技能仓库 / 自进化</b>：{@code enableSkillManageTool} + {@code enableSkillCurator}。</li>
 *   <li><b>子 Agent</b>：不 disable；官方自动加载 {@code workspace/subagents/*.md} + {@code agent_spawn}。</li>
 *   <li><b>会话恢复</b>：官方 {@code MysqlAgentStateStore}，任意副本都能用 (userId, sessionId) 续跑。</li>
 *   <li><b>多用户隔离</b>：{@code IsolationScope.USER}。</li>
 *   <li><b>权限三态</b>：写文件 ASK，其余 BYPASS。</li>
 *   <li><b>MCP</b>：工作区 {@code tools.json}。Harness 2.0.1 会先快照子 Agent Toolkit，
 *       再在 {@code build()} 里注册 MCP；isolated 子工作区没有 {@code tools.json}，
 *       所以必须在 {@code build()} 前 {@code McpServerRegistrar.register}，再
 *       {@code toolsConfig()} 只注入 deny（避免二次连接）。不要 {@code disableToolsConfig()}。</li>
 * </ol>
 *
 * <p>刻意<b>不要</b>调用 {@code disableMemoryHooks()} / {@code disableMemoryTools()}。
 */
@Slf4j
@Configuration
public class AgentScopeConfig {

    @Value("${llm.api-key}")
    private String apiKey;
    @Value("${llm.base-url}")
    private String baseUrl;
    @Value("${llm.model}")
    private String modelName;
    @Value("${llm.temperature:0.3}")
    private double temperature;
    @Value("${llm.enable-thinking:false}")
    private boolean enableThinking;
    @Value("${agent.workspace}")
    private String workspace;
    @Value("${agent.max-iters:25}")
    private int maxIters;
    @Value("#{'${agent.approval-tools:write_file,edit_file}'.split(',')}")
    private List<String> approvalTools;

    @Value("${agent.memory.flush-throttle-minutes:0}")
    private int flushThrottleMinutes;
    @Value("${agent.memory.consolidation-min-gap-minutes:30}")
    private int consolidationMinGapMinutes;
    @Value("${agent.memory.daily-retention-days:90}")
    private int dailyRetentionDays;

    @Value("${redis.host:127.0.0.1}")
    private String redisHost;
    @Value("${redis.port:6379}")
    private int redisPort;
    @Value("${redis.password:}")
    private String redisPassword;
    @Value("${redis.database:0}")
    private int redisDatabase;
    @Value("${mysql.database:agentscope_assistant}")
    private String mysqlDatabase;
    @Value("${mcp.api-key:}")
    private String mcpApiKey;

    /**
     * 通过 AgentScope {@link ModelRegistry} 解析 OpenAI 兼容接口。
     * {@code parallelToolCalls=false}：一次只出一个工具调用，HITL 审批才不会叠多张单。
     * {@code stream=true}：事件流才能一 token 一 token 推到网页。
     *
     * <p><b>何时调用：</b>Spring 启动建 Bean。之后每轮 {@code streamEvents} 里框架拿这个
     * {@link Model} 去打大模型 API，本项目不再直接调它。
     */
    @Bean
    public Model chatModel() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 llm.api-key / LLM_API_KEY");
        }
        GenerateOptions options = GenerateOptions.builder()
                .temperature(temperature)
                .parallelToolCalls(false)
                .additionalBodyParam("enable_thinking", enableThinking)
                .build();
        ModelCreationContext context = ModelCreationContext.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .stream(true)
                .enableThinking(enableThinking)
                .component(GenerateOptions.class, options)
                .build();
        return ModelRegistry.resolve("openai:" + modelName, context);
    }

    /**
     * 官方两层记忆的开关。自定义 consolidation prompt 必须恰好两个 {@code %d}，
     * 所以只在官方默认模板后面追加中文说明。
     *
     * <p><b>何时调用：</b>Spring 启动。真正 Flush 发生在每轮 {@code streamEvents} 结束后的
     * {@code MemoryFlushMiddleware}；Consolidation 由框架按 {@code consolidationMinGap} 周期性触发。
     */
    @Bean
    public MemoryConfig memoryConfig() {
        MemoryConfig.FlushTrigger trigger = flushThrottleMinutes <= 0
                ? MemoryConfig.FlushTrigger.always()
                : MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(flushThrottleMinutes));
        return MemoryConfig.builder()
                .flushTrigger(trigger)
                .flushPrompt(MemoryFlushManager.DEFAULT_FLUSH_PROMPT + """

                        Additional project rules:
                        - Write every extracted bullet in Chinese.
                        - Prefer durable user preferences, project facts, and constraints.
                        - Never record API keys, passwords, or .env contents.
                        """)
                .consolidationPrompt(MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT
                        + "\nWrite the complete MEMORY.md in Chinese markdown.\n")
                .consolidationMinGap(Duration.ofMinutes(Math.max(1, consolidationMinGapMinutes)))
                .dailyFileRetentionDays(Math.max(7, dailyRetentionDays))
                .sessionRetentionDays(180)
                .build();
    }

    /**
     * Lettuce 客户端。密码空则不带 AUTH。容器销毁时 {@code shutdown}，避免连接泄漏。
     *
     * <p><b>何时调用：</b>Spring 启动；之后所有 Redis 操作共用这一条 client。
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient() {
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withDatabase(redisDatabase);
        if (redisPassword != null && !redisPassword.isBlank()) {
            uri.withPassword(redisPassword.toCharArray());
        }
        return RedisClient.create(uri.build());
    }

    /**
     * 同步连接并 PING。连不上直接 fail-fast，避免进程起来后第一条聊天才发现 Redis 挂了。
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        StatefulRedisConnection<String, String> conn = redisClient.connect();
        String pong = conn.sync().ping();
        if (pong == null || !"PONG".equalsIgnoreCase(pong)) {
            conn.close();
            throw new IllegalStateException("Redis 不可用: " + redisHost + ":" + redisPort
                    + " db=" + redisDatabase + " ping=" + pong);
        }
        log.info("[Redis] 已连接 {}:{} db={}", redisHost, redisPort, redisDatabase);
        return conn;
    }

    /** 同步命令接口，SessionStore / BaseStore / Pending 共用这一条连接。 */
    @Bean
    public RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> conn) {
        return conn.sync();
    }

    /**
     * 官方 {@link MysqlAgentStateStore}：对话上下文进 MySQL 表 {@code agentscope_sessions}。
     * {@code createIfNotExist=true} 会按需建库建表。库名必须和 JDBC URL 一致，
     * 因为官方 SQL 用 {@code `库`.`表`} 限定名。
     */
    @Bean
    public AgentStateStore mysqlAgentStateStore(DataSource dataSource) {
        MysqlAgentStateStore store = new MysqlAgentStateStore(
                dataSource, mysqlDatabase, "agentscope_sessions", true);
        log.info("[MySQL] 官方 AgentState 已就绪 database={}", mysqlDatabase);
        return store;
    }

    /** RemoteFilesystem 的 KV 后端：MEMORY.md、日流水、压缩卸载 jsonl。 */
    @Bean
    public RedisBaseStore redisBaseStore(RedisCommands<String, String> redis) {
        return new RedisBaseStore(redis);
    }

    /**
     * 官方分布式门面：AgentState 走 MySQL，工作区走 Redis BaseStore。
     * 不要用 {@code MysqlDistributedStore.create}，那会把工作区也改成 JDBC。
     */
    @Bean
    public DistributedStore distributedStore(AgentStateStore stateStore,
                                             RedisBaseStore baseStore) {
        return DistributedStore.builder()
                .agentStateStore(stateStore)
                .baseStore(baseStore)
                .build();
    }

    /**
     * 工作区走 RemoteFilesystem（Redis），AgentState 走官方 MysqlAgentStateStore。
     * 官方要求 RemoteFilesystem 必须配分布式状态，否则 {@code build()} 抛 IllegalStateException。
     *
     * <p><b>何时调用：</b>Spring 启动 {@code build()} 一次。之后每次聊天
     * {@code AssistantChatService.streamEvents} 调 {@code harnessAgent.streamEvents}，
     * 框架内部才会去调 MySQL AgentState、Redis 工作区、Toolkit 里的 {@code @Tool}、Flush 中间件。
     */
    @Bean(destroyMethod = "close")
    public HarnessAgent harnessAgent(Model chatModel,
                                     DistributedStore distributedStore,
                                     MemoryConfig memoryConfig,
                                     CommonTools commonTools,
                                     HistoryMemoryTools historyMemoryTools) throws Exception {
        Path ws = resolveAndInitWorkspace();
        exportMcpApiKey();
        // 业务工具挂到同一 Toolkit；Harness 自带的 filesystem / memory / spawn 不在这里注册。
        // MCP 必须在 build() 前挂上：框架会先用这份 Toolkit 快照子 Agent，之后才读 tools.json。
        String mcpKey = firstNonBlank(System.getenv("MCP_API_KEY"), mcpApiKey);
        ToolsConfig loadedTools = WorkspaceToolsConfigs.load(ws, mcpKey);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TodoTools());
        toolkit.registerTool(commonTools);
        toolkit.registerTool(historyMemoryTools);
        if (WorkspaceToolsConfigs.hasMcpServers(loadedTools)) {
            McpServerRegistrar.register(toolkit, loadedTools.getMcpServers());
            log.info("[Harness] MCP 已提前注册，isolated 子 Agent 可继承: {}",
                    loadedTools.getMcpServers().keySet());
        }

        // 默认 BYPASS：读文件、搜索、spawn 不弹窗。approval-tools（默认写/改文件）改成 ASK
        PermissionContextState.Builder perm = PermissionContextState.builder()
                .mode(PermissionMode.BYPASS);
        for (String toolName : approvalTools == null ? List.<String>of() : approvalTools) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            String name = toolName.trim();
            perm.addAskRule(name, new PermissionRule(name, null, PermissionBehavior.ASK, "policy"));
        }

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("xiao-shen")
                .sysPrompt("你是私人智能助手「小深」。人格与行为细则见工作区 AGENTS.md。"
                        + "跨会话事实以 MEMORY.md 为准；需要原文时用 memory_search / session_search / search_conversation_history。")
                .model(chatModel)
                .workspace(ws)
                .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
                .distributedStore(distributedStore)
                .toolkit(toolkit)
                .additionalContextFile("PREFERENCES.md")
                .permissionContext(perm.build())
                .maxIters(Math.max(25, maxIters))
                .memory(memoryConfig)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .flushBeforeCompact(true)
                        .offloadBeforeCompact(true)
                        .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                                .maxArgLength(2000)
                                .truncationText("... [truncated] ...")
                                .build())
                        .build())
                .toolResultEviction(ToolResultEvictionConfig.defaults())
                .enablePlanMode()
                .enableSkillManageTool(SkillManageConfig.defaults())
                .enableSkillCurator(SkillCuratorConfig.defaults())
                // 生产环境不给模型开宿主机 shell，避免误执行
                .disableShellTool();
        ToolsConfig filterOnly = WorkspaceToolsConfigs.filterOnly(loadedTools);
        if (filterOnly != null) {
            builder.toolsConfig(filterOnly);
        }
        HarnessAgent agent = builder.build();
        List<String> mcpTools = mcpToolNames(agent);
        if (mcpTools.isEmpty()) {
            log.warn("[Harness] tools.json 的 MCP 未进入 Toolkit。检查 {}/tools.json、MCP_API_KEY，"
                            + "以及 Could not read tools.json / Failed to register MCP server",
                    ws);
        }
        log.info("[Harness] workspace={} flushTrigger={} approvalTools={} mcpApiKeyEnv={} mcpTools={} tools={}",
                ws,
                memoryConfig.flushTrigger(),
                approvalTools == null ? List.of() : new ArrayList<>(approvalTools),
                notBlank(System.getenv("MCP_API_KEY")),
                mcpTools,
                agent.getToolkit().getToolNames());
        return agent;
    }

    private static List<String> mcpToolNames(HarnessAgent agent) {
        List<String> names = new ArrayList<>();
        for (String name : agent.getToolkit().getToolNames()) {
            if (agent.getToolkit().getTool(name) instanceof McpTool) {
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * {@code tools.json} 的 {@code ${MCP_API_KEY}} 只读 {@link System#getenv}（官方 ToolsConfigLoader）。
     * 本地若只配了 yml，在 {@code build()} 前尽量补进进程环境。
     */
    private void exportMcpApiKey() {
        String key = mcpApiKey;
        if (!ProcessEnv.ensure("MCP_API_KEY", key)) {
            log.warn("[Harness] MCP_API_KEY 未进入进程环境，将用 yml mcp.api-key 替换 tools.json 占位符");
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 解析工作区根目录并建好约定子目录。
     * 运行时文件走 Redis，这里的目录主要给启动时加载种子：{@code AGENTS.md}、skills、subagents。
     *
     * <p><b>何时调用：</b>仅 {@link #harnessAgent} 构建时一次。之后 Harness 读种子文件用本地盘，
     * 用户 MEMORY.md / 日流水走 Redis {@link cn.deepassistant.redis.RedisBaseStore}。
     */
    Path resolveAndInitWorkspace() throws Exception {
        Path configured = Path.of(workspace).toAbsolutePath().normalize();
        Path ws = WorkspaceRoots.resolve(workspace, AgentScopeConfig.class);
        if (!configured.equals(ws)) {
            log.warn("[Harness] agent.workspace={} 没有 AGENTS.md/tools.json，改用 {}", configured, ws);
        }
        Files.createDirectories(ws);
        Files.createDirectories(ws.resolve("memory"));
        Files.createDirectories(ws.resolve("skills"));
        Files.createDirectories(ws.resolve("agents"));
        Files.createDirectories(ws.resolve("plans"));
        Files.createDirectories(ws.resolve("knowledge"));
        Files.createDirectories(ws.resolve("sessions"));
        return ws;
    }
}
```

### `src/main/java/cn/deepassistant/config/WorkspaceRoots.java`

**作用：** IDEA Working directory 若指到别的工程，`${user.dir}/workspace` 没有种子文件。用本模块 `target/classes` 反推仓库里的 `workspace/`。

```java
package cn.deepassistant.config;

/**
 * 解析 Harness 种子工作区。yml 默认是 {@code ${user.dir}/workspace}，IntelliJ 若把
 * Working directory 设成别的工程（例如 {@code IdeaProjects/agentscope}），那个目录没有
 * {@code tools.json} / {@code AGENTS.md}。此时用本类 Class 的 {@code target/classes}
 * 反推模块根下的 {@code workspace/}。
 */
public final class WorkspaceRoots {

    private WorkspaceRoots() {
    }

    public static boolean hasSeeds(Path workspace) {
        if (workspace == null) {
            return false;
        }
        return Files.isRegularFile(workspace.resolve("AGENTS.md"))
                || Files.isRegularFile(workspace.resolve("tools.json"));
    }

    public static Path resolve(String configured, Class<?> anchor) {
        Path configuredPath = Path.of(
                        configured == null || configured.isBlank() ? "workspace" : configured)
                .toAbsolutePath()
                .normalize();
        if (hasSeeds(configuredPath)) {
            return configuredPath;
        }
        Path fromCode = fromCodeSource(anchor);
        if (hasSeeds(fromCode)) {
            return fromCode;
        }
        return configuredPath;
    }

    static Path fromCodeSource(Class<?> anchor) {
        if (anchor == null) {
            return null;
        }
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            URL location = source.getLocation();
            if (location == null) {
                return null;
            }
            Path loc = Path.of(location.toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(loc)) {
                Path parent = loc.getParent();
                return parent == null ? null : parent.resolve("workspace");
            }
            Path target = loc.getParent();
            if (target != null && "target".equals(target.getFileName().toString())) {
                Path moduleRoot = target.getParent();
                if (moduleRoot != null) {
                    return moduleRoot.resolve("workspace");
                }
            }
            return loc.resolve("workspace");
        } catch (Exception ignored) {
            return null;
        }
    }
}
```

### `DistributedAgentScopeConfig.java`（已删除）

原先用 Spring profile `distributed` 分「单机 JSON / Redis」两套 Bean。现已并入上面的 `AgentScopeConfig`：**AgentState 走 MySQL，工作区走 Redis RemoteFilesystem**，不需要 `--spring.profiles.active=distributed`。

---

## 三、分布式状态（MySQL AgentState + Redis 工作区）

存储怎么选见上文 **§1.6**。这里只贴接线。一句话：**对话状态进 MySQL 求稳，热文件和短生命周期进 Redis 求快；`MysqlDistributedStore.create()` 不是更高级的写法。**

### `src/main/java/cn/deepassistant/redis/package-info.java`

**作用：** 为什么官方 RemoteFilesystem 必须配分布式 AgentState（后端可以不是 Redis）

```java
/**
 * Redis 生产存储：工作区文件、网页会话、HITL 待审批进同一套 Redis，多副本共享。
 * AgentState（对话上下文）用官方 {@link io.agentscope.extensions.mysql.state.MysqlAgentStateStore}。
 *
 * <ul>
 *   <li>{@link RedisBaseStore} —— MEMORY.md、日流水、压缩卸载 jsonl（RemoteFilesystem）</li>
 *   <li>{@link RedisPendingApprovalStore} —— 写文件审批单，避免打到另一副本丢 pending</li>
 * </ul>
 *
 * 官方要求：{@code filesystem(RemoteFilesystemSpec)} 必须配 {@code DistributedStore}，
 * 否则 {@code HarnessAgent.build()} 抛 IllegalStateException。
 */
package cn.deepassistant.redis;
```

### `io.agentscope.extensions.mysql.state.MysqlAgentStateStore`（官方，`agentscope-extensions-mysql`）

**作用：** AgentState 存 MySQL。本项目只接线，不自研 store。**谁在调：** 几乎全是 AgentScope 框架——`streamEvents` 开头 `get`、回合中/结束后 `save`；本项目删会话时才会 `delete`。

依赖：`io.agentscope:agentscope-extensions-mysql:${agentscope.version}`。文档：<https://java.agentscope.io/v2/en/integration/session/mysql.html>

```java
@Bean
public AgentStateStore mysqlAgentStateStore(DataSource dataSource) {
    // 库名必须和 JDBC URL 一致；官方 SQL 用 `库`.`表` 限定名
    return new MysqlAgentStateStore(dataSource, mysqlDatabase, "agentscope_sessions", true);
}
```

表由官方自动建（`createIfNotExist=true`）：

```sql
CREATE TABLE IF NOT EXISTS agentscope_sessions (
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

`(userId, sessionId)` 打进 `session_id` 列，格式 `{userId}:{sessionId}`。单槽 `item_index=0`；列表槽按行递增。

本项目用 `DistributedStore.builder()` 混搭，**不要** `MysqlDistributedStore.create()`：后者会把 `baseStore` 换成 `JdbcStore`（工作区进 MySQL），并带上用不到的沙箱快照 / `GET_LOCK()`；网页会话和 pending 它也管不到。库名必须显式传入 `agentscope_assistant`，不能用 `create()` 默认的 `agentscope`。

### `src/main/java/cn/deepassistant/mysql/MysqlAgentStateStore.java`（已删除）

原先自研两张表 `agent_state` / `agent_state_list`。已换成上面的官方实现。

### `src/main/java/cn/deepassistant/redis/RedisAgentStateStore.java`（已删除）

原先把 AgentState 放 Redis `as:state:` / `as:list:`。现已换成官方 MySQL store。工作区 / 网页会话 / pending 仍走 Redis。

### `src/main/java/cn/deepassistant/redis/RedisBaseStore.java`

**作用：** 工作区文件存 Redis Hash，Lua 做版本 CAS。**谁在调：** 官方 `RemoteFilesystem`（读 MEMORY.md、write/edit、Flush、Consolidation、压缩卸载 jsonl）；本项目档案页经 `WorkspaceManager` 间接到达。

```java
package cn.deepassistant.redis;

/**
 * Redis 版 {@link BaseStore}：给官方 {@code RemoteFilesystem} 当 KV 后端。
 *
 * <p>多副本时工作区文件（MEMORY.md、memory/日流水、sessions/*.log.jsonl）不再落本地磁盘，
 * 而是存进 Redis，所有副本共享同一份。
 *
 * <h3>Key 设计</h3>
 * <pre>
 *   as:base:{namespaceJoined}/{fileKey}   →  Redis Hash { value: &lt;JSON&gt;, version: &lt;long&gt; }
 * </pre>
 * namespace 是 {@link io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory#getNamespace} 的返回，
 * {@code IsolationScope.USER} 下就是 {@code ["alice"]}，join 成 {@code alice}。
 * fileKey 是工作区相对路径，如 {@code MEMORY.md}、{@code memory/2024-01-01.md}。
 * 所以最终 key 形如 {@code as:base:alice/MEMORY.md}。
 *
 * <h3>version / 乐观锁</h3>
 * 用 Hash 的 {@code version} 字段做 {@link #putIfVersion} 乐观锁，靠 Lua 脚本保证原子。
 * {@link #put} 每次写都 {@code version+1}。
 *
 * <p>value 是 {@link StoreItem#value()}（{@code Map<String, Object>}）的 JSON，
 * 官方 {@code RemoteFilesystem.fileDataToStoreValue} 负责在 FileData ↔ Map 之间转换，
 * 本类只管忠实存取 Map，不关心里面是什么。
 *
 * <h3>谁在调（几乎全是 AgentScope {@code RemoteFilesystem}）</h3>
 * Harness 配了 {@code RemoteFilesystemSpec} 后，工作区读写不再碰本地磁盘，全部变成对本接口的 KV。
 * 本项目从不直接 {@code redisBaseStore.get/put}，而是框架在这些时机回调：
 * <ul>
 *   <li>每轮推理开头 {@code WorkspaceContextMiddleware} 读 {@code MEMORY.md} / {@code AGENTS.md} 注入 system prompt</li>
 *   <li>模型调 {@code read_file}/{@code write_file}/{@code edit_file}/{@code ls}/{@code grep}/{@code glob}</li>
 *   <li>流结束后 {@code MemoryFlushMiddleware} 追加 {@code memory/YYYY-MM-DD.md}</li>
 *   <li>周期性 {@code MemoryConsolidator} 重写 {@code MEMORY.md}</li>
 *   <li>上下文压缩 {@code CompactionMiddleware} 把卸下来的原文写成 {@code sessions/*.log.jsonl}</li>
 *   <li>本项目档案页 / {@code get_user_usage} 经 {@code WorkspaceManager} 间接触达（仍是框架文件系统）</li>
 * </ul>
 */
@Slf4j
public class RedisBaseStore implements BaseStore {

    private static final String PREFIX = "as:base:";

    private final RedisCommands<String, String> redis;

    public RedisBaseStore(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    /** namespace 段用 / 连接，再拼上 fileKey，加全局前缀。 */
    private static String redisKey(List<String> namespace, String key) {
        String ns = String.join("/", namespace);
        return PREFIX + ns + "/" + key;
    }

    /** search 用的前缀：as:base:{namespace}/。 */
    private static String scanPrefix(List<String> namespace) {
        return PREFIX + String.join("/", namespace) + "/";
    }

    /**
     * 读一个工作区文件对应的 Hash。没有 key 返回 null，官方 RemoteFilesystem 会当成文件不存在。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.read}/{@code exists}，以及
     * {@code WorkspaceManager.readMemoryMd} / {@code readManagedWorkspaceFileUtf8}。
     * 典型时机：每轮 chat 开头注入 MEMORY.md；模型 {@code read_file}；Flush/Consolidation 先读再写。
     */
    @Override
    public StoreItem get(List<String> namespace, String key) {
        String rk = redisKey(namespace, key);
        Map<String, String> fields = redis.hgetall(rk);
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        String valueJson = fields.getOrDefault("value", "");
        long version = parseLong(fields.get("version"), 0L);
        Map<String, Object> value = JsonCodecHolder.fromJsonToMap(valueJson);
        return new StoreItem(key, value, version);
    }

    /**
     * 无条件写入：Lua 里 HINCRBY version + HSET value，一次 eval 保证不会出现「只加了版本没写内容」。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.write} 在「文件尚不存在」时走 put
     * （已存在则官方要求先 read 再 edit）。新建日流水、新建计划、压缩卸载 jsonl 第一次落盘都会进这里。
     */
    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        String rk = redisKey(namespace, key);
        String valueJson = JsonCodecHolder.toJson(value);
        // Lua：version 不存在则置 1，存在则 +1；然后写 value。保证原子。
        String script =
                "local v = redis.call('HINCRBY', KEYS[1], 'version', 1) " +
                "redis.call('HSET', KEYS[1], 'value', ARGV[1]) " +
                "return v";
        redis.eval(script, ScriptOutputType.INTEGER, new String[]{rk}, valueJson);
    }

    /**
     * 乐观锁写入。当前 version 必须等于 {@code expectedVersion} 才提交，否则返回 false 让框架重读再试。
     * 多副本同时 flush MEMORY.md 时靠这个避免互相覆盖。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.write}/{@code edit} 改已有文件时。
     * 官方最多重试 5 次；失败会报 {@code Another writer is concurrently modifying this file}。
     * Consolidation 重写 MEMORY.md、模型 {@code edit_file}、Flush 追加日流水都走这条。
     */
    @Override
    public boolean putIfVersion(List<String> namespace, String key,
                               Map<String, Object> value, long expectedVersion) {
        String rk = redisKey(namespace, key);
        String valueJson = JsonCodecHolder.toJson(value);
        // Lua：读当前 version，匹配 expectedVersion 才 version+1 并写 value，返回 1；否则返回 0。
        String script =
                "local cur = tonumber(redis.call('HGET', KEYS[1], 'version')) or 0 " +
                "if cur == tonumber(ARGV[1]) then " +
                "  redis.call('HINCRBY', KEYS[1], 'version', 1) " +
                "  redis.call('HSET', KEYS[1], 'value', ARGV[2]) " +
                "  return 1 " +
                "else " +
                "  return 0 " +
                "end";
        Long r = redis.eval(script, ScriptOutputType.INTEGER, new String[]{rk},
                String.valueOf(expectedVersion), valueJson);
        return r != null && r == 1L;
    }

    /**
     * 列出某用户命名空间下的文件：SCAN 前缀 → 排序 → 按 offset/limit 切片再 HGETALL。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.ls}/{@code glob}/{@code grep} 的
     * {@code searchAllItems}，以及 {@code WorkspaceManager.listMemoryFilePaths} /
     * {@code listSessionLogFiles}。模型列目录、官方 {@code memory_search}/{@code session_search}、
     * 档案页列日流水都会间接触达。
     */
    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String prefix = scanPrefix(namespace);
        List<String> keys = new ArrayList<>(scanKeys(prefix + "*"));
        keys.sort(Comparator.naturalOrder());
        int from = Math.min(offset, keys.size());
        int to = Math.min(from + limit, keys.size());
        List<String> page = keys.subList(from, to);
        List<StoreItem> items = new ArrayList<>();
        for (String rk : page) {
            Map<String, String> fields = redis.hgetall(rk);
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            String valueJson = fields.getOrDefault("value", "");
            long version = parseLong(fields.get("version"), 0L);
            Map<String, Object> value = JsonCodecHolder.fromJsonToMap(valueJson);
            // itemKey = 去掉前缀 + namespace/，剩下的是 fileKey
            String itemKey = stripPrefix(rk, prefix);
            items.add(new StoreItem(itemKey, value, version));
        }
        return items;
    }

    /**
     * 删单个工作区文件对应的 Hash。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.delete}/{@code move}（move = 读新位置 put + 删旧 key）。
     * 模型调删文件工具、技能迁移 {@code moveSkill} 时会进这里。本项目 HTTP 删会话<b>不会</b>清工作区文件。
     */
    @Override
    public void delete(List<String> namespace, String key) {
        redis.del(redisKey(namespace, key));
    }

    // ---- 工具 ----

    /** {@code as:base:alice/MEMORY.md} 去掉前缀后得到官方要的 fileKey {@code MEMORY.md}。 */
    private static String stripPrefix(String rk, String prefix) {
        return rk.startsWith(prefix) ? rk.substring(prefix.length()) : rk;
    }

    /** Hash 里的 version 转 long；缺字段或非数字用默认值，避免一次脏数据让 search 整页失败。 */
    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** SCAN 匹配，不用 KEYS。 */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        do {
            var scan = redis.scan(cursor, ScanArgs.Builder.matches(pattern).limit(200));
            keys.addAll(scan.getKeys());
            cursor = scan;
        } while (!cursor.isFinished());
        return keys;
    }

    /** 延迟拿官方 JsonCodec，避免类加载早期 init 问题。 */
    private static final class JsonCodecHolder {
        static String toJson(Object o) {
            return io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(o);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> fromJsonToMap(String json) {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            try {
                return io.agentscope.core.util.JsonUtils.getJsonCodec()
                        .fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception e) {
                log.warn("[RedisBaseStore] value 反序列化失败: {}", e.getMessage());
                return Map.of();
            }
        }
    }
}
```

### `src/main/java/cn/deepassistant/redis/RedisPendingApprovalStore.java`

**作用：** HITL 审批单跨副本。写文件 ASK 之后必须进 Redis，用户点批准可能打到另一台机器。

```java
package cn.deepassistant.redis;

/**
 * HITL 待审批跨副本共享。key = {@code as:pending:{userId}:{sessionId}}，TTL 1 小时。
 *
 * <p>写文件工具触发 ASK 后，审批单必须进 Redis：用户点「批准」可能打到另一台副本，
 * 那台机器内存里没有当初的 {@link ToolUseBlock}。TTL 防止用户关掉页面后 key 永久残留。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPendingApprovalStore {

    private static final String PREFIX = "as:pending:";
    /** 一小时没人点批准/拒绝就作废，避免幽灵审批。 */
    private static final long TTL_SECONDS = 3600;

    private final RedisCommands<String, String> redis;
    private final ObjectMapper mapper;

    private static String key(String userId, String sessionId) {
        return PREFIX + userId + ":" + sessionId;
    }

    /**
     * 覆盖写入待审批工具列表，并刷新 TTL。序列化失败直接抛，宁可本轮失败也不能丢审批单。
     *
     * <p><b>何时调用：</b>本项目 {@code AssistantChatService.run} 收到框架
     * {@code RequireUserConfirmEvent} 之后。AgentScope 自己不写这个 Redis key。
     */
    public void put(String userId, String sessionId, List<ToolUseBlock> toolCalls) {
        try {
            redis.setex(key(userId, sessionId), TTL_SECONDS, mapper.writeValueAsString(toolCalls));
        } catch (Exception e) {
            throw new IllegalStateException("写入待审批失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读待审批列表。没有 key 或 JSON 坏了返回 null，调用方当成「当前没有待审批」。
     *
     * <p><b>何时调用：</b>{@code POST /api/assistant/resume} 组 {@code ConfirmResult} 之前。
     */
    public List<ToolUseBlock> get(String userId, String sessionId) {
        String json = redis.get(key(userId, sessionId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("读取待审批失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** Redis EXISTS：前端打开历史会话时用来决定要不要显示批准按钮。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions/{id}}、resume 入口校验。
     */
    public boolean exists(String userId, String sessionId) {
        Long n = redis.exists(key(userId, sessionId));
        return n != null && n > 0;
    }

    /** 对话正常结束、拒绝跑完、或删除会话时清掉审批单。
     *
     * <p><b>何时调用：</b>{@code AssistantChatService.run} 正常收尾；{@code clearSessionMemory}。
     */
    public void remove(String userId, String sessionId) {
        redis.del(key(userId, sessionId));
    }
}
```

## 四、记忆、会话与多用户隔离

### `src/main/java/cn/deepassistant/memory/package-info.java`

**作用：** 三层记忆心智模型：不要再自己写抽取器去覆盖 MEMORY.md

```java
/**
 * 记忆分三层，对应 AgentScope Java 2.0 官方文档，而不是原 LangGraph 项目自己的 MemoryStore。
 *
 * <pre>
 *  ① 短期：当前会话 messages（MySQL AgentState）+ 本项目 SessionStore（网页侧栏，Redis as:web:）
 *  ② 中期日流水：memory/YYYY-MM-DD.md（RemoteFilesystem → Redis）
 *       由官方 MemoryFlushMiddleware 在每次 call 结束后后台抽取（只追加、不去重）
 *  ③ 长期策划：MEMORY.md（同上）
 *       由官方 MemoryConsolidator 周期性合并日流水、去重后整体重写
 *       每一轮推理都会注入 system prompt
 *
 *  压缩 Compaction：上下文太长时摘要前缀、保留尾部；压缩前会再 flush 一次
 *  原文卸载：压缩掉的消息写入 sessions/*.log.jsonl（Redis 工作区），官方 session_search 能搜到
 * </pre>
 *
 * 本包里的 Java 类只做两件事：<b>读</b>官方已经写到 Redis 工作区的记忆文件，以及检索网页会话 JSON。
 * 不要再另写一套抽取器去覆盖 MEMORY.md，否则会和 MemoryConsolidator 抢文件。
 */
package cn.deepassistant.memory;
```

### `src/main/java/cn/deepassistant/memory/HarnessMemoryCatalog.java`

**作用：** 只读官方 WorkspaceManager；用 ObjectProvider 解开循环依赖

```java
package cn.deepassistant.memory;

/**
 * 只读目录：把官方 Harness 已经写到 Redis 工作区的记忆文件读出来给前端 / 工具用。
 *
 * <h3>多用户隔离</h3>
 * HarnessAgent 配了 {@code IsolationScope.USER}，框架的 {@link WorkspaceManager} 会按
 * {@code RuntimeContext.userId} 把 {@code memory/} 和 {@code MEMORY.md} 路由到
 * 该用户的命名空间下。本类不自己拼路径，而是调
 * {@link WorkspaceManager#readMemoryMd(RuntimeContext)} /
 * {@link WorkspaceManager#listMemoryFilePaths(RuntimeContext)} /
 * {@link WorkspaceManager#readManagedWorkspaceFileUtf8(RuntimeContext, String)}，
 * 保证和框架写入的路径完全一致（RemoteFilesystem / Redis）。
 *
 * <h3>为什么用 {@link ObjectProvider} 而不是直接注入 {@link HarnessAgent}</h3>
 * 存在循环依赖：{@code harnessAgent (bean) → historyMemoryTools → harnessMemoryCatalog → harnessAgent}。
 * 直接构造注入会在 Spring 启动时死锁。{@code ObjectProvider<HarnessAgent>} 注入的是「延迟解析器」，
 * 真正调 {@code getObject()} 时（HTTP 请求进来时）harnessAgent 早已建好，环就断了。
 * 不能用 {@code @Lazy}，因为 {@link HarnessAgent} 没有可见构造器，CGLIB 代理不了。
 *
 * <p>写文件的是框架，不是这个类：
 * <ul>
 *   <li>{@code MemoryFlushManager} → {@code memory/YYYY-MM-DD.md}</li>
 *   <li>{@code MemoryConsolidator} → {@code MEMORY.md}</li>
 * </ul>
 */
@Slf4j
@Component
public class HarnessMemoryCatalog {

    private final ObjectProvider<HarnessAgent> harnessAgentProvider;
    private volatile WorkspaceManager workspaceManager;

    public HarnessMemoryCatalog(ObjectProvider<HarnessAgent> harnessAgentProvider) {
        this.harnessAgentProvider = harnessAgentProvider;
    }

    /** 首次调用时从 harnessAgent 拿 WorkspaceManager，之后缓存。
     *
     * <p><b>何时调用：</b>本类第一次读记忆文件时（档案页或 {@code get_user_usage} 工具）。
     * 不能在构造期调，否则和 HarnessAgent Bean 循环依赖。
     */
    private WorkspaceManager workspaceManager() {
        WorkspaceManager wm = workspaceManager;
        if (wm == null) {
            synchronized (this) {
                wm = workspaceManager;
                if (wm == null) {
                    wm = harnessAgentProvider.getObject().getWorkspaceManager();
                    workspaceManager = wm;
                    log.info("[HarnessMemory] 拿到官方 WorkspaceManager workspace={}", wm.getWorkspace());
                }
            }
        }
        return wm;
    }

    /**
     * 读指定用户的 MEMORY.md。
     *
     * <p><b>何时调用：</b>{@code GET /api/memory}、工具 {@code get_user_usage}。
     * 内部是官方 {@code WorkspaceManager.readMemoryMd}，会再进 {@code RemoteFilesystem} → RedisBaseStore.get。
     * 写 MEMORY.md 的是框架 Consolidator，不是这个方法。
     */
    public String readMemoryMarkdown(String userId) {
        String uid = UserIds.normalize(userId);
        return workspaceManager().readMemoryMd(runtimeContext(uid));
    }

    /**
     * 列指定用户的日流水文件。用官方 WorkspaceManager 列路径，再逐个读内容。
     *
     * <p><b>何时调用：</b>{@code GET /api/memory}、工具 {@code get_user_usage}。
     * {@code listMemoryFilePaths} 在框架里会 {@code RemoteFilesystem} search。
     */
    public List<DailyMemoryFile> listDailyLedgers(String userId) {
        String uid = UserIds.normalize(userId);
        RuntimeContext ctx = runtimeContext(uid);
        List<String> relPaths = workspaceManager().listMemoryFilePaths(ctx);
        List<DailyMemoryFile> files = new ArrayList<>();
        for (String relPath : relPaths) {
            // listMemoryFilePaths 会把 MEMORY.md 也列进来，只要 memory/ 下的日流水
            if (relPath == null || relPath.isBlank()) {
                continue;
            }
            String p = relPath.startsWith("/") ? relPath.substring(1) : relPath;
            if (!p.startsWith("memory/") || !p.endsWith(".md")) {
                continue;
            }
            String name = p.substring("memory/".length());
            if (name.startsWith(".")) {
                continue;
            }
            String content = workspaceManager().readManagedWorkspaceFileUtf8(ctx, p);
            files.add(DailyMemoryFile.builder()
                    .path("memory/" + name)
                    .lastModified(Instant.now())
                    .content(content == null ? "" : content)
                    .build());
        }
        files.sort(Comparator.comparing(DailyMemoryFile::getPath, Comparator.reverseOrder()));
        return files;
    }

    /**
     * 把指定用户 MEMORY.md 里的 {@code - 条目} 拆成列表，给档案页用。
     * 分类只能从当前小节标题猜，猜不到就叫 {@code fact}。
     *
     * <p><b>何时调用：</b>仅 {@code GET /api/memory}。AgentScope 不调。
     */
    public List<MemoryFact> parseFactsFromMemoryMd(String userId) {
        return parseFacts(readMemoryMarkdown(userId));
    }

    /** 纯函数，方便单测：不碰磁盘。
     *
     * <p><b>何时调用：</b>{@link #parseFactsFromMemoryMd} 以及单测。
     */
    static List<MemoryFact> parseFacts(String md) {
        List<MemoryFact> facts = new ArrayList<>();
        if (md == null || md.isBlank()) {
            return facts;
        }
        String category = "fact";
        for (String rawLine : md.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                category = guessCategory(line.substring(3));
                continue;
            }
            if (!line.startsWith("- ")) {
                continue;
            }
            String content = line.substring(2).trim();
            if (content.isBlank() || content.startsWith("（")) {
                continue;
            }
            facts.add(MemoryFact.builder()
                    .id(UUID.randomUUID().toString())
                    .category(category)
                    .content(content)
                    .confidence(1.0)
                    .build());
        }
        return facts;
    }

    private static String guessCategory(String heading) {
        String h = heading.toLowerCase(Locale.ROOT);
        if (h.contains("偏好") || h.contains("preference")) {
            return "preference";
        }
        if (h.contains("习惯") || h.contains("style")) {
            return "working_style";
        }
        if (h.contains("用户") || h.contains("identity") || h.contains("关于")) {
            return "identity";
        }
        if (h.contains("项目") || h.contains("project") || h.contains("技术")) {
            return "project";
        }
        if (h.contains("约束") || h.contains("constraint")) {
            return "constraint";
        }
        return "fact";
    }

    /**
     * 只带 userId 的 RuntimeContext，给 WorkspaceManager 做 IsolationScope.USER 路由。
     *
     * <p><b>何时调用：</b>本类读记忆时。不需要 sessionId：MEMORY.md 是用户级，不是会话级。
     */
    private static RuntimeContext runtimeContext(String userId) {
        return RuntimeContext.builder()
                .userId(userId)
                .build();
    }
}
```

### `src/main/java/cn/deepassistant/memory/SessionStore.java`

**作用：** 网页侧栏聊天 JSON，Redis `as:web:` / `as:web-index:`，多副本共享。不是磁盘 `data/sessions/`。

```java
package cn.deepassistant.memory;

/**
 * 网页会话库：聊天全文按用户隔离，存 Redis，多副本共享。
 *
 * <pre>
 *   as:web:{userId}:{sessionId}   → SessionDetail JSON
 *   as:web-index:{userId}         → List&lt;SessionSummary&gt; JSON
 * </pre>
 *
 * <p>这和官方 {@code session_search} 搜的 {@code sessions/*.log.jsonl} 不是同一份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStore {

    private static final String SESSION_PREFIX = "as:web:";
    private static final String INDEX_PREFIX = "as:web-index:";

    private final ObjectMapper mapper;
    private final RedisCommands<String, String> redis;

    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> indexLocks = new ConcurrentHashMap<>();

    /** 进程内互斥用的复合键，不是 Redis key。 */
    private static String key(String userId, String sessionId) {
        return userId + "/" + sessionId;
    }

    /** 网页会话全文：{@code as:web:{userId}:{sessionId}}。 */
    private static String sessionKey(String userId, String sessionId) {
        return SESSION_PREFIX + userId + ":" + sessionId;
    }

    /** 该用户侧栏列表：{@code as:web-index:{userId}}。 */
    private static String indexKey(String userId) {
        return INDEX_PREFIX + userId;
    }

    /**
     * 同一 (user, session) 的读写串行化。多副本之间仍靠 Redis，这把锁只防本 JVM 并发写同一条。
     */
    private Object lockFor(String userId, String sessionId) {
        return fileLocks.computeIfAbsent(key(userId, sessionId), k -> new Object());
    }

    /** 同一用户的索引读写串行化，避免 list/upsert 互相覆盖。 */
    private Object indexLockFor(String userId) {
        return indexLocks.computeIfAbsent(userId, k -> new Object());
    }

    /**
     * 侧栏列表：读索引后按 {@code updatedAt} 倒序。索引坏了会得到空列表而不是抛错。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions}；{@link #search}/{@link #usageStats} 内部也会列一遍。
     * AgentScope 不调这个类。
     */
    public List<SessionSummary> listSessions(String userId) {
        String uid = UserIds.normalize(userId);
        synchronized (indexLockFor(uid)) {
            return readIndex(uid).stream()
                    .sorted(Comparator.comparing(SessionSummary::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 按 id 取会话，没有就新建。{@code sessionId} 空则生成 UUID。
     * 标题取首条用户消息前 24 字；消息列表先空着，真正的 user 消息由 {@link #appendMessage} 再写。
     *
     * <p><b>何时调用：</b>{@code POST /api/sessions} 新对话；{@code POST /api/assistant/chat} 确保会话存在。
     */
    public SessionDetail getOrCreate(String userId, String sessionId, String firstUserMessage) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.normalizeOrCreate(sessionId);
        synchronized (lockFor(uid, id)) {
            SessionDetail detail = load(uid, id);
            if (detail == null) {
                Instant now = Instant.now();
                String title = buildTitle(firstUserMessage);
                detail = SessionDetail.builder()
                        .id(id)
                        .title(title)
                        .createdAt(now)
                        .updatedAt(now)
                        .messages(new ArrayList<>())
                        .build();
                persist(uid, detail);
                upsertIndex(uid, SessionSummary.builder()
                        .id(id)
                        .title(title)
                        .preview(previewOf(firstUserMessage))
                        .updatedAt(now)
                        .messageCount(0)
                        .build());
            }
            return detail;
        }
    }

    /** 只读一条会话全文。不存在返回 null，由 Controller 转成「会话不存在」。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions/{id}}；检索/统计内部按摘要再 load。
     */
    public SessionDetail get(String userId, String sessionId) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        synchronized (lockFor(uid, id)) {
            return load(uid, id);
        }
    }

    /**
     * 追加一条消息并刷新索引预览。
     * 该会话的第一条 user 消息会改写标题（侧栏「新对话」点进去后标题跟着首句走）。
     *
     * <p><b>何时调用：</b>{@code AssistantChatService} 在用户消息入库、助手回复入库、审批等待说明入库时。
     * AgentScope 的对话上下文不走这里（那份在 AgentStateStore）。
     */
    public void appendMessage(String userId, String sessionId, ChatMessageRecord record) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        synchronized (lockFor(uid, id)) {
            SessionDetail detail = load(uid, id);
            if (detail == null) {
                detail = getOrCreate(uid, id, record.getContent());
            }
            detail.getMessages().add(record);
            detail.setUpdatedAt(Instant.now());
            // 仅当这是整段历史里的第一条 user 消息时改标题，避免后续提问把标题冲掉
            if ("user".equals(record.getRole())
                    && detail.getMessages().stream().filter(m -> "user".equals(m.getRole())).count() == 1) {
                detail.setTitle(buildTitle(record.getContent()));
            }
            persist(uid, detail);
            upsertIndex(uid, SessionSummary.builder()
                    .id(detail.getId())
                    .title(detail.getTitle())
                    .preview(previewOf(record.getContent()))
                    .updatedAt(detail.getUpdatedAt())
                    .messageCount(detail.getMessages().size())
                    .build());
        }
    }

    /**
     * 在该用户全部网页会话里做大小写不敏感的子串检索。
     * 空白 query 直接空列表；limit 夹在 1～50。命中按匹配次数、再按时间倒序。
     *
     * <p><b>何时调用：</b>{@code GET /api/history/search}；模型工具 {@code search_conversation_history}。
     */
    public List<HistorySearchHit> search(String userId, String query, int limit) {
        String uid = UserIds.normalize(userId);
        String needle = query == null ? "" : query.trim();
        if (needle.isBlank()) {
            return List.of();
        }
        String lower = needle.toLowerCase();
        int cap = Math.min(Math.max(limit, 1), 50);
        List<HistorySearchHit> hits = new ArrayList<>();
        for (SessionSummary summary : listSessions(uid)) {
            SessionDetail detail = get(uid, summary.getId());
            if (detail == null || detail.getMessages() == null) {
                continue;
            }
            for (ChatMessageRecord message : detail.getMessages()) {
                String content = message.getContent();
                if (content == null) {
                    continue;
                }
                int matches = countMatches(content.toLowerCase(), lower);
                if (matches == 0) {
                    continue;
                }
                hits.add(HistorySearchHit.builder()
                        .sessionId(detail.getId())
                        .sessionTitle(detail.getTitle())
                        .role(message.getRole())
                        .timestamp(message.getTimestamp())
                        .snippet(snippetAround(content, needle))
                        .matchCount(matches)
                        .build());
            }
        }
        hits.sort(Comparator
                .comparingInt(HistorySearchHit::getMatchCount).reversed()
                .thenComparing(HistorySearchHit::getTimestamp, Comparator.nullsLast(Comparator.reverseOrder())));
        if (hits.size() > cap) {
            return new ArrayList<>(hits.subList(0, cap));
        }
        return hits;
    }

    /**
     * 档案页用量：扫该用户全部会话现算，不调模型。
     * {@code recentSessions} 取列表前 8 条（列表本身已按更新时间倒序）。
     *
     * <p><b>何时调用：</b>{@code GET /api/memory}；工具 {@code get_user_usage}。
     */
    public UsageStats usageStats(String userId) {
        String uid = UserIds.normalize(userId);
        List<SessionSummary> sessions = listSessions(uid);
        int messages = 0;
        int userMessages = 0;
        Instant first = null;
        Instant last = null;
        for (SessionSummary summary : sessions) {
            SessionDetail detail = get(uid, summary.getId());
            if (detail == null) {
                continue;
            }
            if (detail.getCreatedAt() != null && (first == null || detail.getCreatedAt().isBefore(first))) {
                first = detail.getCreatedAt();
            }
            if (detail.getUpdatedAt() != null && (last == null || detail.getUpdatedAt().isAfter(last))) {
                last = detail.getUpdatedAt();
            }
            if (detail.getMessages() == null) {
                continue;
            }
            messages += detail.getMessages().size();
            for (ChatMessageRecord message : detail.getMessages()) {
                if ("user".equals(message.getRole())) {
                    userMessages++;
                }
            }
        }
        List<SessionSummary> recent = sessions.stream().limit(8).collect(Collectors.toList());
        return UsageStats.builder()
                .sessionCount(sessions.size())
                .messageCount(messages)
                .userMessageCount(userMessages)
                .firstSeenAt(first)
                .lastSeenAt(last)
                .recentSessions(recent)
                .build();
    }

    /**
     * 删会话 JSON 并从索引摘掉。返回值表示索引里是否真有这条（重复删返回 false）。
     *
     * <p><b>何时调用：</b>{@code DELETE /api/sessions/{id}} → {@code AssistantChatService.clearSessionMemory}。
     */
    public boolean delete(String userId, String sessionId) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        synchronized (lockFor(uid, id)) {
            redis.del(sessionKey(uid, id));
            boolean removed;
            synchronized (indexLockFor(uid)) {
                List<SessionSummary> index = readIndex(uid);
                removed = index.removeIf(s -> Objects.equals(s.getId(), id));
                writeIndex(uid, index);
            }
            fileLocks.remove(key(uid, id));
            return removed;
        }
    }

    /** Redis GET + 反序列化。key 不存在或 JSON 坏了都当没有这条会话。 */
    private SessionDetail load(String userId, String sessionId) {
        String json = redis.get(sessionKey(userId, sessionId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, SessionDetail.class);
        } catch (Exception e) {
            log.warn("读取会话失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 整份 SessionDetail 覆盖写回 Redis。序列化失败只打 warn，避免一次坏消息拖死整轮对话。 */
    private void persist(String userId, SessionDetail detail) {
        try {
            redis.set(sessionKey(userId, detail.getId()), mapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("写入会话失败: {}", e.getMessage());
        }
    }

    /** 读侧栏索引；缺失或坏 JSON 当空列表，下次 upsert 会重建。 */
    private List<SessionSummary> readIndex(String userId) {
        String json = redis.get(indexKey(userId));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 覆盖写侧栏索引 JSON。失败只打 warn，会话全文已经 persist 过，索引可下次 upsert 修复。 */
    private void writeIndex(String userId, List<SessionSummary> data) {
        try {
            redis.set(indexKey(userId), mapper.writeValueAsString(data));
        } catch (Exception e) {
            log.warn("写入会话索引失败: {}", e.getMessage());
        }
    }

    /**
     * 按 id 替换或追加一条摘要。先 removeIf 再 add，保证同一会话在索引里只有一行。
     */
    private void upsertIndex(String userId, SessionSummary summary) {
        synchronized (indexLockFor(userId)) {
            List<SessionSummary> data = readIndex(userId);
            data.removeIf(s -> Objects.equals(s.getId(), summary.getId()));
            data.add(summary);
            writeIndex(userId, data);
        }
    }

    /** 侧栏标题：空白 →「新对话」；否则取首行前 24 字。 */
    private static String buildTitle(String message) {
        if (message == null || message.isBlank()) {
            return "新对话";
        }
        String t = message.replace('\n', ' ').trim();
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    /** 索引预览：压成一行，最多 60 字。 */
    private static String previewOf(String content) {
        if (content == null) {
            return "";
        }
        String t = content.replace('\n', ' ').trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    /**
     * 在已转小写的 haystack 里数 needle 出现次数（不重叠）。
     * 包可见方便单测，不走 Redis。
     */
    static int countMatches(String haystackLower, String needleLower) {
        if (haystackLower == null || needleLower == null || needleLower.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystackLower.indexOf(needleLower, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needleLower.length();
        }
    }

    /**
     * 命中处前后各留一点上下文，做成侧栏能扫的摘录。找不到 needle 则退回全文前 120 字。
     */
    static String snippetAround(String content, String needle) {
        String flat = content.replace('\n', ' ').trim();
        int at = flat.toLowerCase().indexOf(needle.toLowerCase());
        if (at < 0) {
            return flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
        }
        int start = Math.max(0, at - 40);
        int end = Math.min(flat.length(), at + needle.length() + 80);
        String snippet = flat.substring(start, end);
        if (start > 0) {
            snippet = "…" + snippet;
        }
        if (end < flat.length()) {
            snippet = snippet + "…";
        }
        return snippet;
    }
}
```

### `src/main/java/cn/deepassistant/service/UserMemoryQueryService.java`

**作用：** 给前端档案页：usage + MEMORY.md + 日流水

```java
package cn.deepassistant.service;

    /**
     * HTTP 门面：历史检索走 SessionStore，档案页读官方 MEMORY.md / 日流水。
     * 所有方法都按 userId 隔离，不同用户互不可见。
     *
     * <p><b>何时调用：</b>只被 {@code AssistantController} 的 GET 接口调。AgentScope 不进这个类。
     */
@Service
public class UserMemoryQueryService {

    private final SessionStore sessionStore;
    private final HarnessMemoryCatalog memoryCatalog;

    public UserMemoryQueryService(SessionStore sessionStore, HarnessMemoryCatalog memoryCatalog) {
        this.sessionStore = sessionStore;
        this.memoryCatalog = memoryCatalog;
    }

    /**
     * 网页会话库关键词检索。
     *
     * <p><b>何时调用：</b>{@code GET /api/history/search}。模型搜历史走 {@code HistoryMemoryTools}，不走这里。
     */
    public HistorySearchResponse searchHistory(String userId, String query, int limit) {
        String uid = UserIds.normalize(userId);
        String q = query == null ? "" : query.trim();
        List<HistorySearchHit> hits = sessionStore.search(uid, q, limit);
        return HistorySearchResponse.builder()
                .query(q)
                .hits(hits)
                .hitCount(hits.size())
                .build();
    }

    /**
     * 档案页一次聚合：用量 + MEMORY.md + 日流水 + 解析出的 facts。
     *
     * <p><b>何时调用：</b>{@code GET /api/memory}。读 MEMORY.md 会间接触达框架 WorkspaceManager / RedisBaseStore。
     */
    public UserMemoryProfile profile(String userId) {
        String uid = UserIds.normalize(userId);
        return UserMemoryProfile.builder()
                .usage(sessionStore.usageStats(uid))
                .memoryMarkdown(memoryCatalog.readMemoryMarkdown(uid))
                .dailyLedgers(memoryCatalog.listDailyLedgers(uid))
                .facts(memoryCatalog.parseFactsFromMemoryMd(uid))
                .build();
    }
}
```

### `src/main/java/cn/deepassistant/util/UserIds.java`

**作用：** userId 校验；空白回退 local；挡住路径穿越

```java
package cn.deepassistant.util;

/**
 * userId 校验，和 {@link SessionIds} 用同一套安全规则：
 * 只允许 {@code [a-zA-Z0-9][a-zA-Z0-9_-]{0,127}}，禁止路径穿越。
 *
 * <p>多用户隔离靠 (userId, sessionId) 二元组寻址，userId 会直接变成磁盘目录名，
 * 所以必须挡住 {@code ..} / {@code /} / {@code \\} / {@code \0}。
 */
public final class UserIds {

    private static final Pattern SAFE =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$");

    /** 请求没带 userId 时的默认值。 */
    public static final String DEFAULT_USER_ID = "local";

    private UserIds() {
    }

    /**
     * 校验 userId，空白时回退到 {@link #DEFAULT_USER_ID}。
     * 这样前端不传 userId 也能跑，等价于原来的单用户模式。
     *
     * <p><b>何时调用：</b>几乎所有 HTTP 入口和工具里取当前用户时。AgentScope 用的是
     * {@code RuntimeContext.userId}，不会调这个方法；我们在进框架前先 normalize 再放进 Context。
     */
    public static String normalize(String userId) {
        if (userId == null || userId.isBlank()) {
            return DEFAULT_USER_ID;
        }
        return requireValid(userId);
    }

    /**
     * 强制校验：空白也报错，不回退到 {@code local}。
     * 给「必须明确知道是哪个用户」的内部调用预留；HTTP 入口一般用 {@link #normalize}。
     */
    public static String requireValid(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 为空");
        }
        String id = userId.trim();
        // 先挡路径穿越，再套白名单，避免奇怪 unicode 绕过正则
        if (id.contains("..") || id.contains("/") || id.contains("\\") || id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法 userId");
        }
        if (!SAFE.matcher(id).matches()) {
            throw new IllegalArgumentException("非法 userId 格式");
        }
        return id;
    }
}
```

### `src/main/java/cn/deepassistant/util/SessionIds.java`

**作用：** sessionId 校验 / 空白则新建 UUID

```java
package cn.deepassistant.util;

/**
 * sessionId 校验与生成。规则与 {@link UserIds} 相同：只允许字母数字开头，后跟字母数字、下划线、短横，
 * 最长 128，禁止 {@code ..} / {@code /} / {@code \\} / {@code \0}。
 *
 * <p>id 会拼进 Redis key（{@code as:web:}）和官方 MySQL 槽位
 * {@code {userId}:{sessionId}}，必须挡住路径穿越和分隔符注入。
 */
public final class SessionIds {

    private static final Pattern SAFE =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$");

    private SessionIds() {
    }

    /** 新会话 id：标准 UUID 字符串，符合 {@link #SAFE}。 */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 校验已有 sessionId。空白或格式非法抛 {@link IllegalArgumentException}，
     * 由 Controller 转成 SSE error 或 HTTP 400。
     */
    public static String requireValid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 为空");
        }
        String id = sessionId.trim();
        if (id.contains("..") || id.contains("/") || id.contains("\\") || id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法 sessionId");
        }
        if (!SAFE.matcher(id).matches()) {
            throw new IllegalArgumentException("非法 sessionId 格式");
        }
        return id;
    }

    /**
     * 聊天入口专用：没带 id 就现场生成；带了就校验。
     * 续跑 / 打开历史必须走 {@link #requireValid}，不能悄悄新建一条对不上的会话。
     *
     * <p><b>何时调用：</b>{@code POST /api/assistant/chat}、{@code SessionStore.getOrCreate}。
     * AgentScope 不生成网页 sessionId，只用我们放进 {@code RuntimeContext.sessionId} 的值。
     */
    public static String normalizeOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return newId();
        }
        return requireValid(sessionId);
    }
}
```
---

**下篇：** [小深-AgentScope-Java-2.0-Harness架构博客-02.md](./小深-AgentScope-Java-2.0-Harness架构博客-02.md) — 工具、官方 tools.json MCP、对话 SSE、前端与踩坑笔记。
