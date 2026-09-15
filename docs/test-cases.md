# 小深助手测试用例

项目目录：`agentscope-assistant-java-2`（Maven artifact 可能仍是 `agentscope-assistant-java`）  
基线：已有自动化用例（记忆解析、会话关键词、`tools.json` MCP 声明、Redis 工作区、MySQL AgentState）。  
应用默认：`http://localhost:8089`  
文档对齐：2026-09-15 — 子 Agent 官方扫 `workspace/subagents/*.md`；聊天 SSE 为 Flux + `RedisSessionRunLock`；`agent.max-iters=25`。

| 状态 | 含义 |
|------|------|
| 已覆盖 | 已有自动化测试 |
| 待补 | 建议写成单测 / MockMvc |
| 手工 | 需连大模型或浏览器 |

跑法：

- 单测：`mvn test`
- HTTP：应用起来后用 curl
- 对话 / 前端：浏览器打开首页，侧栏填用户
- 应用启动前必须有 **MySQL**（库 `agentscope_assistant`，utf8mb4）和 **Redis**（默认 `127.0.0.1:6379`）；不再有单机 JSON 模式 / `distributed` profile

Redis 集成测试只 FLUSHDB **db=15**，不会清应用默认的 db=0。MySQL 测例只用 **`agentscope_assistant_test`**。对话类依赖百炼额度，没钱时先看 C-14。

---

## 1. 单测

| ID | 状态 | 场景 | 步骤 | 期望 |
|----|------|------|------|------|
| U-01 | 待补 | userId 空白回退 local | `UserIds.normalize(null / "" / "  ")` | 返回 `local`，不抛异常 |
| U-02 | 待补 | userId 挡住路径穿越 | `normalize("../etc")`、`alice/bob`、反斜杠、空字节、中文、超长串 | `IllegalArgumentException`（非法 userId / 格式） |
| U-03 | 待补 | 合法 userId 原样保留 | `normalize("Alice_01-x")` | 原样返回；允许字母数字 `_` `-`，最长 128 |
| U-04 | 待补 | sessionId 空白则新建 | `SessionIds.normalizeOrCreate(null)` | 生成 UUID 形态的安全 id |
| U-05 | 待补 | sessionId 非法拒绝 | `requireValid("../x")`、空白 | 抛「非法 sessionId」或「sessionId 为空」 |
| U-06 | 待补 | 计算器正常表达式 | `SafeMathEval.evalToString("(3+5)*2")`、`"2.5+0.5"` | `16` 和 `3`；整数不带小数点 |
| U-07 | 待补 | 计算器拒绝危险输入 | 空串、`1/0`、`2++2`、`Math.pow(2,3)` | 抛异常，或 calculate 工具返回「计算失败」 |
| U-08 | 待补 | 会话按用户 Redis key 隔离 | 连测试 Redis；alice / bob 各建会话并 append 不同消息 | `listSessions` / `get` / `search` 互不可见；key 为 `as:web:{userId}:{sessionId}` |
| U-09 | 待补 | 删除会话只删自己的 | alice 删 `sess1`；bob 同 id 的会话仍在 | alice 列表空，bob 不受影响；pending approval key 一并清 |
| U-10 | 已覆盖 | 历史关键词计数与摘要 | `SessionStoreSearchTest` | 重叠命中计数正确；snippet 含关键词 |
| U-11 | 已覆盖 | MEMORY.md 事实解析 | `HarnessMemoryCatalogTest` | 标题映射 preference / project；占位行忽略 |
| U-12 | 已覆盖 | 官方 tools.json MCP | `ToolsJsonTest` | 工作区根 `tools.json`：mcpServers 为 zhipu-web-search / zhipu-web-reader；Java MCP SDK 用智谱文档的 SSE 回退（`/sse?Authorization=`）；deny 含 web_search；可反序列化为官方 `ToolsConfig` |
| U-13 | 已覆盖 | MDC 写入 SESSION_ID | `ConversationMdcTest` | `open` 后 `%X{SESSION_ID}` 能读到；`run` 抛异常也 clear |
| U-14 | 已覆盖 | 路径解析 sessionId | `SessionMdcInterceptorTest` | `/api/sessions/{id}` 抽出 id；列表 / 聊天路径为 null |
| U-15 | 待补 | ProcessEnv 写入 getenv | `ProcessEnv.ensure("MCP_API_KEY", v)` | 已有非空 env 不覆盖；空 value 返回 false；写入后 `System.getenv` 非空 |
| U-16 | 已覆盖 | 思考 / 工具事件映射 | `AgentEventMapperTest` | `ModelCallStart` → live `status`；thinking delta 不落库、end 可持久化；工具 start 带 name/id；todo → plan |
| U-17 | 已覆盖 | 活动日志截断与检索词 | `AgentActivityLoggerTest` | clip 超长加字数；args 里的 api-key 打码；webSearchPrime / webReader 抽出 query/url |

---

## 2. HTTP 接口

多用户约定：POST 从 body 的 `userId` 取；GET / DELETE 从 `X-User-Id` 或 `?userId=` 取。不传则回退 `local`。

| ID | 状态 | 场景 | 步骤 | 期望 |
|----|------|------|------|------|
| A-01 | 待补 | 健康检查 | `GET /api/health` | `status=ok`，`storage=mysql+redis`，`multi_replica=true`，`multi_user=true`，`mcp=tools.json`；`mcp_enabled=true` 且 `mcp_tools` 含 `webSearchPrime` / `webReader`（Toolkit 真实 MCP 工具，不是仅密钥已配） |
| A-02 | 待补 | Agent 能力清单 | `GET /api/agents` | 含 `research-agent`；`harness` 为当前 Toolkit 实名，应含 `webSearchPrime` / `webReader` / `todo_write` |
| A-03 | 待补 | 建会话：header / query / 默认用户 | `POST /api/sessions`；分别带 `X-User-Id`、`?userId=`、都不带 | 返回新对话；不带时落到 user=`local` |
| A-04 | 待补 | 列会话只返回本用户 | alice、bob 各 POST 会话后 `GET /api/sessions?userId=alice` | 只有 alice 的会话 |
| A-05 | 待补 | 取不存在会话 | `GET /api/sessions/not-exist?userId=alice` | HTTP 400，error 含「会话不存在」 |
| A-06 | 待补 | 跨用户读会话失败 | alice 建 sess；bob GET 同一 id | 400 会话不存在 |
| A-07 | 待补 | 删除会话 | `DELETE /api/sessions/{id}?userId=alice` 后再 GET | `{ok:true}`；再次 GET 400 |
| A-08 | 待补 | 历史检索空词与隔离 | alice 会话写入「苹果派」；`GET /history/search?q=苹果` 分别带 bob / alice | bob 0 条；alice 命中 |
| A-09 | 待补 | 记忆档案按用户 | `GET /api/memory?userId=alice` 与 bob | usage / MEMORY.md / 日账只含该用户 |
| A-10 | 待补 | 聊天空消息 | `POST /api/assistant/chat` body `{message:""}` | SSE `error`「消息不能为空」，然后 `done` |
| A-11 | 待补 | 聊天非法 userId | `POST chat {userId:"../x", message:"hi"}` | SSE error 非法 userId，不落盘 |
| A-12 | 待补 | 续跑无待审批 | `POST /api/assistant/resume` 且无 pending | SSE error「当前会话没有待审批的操作」 |
| A-13 | 待补 | 聊天首包带 session | `POST chat` 不传 sessionId（可用 mock 模型） | 先 `session` 事件给出新 id，结束有 `done`；会话 JSON 写入对应用户目录 |
| A-14 | 待补 | 同会话互斥 | 同一 session 连续两次 chat（第二轮在第一轮结束前） | 第二轮 SSE error「该会话正在处理中」 |
| A-15 | 待补 | 有 pending 时禁止新聊 | 会话卡在写文件审批后再 chat | SSE error「当前会话有待审批的操作」 |

### curl 示例

空消息：

```bash
curl -N -H 'Content-Type: application/json' \
  -d '{"message":""}' \
  http://localhost:8089/api/assistant/chat
```

两用户隔离：

```bash
curl -X POST http://localhost:8089/api/sessions -H 'X-User-Id: alice'
curl 'http://localhost:8089/api/sessions?userId=bob'
```

bob 的列表不应出现 alice 刚建的会话。

---

## 3. 对话（需连模型）

| ID | 状态 | 场景 | 步骤 | 期望 |
|----|------|------|------|------|
| C-01 | 手工 | 普通问答（真流式） | 对 alice 说一句较长的自我介绍请求 | token 逐字出现，不必等整段结束；侧栏出现会话；刷新后记录还在 |
| C-02 | 手工 | 调用计算器 | 「算一下 (18+7)*3 等于多少，必须用工具」 | 工具条出现 `calculate`；答案 75 |
| C-03 | 手工 | 当前时间 | 「现在几点了，星期几」 | 调用 `getCurrentDateTime`；日期接近本机时间 |
| C-04 | 手工 | 联网搜索（MCP） | 确认 `health.mcp=tools.json`；「搜一下今天杭州天气」 | 调用 `webSearchPrime`；结果有来源/摘要 |
| C-05 | 手工 | 读网页（MCP） | 给一个公开 URL：「打开这个链接总结要点」 | 调用 `webReader`；有正文摘要。MCP 挂了工具未注册或返回错误 |
| C-06 | 手工 | MCP 未配置 | 不设 `MCP_API_KEY` / `mcp.api-key` 且 LLM key 也无法用于智谱，重启后再搜 | 启动日志 warn 注册失败；不能再靠 REST 回退 |
| C-07 | 手工 | 写文件触发人工审批 | 「在工作区写一个 hello.txt，内容是 ping」 | SSE 出审批卡（`write_file` / `edit_file`）；暂停，文件未落盘 |
| C-08 | 手工 | 批准后续跑 | 在 C-07 卡片点批准 | POST resume `approved=true`；文件出现在该用户工作区；会话继续 |
| C-09 | 手工 | 拒绝写文件 | 再要求写文件后点拒绝 | 不写文件；模型说明被拒绝；pending 清除 |
| C-10 | 手工 | 跨会话记忆 | 告诉「我叫小深用户，偏好简洁中文」；新开会话问「我有什么偏好」 | 新会话能答出偏好（等 flush 进 MEMORY.md / 日账后再问） |
| C-11 | 手工 | 多用户记忆不串 | alice 说爱吃川菜；切 bob 问「我爱吃什么」 | bob 不知道川菜；alice 的 MEMORY.md 对 bob 的 `/api/memory` 不可见 |
| C-12 | 手工 | 会话内历史检索 | 先聊「项目代号北冥」；再问代号或侧栏检索 | 能找回原文；`GET /history/search?q=北冥` 命中 |
| C-13 | 手工 | 待办与规划 | 「列一个三步调研计划并写成 todo」 | 出现 `todo_write`；可选 plan 文件在 `workspace/plans/` |
| C-14 | 手工 | 模型额度/鉴权失败 | 用空 key 或耗尽额度账号发一句闲聊 | SSE error；401 时文案含 `LLM_API_KEY`；配额 403 能看到 `insufficient_quota` |

### 对话提示词（复制即用）

| 用例 | 对模型说 |
|------|----------|
| C-02 | 算一下 (18+7)*3，必须调用计算工具，不要心算 |
| C-04 | 用联网搜索查今天杭州天气，列出 2 条来源 |
| C-07 | 在工作区创建 hello.txt，写入一行 ping，不要只口头说 |
| C-10 | 记住：我叫测评用户，回答请用简洁中文。之后新开对话问：我有什么偏好？ |
| C-11 | alice：我爱吃川菜。切换用户 bob：我爱吃什么？ |

---

## 4. 前端

| ID | 状态 | 场景 | 步骤 | 期望 |
|----|------|------|------|------|
| F-01 | 手工 | 切换用户刷新列表 | 侧栏用户框从 `local` 改成 alice 再改 bob | 会话列表切换；`localStorage.pa_userId` 更新 |
| F-02 | 手工 | 刷新保持用户 | 设 alice 后 F5 | 输入框仍是 alice，列表仍是 alice 的会话 |
| F-03 | 手工 | 删除会话按钮 | 侧栏点删除 | 调用 DELETE 带 userId；条目消失；当前窗清空 |
| F-04 | 手工 | 审批卡按钮 | 触发写文件后点批准/拒绝 | 卡片 resolved；resume 带同一 userId / sessionId |
| F-05 | 手工 | 档案与检索面板 | 打开记忆/历史检索，输入关键词 | 请求带 userId；结果只属当前用户 |
| F-06 | 手工 | 思考与工具活动流 | 发一句闲聊，再强制调用计算/搜索 | 发送后立刻出现「思考中」动效；有工具时出现可折叠步骤（搜索/计算），完成后打勾；最终答案在下方气泡。刷新后思考原文与工具步骤仍在 |

---

## 5. Redis / MySQL 多副本

| ID | 状态 | 场景 | 步骤 | 期望 |
|----|------|------|------|------|
| D-01 | 已覆盖 | MySQL AgentState 读写隔离 | `MysqlAgentStateStoreTest`（需本机 3306） | alice/sess1 与 bob 隔离；delete / list 正确 |
| D-02 | 已覆盖 | Redis 工作区 CAS | `RedisStoresIntegrationTest` BaseStore `put` / `putIfVersion` | 版本冲突失败；search 分页 |
| D-03 | 手工 | 默认启动 MySQL+Redis | 不设 profile；`GET /api/health` | `storage=mysql+redis`；聊天后 MySQL `agentscope_sessions` 有行，Redis 出现 `as:base:` / `as:web:` 键 |
| D-04 | 手工 | 两副本共享会话 | 两进程不同 `server.port`，同一 MySQL+Redis；A 聊完 B 用同 userId+sessionId 续 | B 能读到 AgentState / 工作区 |

---

## 建议补自动化的顺序

| 优先级 | 用例 | 建议落点 |
|--------|------|----------|
| P0 | U-01～U-05 | `UserIdsTest` / `SessionIdsTest` |
| P0 | U-08、U-09、A-04、A-06 | `SessionStore` Redis + Controller MockMvc |
| P0 | A-10、A-11、A-12 | `AssistantController` 空消息 / 非法 id / 无 pending |
| P1 | U-06、U-07 | `SafeMathEvalTest` |
| P1 | A-01、A-03、A-07 | health / 建删会话 |
| P2 | C / F / D-03 | 手工回归，或以后再上假模型 |
