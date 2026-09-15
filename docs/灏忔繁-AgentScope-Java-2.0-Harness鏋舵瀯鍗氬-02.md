# 小深：用 AgentScope Java 2.0 Harness 做私人助手（下）

> 本篇是系列 **下篇**：工具、官方 tools.json MCP、对话 SSE 与审批续跑、DTO、配置与前端、设计取舍。  
> 上篇：[小深-AgentScope-Java-2.0-Harness架构博客-01.md](./小深-AgentScope-Java-2.0-Harness架构博客-01.md)  
> 项目目录：`agentscope-assistant-java-2` · 技术栈：**Spring Boot 3.2.5 + AgentScope Java 2.0.1 `HarnessAgent` + MCP SDK 0.17.0**  
> 包名：`cn.deepassistant` · 端口 **8089**

## 与仓库对齐（2026-09-15）

下文大量「整文件粘贴」来自早期定稿，**学习请打开仓库源码对照**。相对旧稿的关键差异：

| 主题 | 当前仓库 |
|------|----------|
| 子 Agent | 官方自动加载 `workspace/subagents/*.md`，Java **不** `.subagents(...)` |
| research-agent | `isolated` + `maxIters: 25` + ephemeral（不写 MEMORY / 日流水） |
| SSE | `AssistantChatService.chat/resume` 返回 `Flux<SseEvent>`，订阅 `streamEvents`，**禁止** `toIterable()` |
| 同会话互斥 | `RedisSessionRunLock`；忙则 SSE `error`「该会话正在处理中」 |
| 审批续跑 | `pendingApprovals.claim` 后再带 confirm metadata；抢锁失败会写回审批单 |
| 配置 | `agent.max-iters: 25`，`llm.model: qwen3.8-max`，`auto-allow-tools: webReader,webSearchPrime` |
| MCP | `workspace/tools.json` SSE；`build()` 前 `McpServerRegistrar` + 强制 MCP 只读 |

对照：[README](../README.md) · [上篇](./小深-AgentScope-Java-2.0-Harness架构博客-01.md) · [测试用例](./test-cases.md)

Java 源码为便于发布已**省略 `import` 行**，落盘后请对照仓库或用 IDE 补全。

---

## 2. 模块详解与源码（续）

承接上篇第四节。下面从领域工具开始，一路看到产品层与前端。

## 五、工具

### `src/main/java/cn/deepassistant/tool/CommonTools.java`

**作用：** 时间与安全四则运算（@Tool）

```java
package cn.deepassistant.tool;

/**
 * 注册进 Harness {@code Toolkit} 的通用工具。HTTP 接口不会直接调这些方法。
 *
 * <p><b>何时调用（框架）：</b>大模型在 ReAct 循环里决定用某个工具后，
 * {@code io.agentscope.core.tool.ToolExecutor#callTool} 按工具名找到
 * {@code ReflectiveFunctionTool}，再反射调用下面带 {@code @Tool} 的方法。
 * 只在 {@code harnessAgent.streamEvents} 进行中发生。
 */
@Component
public class CommonTools {

    /**
     * 返回本机当前日期时间（中文星期）。
     *
     * <p><b>何时调用（框架）：</b>模型认为需要「现在几点/今天星期几」时发出 {@code getCurrentDateTime} 工具调用。
     */
    @Tool(name = "getCurrentDateTime", description = "获取当前精确日期、时间与星期。",
            readOnly = true, concurrencySafe = true)
    public String getCurrentDateTime() {
        LocalDateTime now = LocalDateTime.now();
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
        return now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")) + "，" + weekday;
    }

    /**
     * 安全求值四则运算（无 ScriptEngine）。
     *
     * <p><b>何时调用（框架）：</b>模型发出 {@code calculate}，参数 {@code expression} 由框架从 ToolUseBlock 填入。
     */
    @Tool(name = "calculate", description = "计算数学表达式，支持加减乘除与括号。",
            readOnly = true, concurrencySafe = true)
    public String calculate(
            @ToolParam(name = "expression", description = "数学表达式，如 (3+5)*2")
            String expression) {
        try {
            return expression + " = " + SafeMathEval.evalToString(expression);
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        }
    }
}
```

### 联网：`workspace/tools.json`（官方 MCP）

**作用：** 工作区根目录 `tools.json` 声明 `mcpServers` + `deny`。`getenv` 已有 `MCP_API_KEY` 时，Harness `build()` 走官方 `ToolsConfigLoader`（远端 overlay 上层 + 本地模板下层）。JDK 17 往往写不进 getenv，此时用 yml `mcp.api-key` 做与官方相同的占位符替换再 `builder.toolsConfig()`。不要 `disableToolsConfig()`，也不要用 `allow` 白名单（会把 `read_file` / `agent_spawn` 砍掉）。

智谱 `/mcp` 是 Streamable HTTP，只接受 POST。Java MCP SDK 0.17 初始化后还会 GET SSE，智谱返回 405（`Session not found`）。因此按[智谱文档](https://docs.bigmodel.cn/cn/coding-plan/mcp/search-mcp-server)给旧客户端的配置走 `/sse?Authorization=`。模型看到的工具名仍是 MCP 原名：`webSearchPrime`、`webReader`（2.0.1 不自动加 `mcp__` 前缀）。密钥必须是智谱 API Key，不要用百炼 Key。

完整 JSON 见下文 `workspace/tools.json`。

### `src/main/java/cn/deepassistant/tool/HistoryMemoryTools.java`

**作用：** 搜网页会话 JSON；官方 memory_search / session_search 不要重复造

```java
package cn.deepassistant.tool;

/**
 * 给主 Agent 用的「本项目聊天记录」检索。
 *
 * <p>多用户隔离：工具方法第一个参数是 {@link RuntimeContext}，框架自动注入。
 * 从 {@code ctx.getUserId()} 取出当前用户，再传给 {@link SessionStore} / {@link HarnessMemoryCatalog}。
 *
 * <p>官方已经注册了这些工具，不要重复造：
 * <ul>
 *   <li>{@code memory_search} / {@code memory_get} / {@code memory_save} —— 搜/读/写 MEMORY.md 与日流水</li>
 *   <li>{@code session_search} —— 搜工作区 {@code sessions/*.log.jsonl}（压缩前卸下来的原文）</li>
 * </ul>
 *
 * 网页上用户看到的对话存在 Redis {@code as:web:{userId}:{sessionId}}，和 jsonl 不是同一份。
 * 这个工具专门搜那份会话 JSON，回答「我在这个聊天页里上次说过什么」。
 *
 * <p><b>何时调用（框架）：</b>{@code Toolkit.registerTool(historyMemoryTools)} 之后，
 * 模型在对话中点名 {@code search_conversation_history} / {@code get_user_usage}，
 * {@code ToolExecutor} 反射进来。第一个参数 {@link RuntimeContext} 由框架注入，不是模型填的。
 */
@Component
public class HistoryMemoryTools {

    private final SessionStore sessionStore;
    private final HarnessMemoryCatalog memoryCatalog;

    public HistoryMemoryTools(SessionStore sessionStore, HarnessMemoryCatalog memoryCatalog) {
        this.sessionStore = sessionStore;
        this.memoryCatalog = memoryCatalog;
    }

    /**
     * 搜网页会话库原文。
     *
     * <p><b>何时调用（框架）：</b>模型发出 {@code search_conversation_history}。
     * 档案页搜索走 HTTP {@code GET /api/history/search}，不走这个方法（那条走 {@code UserMemoryQueryService}）。
     */
    @Tool(name = "search_conversation_history",
            description = "在网页会话库（用户在界面里看到的那些对话）里按关键词搜原文。"
                    + "搜跨会话沉淀事实请用官方 memory_search；搜压缩前卸载日志请用 session_search。",
            readOnly = true, concurrencySafe = true)
    public String searchConversationHistory(
            RuntimeContext ctx,
            @ToolParam(name = "query", description = "关键词，尽量短")
            String query,
            @ToolParam(name = "limit", description = "最多几条，默认 8")
            Integer limit) {
        if (query == null || query.isBlank()) {
            return "请提供搜索关键词。";
        }
        String userId = UserIds.normalize(ctx.getUserId());
        List<HistorySearchHit> hits = sessionStore.search(userId, query.trim(), limit == null ? 8 : limit);
        if (hits.isEmpty()) {
            return "网页会话库里没有「" + query.trim() + "」。可以再试 memory_search 或 session_search。";
        }
        StringBuilder sb = new StringBuilder("网页会话库命中 ").append(hits.size()).append(" 条：\n");
        for (HistorySearchHit hit : hits) {
            sb.append("- [").append(hit.getSessionTitle()).append("] (")
                    .append(hit.getRole()).append(") ")
                    .append(hit.getSnippet()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 当前用户用量 + MEMORY.md 摘要。
     *
     * <p><b>何时调用（框架）：</b>模型发出 {@code get_user_usage}。
     * 档案页 {@code GET /api/memory} 不走这个方法。
     */
    @Tool(name = "get_user_usage",
            description = "查看当前用户的使用概况（会话数、消息数）以及当前 MEMORY.md / 日流水摘要。",
            readOnly = true, concurrencySafe = true)
    public String getUserUsage(RuntimeContext ctx) {
        String userId = UserIds.normalize(ctx.getUserId());
        UsageStats usage = sessionStore.usageStats(userId);
        StringBuilder sb = new StringBuilder();
        sb.append("使用概况：").append(usage.getSessionCount()).append(" 个网页会话，")
                .append(usage.getUserMessageCount()).append(" 条用户消息");
        if (usage.getLastSeenAt() != null) {
            sb.append("，最近活跃 ").append(usage.getLastSeenAt());
        }
        sb.append("。\n\n长期记忆 MEMORY.md：\n");
        String md = memoryCatalog.readMemoryMarkdown(userId);
        sb.append(md.isBlank() ? "（尚无，等首轮 Flush/Consolidation）\n" : md).append("\n");
        List<DailyMemoryFile> daily = memoryCatalog.listDailyLedgers(userId);
        if (!daily.isEmpty()) {
            sb.append("日流水文件：");
            for (DailyMemoryFile file : daily) {
                sb.append(file.getPath()).append(" ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
```

### `src/main/java/cn/deepassistant/util/SafeMathEval.java`

**作用：** 递归下降计算器，拒绝任意代码执行

```java
package cn.deepassistant.util;

/**
 * 只支持 {@code + - * / ()} 和小数的表达式求值，给 {@code calculate} 工具用。
 * 不用 {@code ScriptEngine} / 反射，避免模型把任意 Java 表达式塞进来。
 *
 * <p>文法：expression → term {(+|-) term}*；term → factor {(*|/) factor}*；
 * factor → +factor | -factor | (expression) | number。
 */
public final class SafeMathEval {

    private SafeMathEval() {
    }

    /**
     * 求值成 double。空白抛错；除以零在 {@link Parser#parseTerm()} 里单独拦。
     *
     * <p><b>何时调用：</b>{@link #evalToString}；单测。框架不直接调。
     */
    public static double eval(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式为空");
        }
        // 去掉空白后再解析，"( 1 + 2 )" 和 "(1+2)" 等价
        return new Parser(expression.replaceAll("\\s+", "")).parse();
    }

    /**
     * 给人看的结果字符串：能表示成整数就去掉小数点；NaN / Inf 当成非法（例如 0/0）。
     *
     * <p><b>何时调用（框架间接）：</b>{@code CommonTools.calculate} ← {@code ToolExecutor}。
     */
    public static String evalToString(String expression) {
        double v = eval(expression);
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new ArithmeticException("计算结果非法（可能除以零）");
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /** 递归下降解析器，一次扫完整个去掉空白的表达式。 */
    private static final class Parser {
        private final String s;
        /** 当前字符下标；{@link #next()} 会先 ++ 再读。 */
        private int pos = -1;
        /** 当前字符的码点；扫完后为 -1。 */
        private int ch;

        Parser(String s) {
            this.s = s;
        }

        void next() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

        /** 若当前字符是 c 则吃掉并返回 true。 */
        boolean eat(int c) {
            if (ch == c) {
                next();
                return true;
            }
            return false;
        }

        double parse() {
            next();
            double x = parseExpression();
            if (pos < s.length()) {
                throw new IllegalArgumentException("意外字符: " + (char) ch);
            }
            return x;
        }

        /** 加减，优先级最低。 */
        double parseExpression() {
            double x = parseTerm();
            for (; ; ) {
                if (eat('+')) {
                    x += parseTerm();
                } else if (eat('-')) {
                    x -= parseTerm();
                } else {
                    return x;
                }
            }
        }

        /** 乘除。除数为 0 立刻抛，不让 Inf 漏到外层才发现。 */
        double parseTerm() {
            double x = parseFactor();
            for (; ; ) {
                if (eat('*')) {
                    x *= parseFactor();
                } else if (eat('/')) {
                    double d = parseFactor();
                    if (d == 0.0) {
                        throw new ArithmeticException("除数不能为 0");
                    }
                    x /= d;
                } else {
                    return x;
                }
            }
        }

        /** 一元正负、括号、字面量数字。不支持函数名，遇到字母直接失败。 */
        double parseFactor() {
            if (eat('+')) {
                return parseFactor();
            }
            if (eat('-')) {
                return -parseFactor();
            }
            double x;
            int start = pos;
            if (eat('(')) {
                x = parseExpression();
                if (!eat(')')) {
                    throw new IllegalArgumentException("缺少右括号");
                }
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') {
                    next();
                }
                x = Double.parseDouble(s.substring(start, pos));
            } else {
                throw new IllegalArgumentException("无法解析: " + s);
            }
            return x;
        }
    }
}
```

### `src/main/java/cn/deepassistant/util/ProcessEnv.java`

**作用：** 尽量把 yml `mcp.api-key` 写进 `System.getenv`。官方 `ToolsConfigLoader` 的 `${MCP_API_KEY}` 只认进程环境。JDK 17 默认打不开 `java.lang.ProcessEnvironment`，失败时打 warn，由 `WorkspaceToolsConfigs.load` 做同等替换。已有非空 env 不覆盖。不要用百炼 `LLM_API_KEY` 去调智谱 MCP。

Harness 2.0.1 会先快照子 Agent Toolkit，再注册 MCP；`research-agent` 又是 isolated 工作区（没有 `tools.json`）。所以父 `build()` 前必须 `McpServerRegistrar.register`，再 `toolsConfig()` 只注入 deny。

```java
package cn.deepassistant.util;

/**
 * 把键写入 {@link System#getenv()}。Harness {@code tools.json} 的 {@code ${ENV}} 只认进程环境变量，
 * 不认 Spring {@code application.yml}。
 *
 * <p>JDK 17 默认不允许打开 {@code java.lang.ProcessEnvironment}，写入会失败；调用方必须再走
 * 本地 {@code tools.json} 替换（与 {@code ToolsConfigLoader.substituteEnv} 等价）。
 */
@Slf4j
public final class ProcessEnv {

    private ProcessEnv() {
    }

    /**
     * 环境里已有非空值则保持不动。否则尝试写入 {@code value}。
     *
     * @return 调用结束后 {@code System.getenv(key)} 非空
     */
    public static boolean ensure(String key, String value) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String existing = System.getenv(key);
        if (existing != null && !existing.isBlank()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            put(key, value);
        } catch (Exception e) {
            log.warn("无法写入 System.getenv({})：{}。请在 IDE 环境变量或 shell 里 export，或依赖 yml 回退替换。",
                    key, e.toString());
            return false;
        }
        String now = System.getenv(key);
        return now != null && !now.isBlank();
    }

    private static void put(String key, String value) throws Exception {
        Exception last = null;
        try {
            mutateProcessEnvironment(key, value);
            if (notBlank(System.getenv(key))) {
                return;
            }
        } catch (Exception e) {
            last = e;
        }
        try {
            mutateGetenvView(key, value);
            if (notBlank(System.getenv(key))) {
                return;
            }
        } catch (Exception e) {
            last = e;
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("写入后 System.getenv(" + key + ") 仍为空");
    }

    @SuppressWarnings("unchecked")
    private static void mutateProcessEnvironment(String key, String value) throws Exception {
        Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
        try {
            Field env = pe.getDeclaredField("theEnvironment");
            env.setAccessible(true);
            ((Map<String, String>) env.get(null)).put(key, value);
        } catch (NoSuchFieldException ignored) {
            // Windows 只有 case-insensitive 那张表
        }
        try {
            Field ci = pe.getDeclaredField("theCaseInsensitiveEnvironment");
            ci.setAccessible(true);
            ((Map<String, String>) ci.get(null)).put(key, value);
        } catch (NoSuchFieldException ignored) {
            // Unix
        }
        try {
            Field unmod = pe.getDeclaredField("theUnmodifiableEnvironment");
            unmod.setAccessible(true);
            Map<String, String> frozen = (Map<String, String>) unmod.get(null);
            putInner(frozen, key, value);
        } catch (NoSuchFieldException ignored) {
            // 部分 JDK 没有这张表
        }
    }

    @SuppressWarnings("unchecked")
    private static void mutateGetenvView(String key, String value) throws Exception {
        putInner(System.getenv(), key, value);
    }

    @SuppressWarnings("unchecked")
    private static void putInner(Map<String, String> frozen, String key, String value) throws Exception {
        Field inner = frozen.getClass().getDeclaredField("m");
        inner.setAccessible(true);
        ((Map<String, String>) inner.get(frozen)).put(key, value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
```

## 六、联网集成（官方 tools.json MCP）

自研 `ZhipuMcpWebSearchService` / `WebResearchTools` / REST 回退已删除。MCP 由框架 `McpServerRegistrar` 按 `workspace/tools.json` 注册。密钥链路：`export MCP_API_KEY`（官方 getenv）→ 否则 yml `mcp.api-key`（`ProcessEnv.ensure`，失败则本地替换占位符）。**不要**把百炼 Key 当智谱 MCP Key。传输用智谱文档的 SSE 回退，避免 Java SDK 对 `/mcp` GET 405。

## 七、对话服务与 SSE API

### `src/main/java/cn/deepassistant/service/AssistantChatService.java`

**作用（请读仓库文件，勿复制下文旧粘贴）：** 把用户消息 / 审批续跑交给 `HarnessAgent.streamEvents`，把 `AgentEvent` 映射成网页 SSE。

当前实现要点：

1. **`chat` / `resume` 返回 `Flux<SseEvent>`**，Controller 直接订阅；浏览器断开即取消订阅。
2. **不要**再写 `stream.toIterable()` + `boundedElastic` 阻塞循环——那是旧版做法。
3. **`RedisSessionRunLock`**：同一 `(userId, sessionId)` 同时只能跑一轮；冲突返回 SSE error。
4. **待审批**：`chat` 前若仍有 pending 则拒绝新聊；`resume` 用 **`claim`** 取出审批单，抢锁失败会把审批单写回。
5. **续跑 metadata**：`Msg.METADATA_CONFIRM_RESULTS` + `METADATA_CONFIRM_REQUEST_REPLY_ID`。
6. **删会话**：取消进行中的 `Disposable`，再清 pending / SessionStore / AgentState。
7. 每条官方事件先过 `AgentActivityLogger`；思考 delta 不落库，思考结束可持久化全文。

```java
// 学习入口（伪代码，完整逻辑见仓库）
public Flux<SseEvent> chat(String userId, String sessionId, String userMessage) {
    return Flux.defer(() -> {
        // getOrCreate 会话 → 若有 pending 则 error
        // runLock.tryAcquire → 失败则「该会话正在处理中」
        // appendMessage(user) → subscribe(streamEvents(...))
    });
}

public Flux<SseEvent> resume(String userId, String sessionId, boolean approved) {
    return Flux.defer(() -> {
        // claim pending → tryAcquire → ConfirmResult metadata → subscribe
    });
}
```

### `src/main/java/cn/deepassistant/service/AgentActivityLogger.java`

**作用：** 一轮对话里模型做了什么：调用模型、思考摘要、工具名/参数、搜索词与返回、作答预览。只打 MDC 的 `SESSION_ID`，不打 userId。过长截断；args 里的 api-key / token 打码。搜索类工具（`webSearchPrime` / `webReader` 等）走 `[Search]` 前缀。

```java
package cn.deepassistant.service;

/**
 * 把一轮 Harness 事件打成可读的后台日志：模型在干什么、调了哪些工具、搜索词和返回摘要。
 * 只打 {@code SESSION_ID}（MDC），<b>不打 userId</b>。
 *
 * <p><b>何时调用：</b>{@code AssistantChatService.run} 每来一条官方 {@link AgentEvent} 就 {@link #accept}，
 * 流结束再 {@link #finish}。
 */
@Slf4j
final class AgentActivityLogger {

    static final int ARGS_LIMIT = 800;
    static final int RESULT_LIMIT = 2000;
    static final int THINKING_LIMIT = 800;
    static final int REPLY_LIMIT = 400;

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(\"(?:api[-_]?key|token|secret|authorization|password|bearer)\"\\s*:\\s*\")([^\"]*)(\")");

    private final List<String> steps = new ArrayList<>();
    private final Map<String, StringBuilder> argsById = new LinkedHashMap<>();
    private final Map<String, StringBuilder> resultsById = new LinkedHashMap<>();
    private final Set<String> argsLogged = new HashSet<>();
    private final StringBuilder thinking = new StringBuilder();
    private int modelCalls;

    void accept(AgentEvent event) {
        if (event instanceof ModelCallStartEvent) {
            modelCalls++;
            log.info("[Agent] 调用模型 #{}", modelCalls);
            steps.add("模型#" + modelCalls);
            return;
        }
        if (event instanceof ModelCallEndEvent end) {
            ChatUsage usage = end.getUsage();
            if (usage != null) {
                log.info("[Agent] 模型返回 #{} in={} out={} total={} time={}s",
                        modelCalls,
                        usage.getInputTokens(),
                        usage.getOutputTokens(),
                        usage.getTotalTokens(),
                        usage.getTime());
            } else {
                log.info("[Agent] 模型返回 #{}", modelCalls);
            }
            return;
        }
        if (event instanceof ThinkingBlockStartEvent) {
            thinking.setLength(0);
            log.info("[Agent] 开始思考");
            return;
        }
        if (event instanceof ThinkingBlockDeltaEvent delta) {
            if (delta.getDelta() != null) {
                thinking.append(delta.getDelta());
            }
            return;
        }
        if (event instanceof ThinkingBlockEndEvent) {
            String text = thinking.toString().trim();
            if (!text.isEmpty()) {
                log.info("[Agent] 思考内容 {}", clip(text, THINKING_LIMIT));
                steps.add("思考");
            }
            thinking.setLength(0);
            return;
        }
        if (event instanceof ToolCallStartEvent start) {
            remember(start.getToolCallId(), start.getToolCallName());
            log.info("[Agent] 准备调用工具 name={} id={}", start.getToolCallName(), start.getToolCallId());
            return;
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            remember(delta.getToolCallId(), delta.getToolCallName());
            append(argsById, delta.getToolCallId(), delta.getDelta());
            return;
        }
        if (event instanceof ToolCallEndEvent end) {
            remember(end.getToolCallId(), end.getToolCallName());
            logToolArgs(end.getToolCallId(), end.getToolCallName());
            return;
        }
        if (event instanceof ToolResultStartEvent start) {
            remember(start.getToolCallId(), start.getToolCallName());
            if (!loggedArgs(start.getToolCallId())) {
                logToolArgs(start.getToolCallId(), start.getToolCallName());
            }
            log.info("[Agent] 工具执行中 name={} id={}", start.getToolCallName(), start.getToolCallId());
            return;
        }
        if (event instanceof ToolResultTextDeltaEvent delta) {
            remember(delta.getToolCallId(), delta.getToolCallName());
            append(resultsById, delta.getToolCallId(), delta.getDelta());
            return;
        }
        if (event instanceof ToolResultEndEvent end) {
            remember(end.getToolCallId(), end.getToolCallName());
            logToolResult(end);
            return;
        }
        if (event instanceof RequireUserConfirmEvent confirm) {
            ToolUseBlock first = confirm.getToolCalls() == null || confirm.getToolCalls().isEmpty()
                    ? null : confirm.getToolCalls().get(0);
            String name = first == null ? "未知工具" : first.getName();
            String args = first == null || first.getInput() == null ? "" : String.valueOf(first.getInput());
            log.info("[Agent] 等待审批 name={} args={}", name, clip(redact(args), ARGS_LIMIT));
            steps.add("审批:" + name);
            return;
        }
        if (event instanceof TextBlockStartEvent) {
            log.info("[Agent] 开始作答");
        }
    }

    void finish(String reply, boolean interrupted, boolean cancelled) {
        if (cancelled) {
            log.info("[Agent] 本轮被取消 steps={}", steps);
            return;
        }
        if (interrupted) {
            log.info("[Agent] 本轮停在审批 steps={}", steps);
            return;
        }
        if (reply != null && !reply.isBlank()) {
            log.info("[Agent] 作答 {}", clip(reply, REPLY_LIMIT));
            steps.add("作答" + reply.length() + "字");
        }
        log.info("[Agent] 本轮结束 steps={}", steps.isEmpty() ? List.of("直接作答") : steps);
    }

    private void logToolArgs(String id, String name) {
        String args = buf(argsById, id);
        String query = searchQuery(name, args);
        if (isSearch(name) && query != null) {
            log.info("[Search] 检索词 tool={} query={}", name, clip(query, ARGS_LIMIT));
            steps.add(name + "(" + clip(query, 80) + ")");
        } else {
            log.info("[Agent] 工具参数 name={} id={} args={}", name, id, clip(redact(args), ARGS_LIMIT));
            steps.add(name);
        }
        if (id != null) {
            argsLogged.add(id);
        }
    }

    private boolean loggedArgs(String id) {
        return id != null && argsLogged.contains(id);
    }

    private void logToolResult(ToolResultEndEvent end) {
        String id = end.getToolCallId();
        String name = end.getToolCallName();
        String state = end.getState() == null ? "?" : end.getState().name();
        String result = buf(resultsById, id);
        if (isSearch(name)) {
            log.info("[Search] 检索结果 tool={} state={} content={}",
                    name, state, clip(result, RESULT_LIMIT));
        } else {
            log.info("[Agent] 工具结果 name={} id={} state={} content={}",
                    name, id, state, clip(redact(result), RESULT_LIMIT));
        }
        if (id != null) {
            resultsById.remove(id);
        }
    }

    private void remember(String id, String name) {
        if (id == null) {
            return;
        }
        argsById.computeIfAbsent(id, k -> new StringBuilder());
        resultsById.computeIfAbsent(id, k -> new StringBuilder());
    }

    private static void append(Map<String, StringBuilder> buf, String id, String delta) {
        if (id == null || delta == null || delta.isEmpty()) {
            return;
        }
        buf.computeIfAbsent(id, k -> new StringBuilder()).append(delta);
    }

    private static String buf(Map<String, StringBuilder> map, String id) {
        if (id == null) {
            return "";
        }
        StringBuilder b = map.get(id);
        return b == null ? "" : b.toString();
    }

    static boolean isSearch(String tool) {
        if (tool == null) {
            return false;
        }
        String n = tool.toLowerCase(Locale.ROOT);
        return n.contains("search") || n.contains("webreader") || n.contains("web_fetch") || n.contains("webread");
    }

    static String searchQuery(String tool, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        for (String key : List.of("q", "query", "search_query", "searchQuery", "keyword", "keywords", "url")) {
            String value = jsonField(argsJson, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return isSearch(tool) ? argsJson : null;
    }

    static String jsonField(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != '"') {
            return null;
        }
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int j = p + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (esc) {
                out.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '"') {
                return out.toString();
            }
            out.append(c);
        }
        return out.toString();
    }

    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return SECRET_KEY.matcher(text).replaceAll("$1***$3");
    }

    static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() <= max) {
            return flat;
        }
        return flat.substring(0, max) + "...(" + flat.length() + "字)";
    }
}
```

### `src/main/java/cn/deepassistant/service/AgentEventMapper.java`

**作用：** 官方 AgentEvent → 前端 SSE：`status` / `thinking` / `token` / `tool` / `plan` / `agent` / `interrupt`。`ModelCallStart` 变 live `status`（思考中）；`ThinkingBlock*` 变 `thinking`；工具起止带 name/id/phase。思考 delta 与 status 不 persist。

```java
package cn.deepassistant.service;

/**
 * 把 AgentScope 官方 {@link AgentEvent} 收成前端 SSE 认识的几种名字：
 * {@code token} / {@code thinking} / {@code status} / {@code tool} / {@code plan} /
 * {@code agent} / {@code interrupt}。
 *
 * <p><b>何时调用：</b>仅本项目 {@code AssistantChatService.run} 在消费
 * {@code harnessAgent.streamEvents} 时逐条映射。框架自己不会调这个类。
 */
@Component
@RequiredArgsConstructor
public class AgentEventMapper {

    private final ObjectMapper objectMapper;

    /**
     * 一条官方事件 → 一条前端事件；不关心的类型返回 {@link MappedEvent#none()} 被丢掉。
     *
     * <p><b>何时调用：</b>{@code AssistantChatService.run} 的 for 循环，每个 {@link AgentEvent} 一次。
     */
    public MappedEvent map(AgentEvent event) {
        if (event instanceof ModelCallStartEvent) {
            return MappedEvent.live("status", json(status("thinking", "start")));
        }
        if (event instanceof ThinkingBlockStartEvent) {
            return MappedEvent.live("thinking", json(thinking("start", null)));
        }
        if (event instanceof ThinkingBlockDeltaEvent delta) {
            String text = delta.getDelta();
            if (text == null || text.isEmpty()) {
                return MappedEvent.none();
            }
            return MappedEvent.live("thinking", json(thinking("delta", text)));
        }
        if (event instanceof ThinkingBlockEndEvent) {
            return MappedEvent.of("thinking", json(thinking("end", null)));
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            String text = delta.getDelta();
            if (text == null || text.isEmpty()) {
                return MappedEvent.none();
            }
            return MappedEvent.of("token", text);
        }
        if (event instanceof RequireUserConfirmEvent confirm) {
            ToolUseBlock first = confirm.getToolCalls() == null || confirm.getToolCalls().isEmpty()
                    ? null : confirm.getToolCalls().get(0);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", first == null ? "未知工具" : first.getName());
            payload.put("args", first == null ? Map.of() : first.getInput());
            payload.put("replyId", confirm.getReplyId());
            return MappedEvent.of("interrupt", toJson(payload), true);
        }
        if (event instanceof ToolCallStartEvent start) {
            return MappedEvent.of(classify(start.getToolCallName()),
                    json(toolPayload(start.getToolCallId(), start.getToolCallName(), "start", null)));
        }
        if (event instanceof ToolResultStartEvent start) {
            return MappedEvent.of(classify(start.getToolCallName()),
                    json(toolPayload(start.getToolCallId(), start.getToolCallName(), "running", null)));
        }
        if (event instanceof ToolResultEndEvent end) {
            String state = end.getState() == null ? null : end.getState().name();
            return MappedEvent.of(classify(end.getToolCallName()),
                    json(toolPayload(end.getToolCallId(), end.getToolCallName(), "end", state)));
        }
        String typeName = event.getType() == null ? "" : event.getType().name();
        if (typeName.contains("TOOL") && (typeName.contains("RESULT") || typeName.contains("END"))) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "tool");
            payload.put("phase", "end");
            payload.put("event", typeName);
            return MappedEvent.of("tool", toJson(payload));
        }
        if (typeName.contains("SUBAGENT") || typeName.contains("AGENT_SPAWN")) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "agent");
            payload.put("event", typeName);
            return MappedEvent.of("agent", toJson(payload));
        }
        return MappedEvent.none();
    }

    /**
     * 按工具名把 start 事件分到前端频道：todo → plan，spawn/task → agent，其余 → tool。
     *
     * <p><b>何时调用：</b>仅 {@link #map} 处理工具生命周期事件时。
     */
    static String classify(String toolName) {
        if (toolName == null) {
            return "tool";
        }
        String lower = toolName.toLowerCase();
        if (lower.contains("todo")) {
            return "plan";
        }
        if (lower.contains("agent_spawn") || lower.contains("agent_send") || lower.contains("task")) {
            return "agent";
        }
        return "tool";
    }

    private static Map<String, Object> status(String label, String phase) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("label", label);
        payload.put("phase", phase);
        return payload;
    }

    private static Map<String, Object> thinking(String phase, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        if (text != null) {
            payload.put("text", text);
        }
        return payload;
    }

    private static Map<String, Object> toolPayload(String id, String name, String phase, String state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool");
        payload.put("id", id);
        payload.put("tool", name);
        payload.put("phase", phase);
        if (state != null) {
            payload.put("state", state);
        }
        return payload;
    }

    private String json(Map<String, Object> payload) {
        return toJson(payload);
    }

    /** JSON 失败时退回 {@code String.valueOf}，避免映射抛错把整条 SSE 掐断。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * @param interrupt true 时 {@code AssistantChatService.run} 会停止消费后续事件并写入 pending
     * @param skipped   true 时前端不展示（空 delta、内部心跳等）
     * @param persist   false 时只推 SSE，不写进网页会话 events（思考 delta / 状态条）
     */
    public record MappedEvent(String event, String data, boolean interrupt, boolean skipped, boolean persist) {
        static MappedEvent of(String event, String data) {
            return new MappedEvent(event, data, false, false, true);
        }

        static MappedEvent of(String event, String data, boolean interrupt) {
            return new MappedEvent(event, data, interrupt, false, true);
        }

        static MappedEvent live(String event, String data) {
            return new MappedEvent(event, data, false, false, false);
        }

        static MappedEvent none() {
            return new MappedEvent(null, null, false, true, false);
        }
    }
}
```

### `src/main/java/cn/deepassistant/controller/AssistantController.java`

**作用：** HTTP API。聊天 / 续跑都是真流式 SSE：先推 `session`，再订阅 Harness Flux。`CACHE_CONTROL` + `X-Accel-Buffering: no` 避免反向代理把 token 攒包。START 日志在本类打开 `SESSION_ID`；事件到达时服务层再打开。GET 会话不存在返回 400。

```java
package cn.deepassistant.controller;

/**
 * HTTP API。聊天走 SSE；历史检索和档案读官方记忆文件。
 *
 * <p>多用户隔离：所有端点都接收 userId——POST 请求从 body 的 {@code userId} 字段取，
 * GET 请求从 {@code X-User-Id} header 或 {@code ?userId=} 查询参数取。
 * 不传时回退到 {@code "local"}，等价于原来的单用户模式。
 *
 * <p>日志里的 {@code SESSION_ID}：聊天 / 续跑的 sessionId 在 JSON body 里，拦截器读不到，
 * 必须在本类 START 日志里打开 MDC。Harness 每条事件到达时 {@link AssistantChatService} 会再打开一次。
 * SSE 订阅在 {@code boundedElastic}，避免占死 Netty 的 HTTP 线程。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantChatService chatService;
    private final SessionStore sessionStore;
    private final UserMemoryQueryService memoryQueryService;
    private final HarnessAgent harnessAgent;

    /** 健康检查回显当前大模型名，方便运维确认配的是哪套。 */
    @Value("${llm.model}")
    private String model;

    public AssistantController(AssistantChatService chatService,
                               SessionStore sessionStore,
                               UserMemoryQueryService memoryQueryService,
                               HarnessAgent harnessAgent) {
        this.chatService = chatService;
        this.sessionStore = sessionStore;
        this.memoryQueryService = memoryQueryService;
        this.harnessAgent = harnessAgent;
    }

    /**
     * 新一轮对话：校验入参 → 立刻把 sessionId 推给前端 → 在工作线程里跑 Harness 并转成 SSE。
     *
     * <p><b>何时调用：</b>前端聊天框发送，{@code POST /api/assistant/chat}。AgentScope 不会调 Controller。
     *
     * <p>返回的 Flux 分两段 {@code concat}：先发 {@code session} 事件（新建会话时前端要存这个 id），
     * 再发 token / tool / interrupt / done。前一段在 HTTP 线程就能发出去，不必等模型。
     *
     * @param request {@code message} 必填；{@code sessionId} 空则新建；{@code userId} 空则 {@code local}
     */
    @PostMapping(value = "/assistant/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
        // 空消息直接结束流，避免空跑一次大模型
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Flux.just(sse("error", "消息不能为空"), sse("done", "[DONE]"));
        }
        final String userId;
        final String sessionId;
        try {
            userId = UserIds.normalize(request.getUserId());
            // 没带 sessionId 就现场生成 UUID，后续所有 Redis 槽位都用这个 id
            sessionId = SessionIds.normalizeOrCreate(request.getSessionId());
        } catch (IllegalArgumentException e) {
            return Flux.just(sse("error", e.getMessage()), sse("done", "[DONE]"));
        }
        // HTTP 线程上的 START 日志：拦截器读不到 body 里的 sessionId，这里临时打开再清掉
        ConversationMdc.open(sessionId);
        try {
            log.info("[Chat] START session={} msgChars={}", sessionId, request.getMessage().length());
        } finally {
            ConversationMdc.clear();
        }
        Flux<ServerSentEvent<String>> sessionEvent = Flux.just(sse("session", sessionId));
        Flux<ServerSentEvent<String>> chatStream = Flux.<ServerSentEvent<String>>create(sink -> {
            // 浏览器断开 SSE 时 Reactor 会 cancel/dispose；标志位置位后不再往已关的流里写
            AtomicBoolean cancelled = new AtomicBoolean(false);
            sink.onCancel(() -> cancelled.set(true));
            sink.onDispose(() -> cancelled.set(true));
            // 必须在 boundedElastic 线程里打开 MDC：Harness 的 log 也打在这条线程
            ConversationMdc.run(sessionId, () -> {
                try {
                    chatService.chat(userId, sessionId, request.getMessage(), event -> {
                        if (!cancelled.get()) {
                            sink.next(sse(event.event(), event.data()));
                        }
                    }, cancelled);
                    if (!cancelled.get()) {
                        sink.next(sse("done", "[DONE]"));
                    }
                    sink.complete();
                } catch (Exception e) {
                    log.error("[Chat] FAIL session={}", sessionId, e);
                    if (!cancelled.get()) {
                        String msg = friendlyError(e);
                        sink.next(sse("error", msg));
                        // 再发一条 token，让前端聊天气泡里也能看到失败原因，而不是只有侧栏 error
                        sink.next(sse("token", "抱歉，本次请求失败：" + msg));
                        sink.next(sse("done", "[DONE]"));
                    }
                    sink.complete();
                }
            });
        }).subscribeOn(Schedulers.boundedElastic());
        return Flux.concat(sessionEvent, chatStream);
    }

    /**
     * HITL 续跑：用户批准或拒绝写文件后，把确认结果塞回 Harness 继续往下跑。
     *
     * <p><b>何时调用：</b>前端点批准/拒绝，{@code POST /api/assistant/resume}。
     * 框架侧对应「上次 {@code RequireUserConfirmEvent} 之后的下一次 {@code streamEvents}」。
     *
     * <p>sessionId 必须已存在（不能现场新建）。先查 Redis 里有没有待审批记录，
     * 没有就立刻返回 error，避免空跑。
     */
    @PostMapping(value = "/assistant/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> resume(@RequestBody ResumeRequest request) {
        final String userId;
        final String sessionId;
        try {
            userId = UserIds.normalize(request.getUserId());
            sessionId = SessionIds.requireValid(request.getSessionId());
        } catch (IllegalArgumentException e) {
            return Flux.just(sse("error", e.getMessage()), sse("done", "[DONE]"));
        }
        ConversationMdc.open(sessionId);
        try {
            if (!chatService.hasPendingApproval(userId, sessionId)) {
                return Flux.just(sse("error", "当前会话没有待审批的操作"), sse("done", "[DONE]"));
            }
            log.info("[Resume] START session={} approved={}", sessionId, request.isApproved());
        } finally {
            ConversationMdc.clear();
        }
        return Flux.<ServerSentEvent<String>>create(sink -> {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            sink.onCancel(() -> cancelled.set(true));
            sink.onDispose(() -> cancelled.set(true));
            ConversationMdc.run(sessionId, () -> {
                try {
                    chatService.resume(userId, sessionId, request.isApproved(), event -> {
                        if (!cancelled.get()) {
                            sink.next(sse(event.event(), event.data()));
                        }
                    }, cancelled);
                    if (!cancelled.get()) {
                        sink.next(sse("done", "[DONE]"));
                    }
                    sink.complete();
                } catch (Exception e) {
                    log.error("[Resume] FAIL session={}", sessionId, e);
                    if (!cancelled.get()) {
                        String msg = friendlyError(e);
                        sink.next(sse("error", msg));
                        sink.next(sse("token", "抱歉，续跑失败：" + msg));
                        sink.next(sse("done", "[DONE]"));
                    }
                    sink.complete();
                }
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 列出指定用户的所有会话摘要（侧栏）。query 优先于 header。
     */
    @GetMapping("/sessions")
    public List<SessionSummary> listSessions(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        return sessionStore.listSessions(userId);
    }

    /**
     * 打开一条会话的全文。不存在抛 400（前端据此清掉过期的 localStorage sessionId）。
     * {@code pendingApproval} 现查 Redis，告诉前端这条会话是否还卡在写文件审批。
     */
    @GetMapping("/sessions/{id}")
    public SessionDetail getSession(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        SessionDetail detail = sessionStore.get(userId, id);
        if (detail == null) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        detail.setPendingApproval(chatService.hasPendingApproval(userId, id));
        return detail;
    }

    /**
     * 删除网页会话 JSON，同时清掉 AgentState 和待审批记录，避免幽灵会话续跑。
     */
    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> deleteSession(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        chatService.clearSessionMemory(userId, id);
        return Map.of("ok", true, "id", id);
    }

    /**
     * 侧栏「新对话」：只建一条空会话记录，不调大模型。标题先写成「新对话」。
     */
    @PostMapping("/sessions")
    public SessionDetail createSession(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        return sessionStore.getOrCreate(userId, null, "新对话");
    }

    /**
     * 历史检索：搜网页会话库里的原文，只搜指定用户。
     *
     * @param q     关键词，空则返回空列表
     * @param limit 最多返回条数，底层还会再 cap 到 50
     */
    @GetMapping("/history/search")
    public HistorySearchResponse searchHistory(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        return memoryQueryService.searchHistory(userId, q, limit);
    }

    /**
     * 用户档案：使用次数 + 官方 MEMORY.md + 日流水账，全部按用户隔离。
     */
    @GetMapping("/memory")
    public UserMemoryProfile memoryProfile(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        return memoryQueryService.profile(userId);
    }

    /**
     * 前端展示用的能力清单：子 Agent 名 + 当前 Toolkit 真实工具名。
     */
    @GetMapping("/agents")
    public Map<String, Object> agents() {
        List<String> tools = new ArrayList<>(harnessAgent.getToolkit().getToolNames());
        tools.sort(Comparator.naturalOrder());
        return Map.of(
                "agents", List.of("research-agent", "general-purpose"),
                "harness", tools);
    }

    /**
     * 存活探测 + 运行时关键开关。{@code storage=mysql+redis}：AgentState 在 MySQL，
     * 工作区 / 网页会话 / 待审批在 Redis。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        List<String> mcpTools = new ArrayList<>();
        for (String name : harnessAgent.getToolkit().getToolNames()) {
            AgentTool tool = harnessAgent.getToolkit().getTool(name);
            if (tool instanceof McpTool) {
                mcpTools.add(name);
            }
        }
        mcpTools.sort(Comparator.naturalOrder());
        return Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("framework", "AgentScope-Java-2.0-HarnessAgent"),
                Map.entry("edition", "agentscope"),
                Map.entry("model", model),
                Map.entry("mcp_enabled", !mcpTools.isEmpty()),
                Map.entry("mcp", "tools.json"),
                Map.entry("mcp_tools", mcpTools),
                Map.entry("memory", "official-flush-consolidation"),
                Map.entry("storage", "mysql+redis"),
                Map.entry("multi_user", true),
                Map.entry("multi_replica", true),
                Map.entry("version", "2.0.0"));
    }

    /**
     * 非法 userId / sessionId、会话不存在等业务校验失败统一成 400 JSON，前端可据此重置本地会话。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage());
    }

    /** 组装一条 SSE：event 名给前端 switch，data 为空时发空串避免客户端拿到 null。 */
    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data == null ? "" : data).build();
    }

    /**
     * 把底层异常收成给人看的短句。鉴权失败单独提示要配 {@code LLM_API_KEY}；其它超长堆栈截断到 300 字。
     */
    private static String friendlyError(Throwable e) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (raw.contains("401") || raw.contains("令牌") || raw.contains("Unauthorized")) {
            return "大模型鉴权失败，请设置环境变量 LLM_API_KEY。";
        }
        if (raw.length() > 300) {
            return raw.substring(0, 300) + "...";
        }
        return raw;
    }
}
```

## 八、会话持久化与模型 DTO

### `src/main/java/cn/deepassistant/model/ChatRequest.java`

**作用：** 聊天请求，userId 是多用户入口

```java
package cn.deepassistant.model;

/**
 * 聊天请求。{@code userId} 决定这轮对话归谁——多用户隔离的入口。
 * 不传时后端回退到 {@code "local"}，等价于原来的单用户模式。
 */
@Data
public class ChatRequest {
    private String userId;
    private String sessionId;
    private String message;
    private Boolean showTools;
}
```

### `src/main/java/cn/deepassistant/model/ResumeRequest.java`

**作用：** 审批续跑：approved + userId + sessionId

```java
package cn.deepassistant.model;

/**
 * 文件写入审批续跑请求。{@code userId} 必须和当初 chat 请求一致，
 * 否则找不到待审批记录。
 */
@Data
public class ResumeRequest {
    private String userId;
    private String sessionId;
    private boolean approved;
}
```

### `src/main/java/cn/deepassistant/model/ChatMessageRecord.java`

**作用：** 一条落盘消息（含工具事件）

```java
package cn.deepassistant.model;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageRecord {
    private String role;
    private String content;
    private Instant timestamp;
    @Builder.Default
    private List<Map<String, Object>> events = new ArrayList<>();
}
```

### `src/main/java/cn/deepassistant/model/SessionDetail.java`

**作用：** 会话详情

```java
package cn.deepassistant.model;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionDetail {
    private String id;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    @Builder.Default
    private List<ChatMessageRecord> messages = new ArrayList<>();
    private boolean pendingApproval;
}
```

### `src/main/java/cn/deepassistant/model/SessionSummary.java`

**作用：** 会话列表项

```java
package cn.deepassistant.model;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummary {
    private String id;
    private String title;
    private String preview;
    private Instant updatedAt;
    private int messageCount;
}
```

### `src/main/java/cn/deepassistant/model/MemoryFact.java`

**作用：** 从 MEMORY.md 解析出的一条事实

```java
package cn.deepassistant.model;

/**
 * 档案页展示用的一条记忆。数据来源是官方 {@code MEMORY.md} 里的 Markdown 列表，
 * 不是另一套数据库。分类是根据当前 {@code ## 标题} 猜的。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryFact {

    /** 本条记忆的稳定 id，更新时靠它定位，而不是靠内容字符串。 */
    private String id;

    /** 分类由 MEMORY.md 小节标题推断：preference / identity / project / constraint / working_style / fact。 */
    private String category;

    /** 给人看的一句话。 */
    private String content;

    private String sourceSessionId;

    private double confidence;

    private Instant createdAt;
    private Instant updatedAt;
}
```

### `src/main/java/cn/deepassistant/model/UserMemoryProfile.java`

**作用：** 档案页聚合

```java
package cn.deepassistant.model;

/**
 * 「我的档案」页一次返回的数据：使用次数（本项目会话库）+ 官方两层记忆文件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMemoryProfile {

    private UsageStats usage;

    /** 第二层：策划后的长期记忆 MEMORY.md，每轮会进 system prompt。 */
    private String memoryMarkdown;

    /** 第一层：按日期倒序的日流水账。 */
    @Builder.Default
    private List<DailyMemoryFile> dailyLedgers = new ArrayList<>();

    /**
     * 从 MEMORY.md 里拆出来的列表项，方便前端分组展示。
     * 不是另一套存储，只是 Markdown 的解析结果。
     */
    @Builder.Default
    private List<MemoryFact> facts = new ArrayList<>();
}
```

### `src/main/java/cn/deepassistant/model/DailyMemoryFile.java`

**作用：** 一条日流水文件元数据

```java
package cn.deepassistant.model;

/**
 * 官方第一层记忆：某天的日流水账 {@code workspace/memory/YYYY-MM-DD.md}。
 * 内容由框架追加，本项目只读、不改。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyMemoryFile {

    /** 相对工作区的路径，例如 {@code memory/2026-09-08.md}。 */
    private String path;
    private Instant lastModified;
    private String content;
}
```

### `src/main/java/cn/deepassistant/model/HistorySearchHit.java`

**作用：** 历史检索命中

```java
package cn.deepassistant.model;

/**
 * 历史检索的一条命中。用户在侧栏搜「AgentScope」时，每条结果对应某次会话里的一句话。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistorySearchHit {

    private String sessionId;
    private String sessionTitle;
    private String role;
    private Instant timestamp;

    /** 带一点前后文的摘录，方便人眼扫，不必打开整段对话。 */
    private String snippet;

    /** 这一句里关键词出现了几次，用来排序。 */
    private int matchCount;
}
```

### `src/main/java/cn/deepassistant/model/HistorySearchResponse.java`

**作用：** 历史检索响应

```java
package cn.deepassistant.model;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistorySearchResponse {

    private String query;
    private int hitCount;

    @Builder.Default
    private List<HistorySearchHit> hits = new ArrayList<>();
}
```

### `src/main/java/cn/deepassistant/model/UsageStats.java`

**作用：** 使用次数统计

```java
package cn.deepassistant.model;

/**
 * 「这个用户历史上怎么用过助手」的统计，全部从 {@code SessionStore} 现算，不调大模型。
 *
 * <p>自动抽取解决的是「记住偏好」；这份统计解决的是「知道用过多少、最近聊了啥」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageStats {

    private int sessionCount;
    private int messageCount;
    private int userMessageCount;
    private Instant firstSeenAt;
    private Instant lastSeenAt;

    /** 最近几条会话标题，用来在档案页展示「最近在聊什么」。 */
    @Builder.Default
    private List<SessionSummary> recentSessions = new ArrayList<>();
}
```

## 九、工具类与配置

### `src/main/resources/application.yml`

**作用：** 端口、模型、MCP、记忆节流、MySQL、Redis；密钥已脱敏

```yaml
server:
  port: 8089

spring:
  application:
    name: agentscope-assistant-java
  mvc:
    async:
      request-timeout: 330000
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:agentscope_assistant}?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      connection-test-query: SELECT 1
      maximum-pool-size: 20

# OpenAI 兼容（示例为阿里云百炼 / 智谱均可）
llm:
  base-url: https://llm-inr089bfe37yw1si.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
  api-key: ${LLM_API_KEY:YOUR_LLM_API_KEY}
  model: qwen3.8-max
  temperature: 0.3
  timeout-seconds: 120
  enable-thinking: true

# MCP 按官方工作区约定：只声明在 workspace/tools.json 的 mcpServers。
# Harness 在 build() 时读文件（远端 overlay 上层 + 本地模板下层），不要编程注入 ToolsConfig。
# ${MCP_API_KEY} 只认进程环境。IDEA 没 export 时用下面的智谱 key（须是 open.bigmodel.cn 的 API Key）。
mcp:
  api-key: ${MCP_API_KEY:YOUR_ZHIPU_API_KEY}

agent:
  workspace: ${user.dir}/workspace
  max-iters: 25
  approval-tools: write_file,edit_file
  # 只读联网工具：不走人工审批（智谱 MCP 缺 readOnlyHint 时也会强制放行）
  auto-allow-tools: webReader,webSearchPrime
  # 官方分层记忆（见 AgentScopeConfig.memoryConfig）
  # flush-throttle-minutes: 0 = 每轮结束都抽取（ALWAYS）；>0 = 节流分钟数
  memory:
    flush-throttle-minutes: 0
    consolidation-min-gap-minutes: 30
    daily-retention-days: 90

logging:
  level:
    cn.deepassistant: INFO
    io.agentscope: INFO

# 生产必填。AgentState 用官方 MysqlAgentStateStore（表 agentscope_sessions，可自动建）。
# 库名必须和 spring.datasource.url 一致。工作区 / 网页会话 / 待审批仍进 Redis。
# Redis ping 或 MySQL 连不上时进程直接退出。
mysql:
  database: ${MYSQL_DATABASE:agentscope_assistant}
redis:
  host: ${REDIS_HOST:127.0.0.1}
  port: ${REDIS_PORT:6379}
  password: ${REDIS_PASSWORD:}
  database: ${REDIS_DATABASE:0}
```

### `src/main/resources/logback.xml`

**作用：** 控制台日志格式。第四段是 MDC 的 `SESSION_ID`。故意不打 userId，避免泄露用户信息。

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<configuration>

    <appender name="Console" class="ch.qos.logback.core.ConsoleAppender">
        <encoder charset="utf-8">
            <pattern>
                %d{yyyy-MM-dd HH:mm:ss.SSS}|%thread|%level|%X{SESSION_ID}|%logger{0}--->%msg%n
            </pattern>
        </encoder>
    </appender>

    <appender name="Async" class="ch.qos.logback.classic.AsyncAppender">
        <neverBlock>true</neverBlock>
        <queueSize>10240</queueSize>
        <appender-ref ref="Console"/>
    </appender>

    <root level="info">
        <appender-ref ref="Async"/>
    </root>

</configuration>
```
聊天走 SSE，真正执行在 `boundedElastic`。HTTP 线程上的 MDC 传不过去，要在工作线程再打开：

- `ConversationMdc.run(sessionId, ...)`：包住 `chatService.chat` / `resume`
- `SessionMdcInterceptor`：`GET/DELETE /api/sessions/{id}` 从路径取 id；请求结束必须 `clear()`，Tomcat 线程会复用

**不要**把 userId 放进 MDC 或 `log.info("user={}", userId)`。

### `src/main/java/cn/deepassistant/util/ConversationMdc.java`

```java
package cn.deepassistant.util;

/**
 * 把本轮对话的 sessionId 写入 SLF4J MDC，供 {@code logback.xml} 的
 * {@code %X{SESSION_ID}} 打印。线程局部，用完必须 {@link #clear()}，避免线程池串话。
 *
 * <p>不写入 userId：日志里出现用户标识会泄露用户信息。
 *
 * <p>SSE 聊天跑在 {@code Schedulers.boundedElastic()}，HTTP 线程上的 MDC 传不过去，
 * 必须在工作线程再 {@link #run(String, Runnable)} 一次。
 */
public final class ConversationMdc {

    /** 与 {@code logback.xml} 里 {@code %X{SESSION_ID}} 的 key 必须一字不差。 */
    public static final String SESSION_ID = "SESSION_ID";

    private ConversationMdc() {
    }

    /**
     * 把 sessionId 放进当前线程 MDC。空白则移除，避免上一轮残留的 id 被下一条日志带走。
     *
     * <p><b>何时调用：</b>本项目拦截器 / Controller，不是 AgentScope。HTTP 线程打 START 日志前；
     * 工作线程 {@link #run} 里也会再 open 一次。
     */
    public static void open(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            MDC.put(SESSION_ID, sessionId);
        } else {
            MDC.remove(SESSION_ID);
        }
    }

    /** 只清 SESSION_ID，不影响其它业务自己放进 MDC 的 key。 */
    public static void clear() {
        MDC.remove(SESSION_ID);
    }

    /**
     * 在目标线程打开 MDC，执行完（含抛异常）后清掉。
     * 用于 SSE 切到 {@code boundedElastic} 的那段，保证 Harness / Redis 日志也带 sessionId。
     */
    public static void run(String sessionId, Runnable action) {
        open(sessionId);
        try {
            action.run();
        } finally {
            clear();
        }
    }
}
```

### `src/main/java/cn/deepassistant/config/SessionMdcInterceptor.java`

**作用：** 非 SSE 接口从路径 `/api/sessions/{id}` 写入 MDC；`afterCompletion` 必须 `clear()`，Tomcat 线程会复用。聊天 / 续跑的 sessionId 在 JSON body 里，拦截器拿不到，由 Controller 在工作线程 `ConversationMdc.run`。

```java
package cn.deepassistant.config;

/**
 * 非 SSE 接口：从路径里的会话 id 写入 MDC。
 *
 * <p>聊天 / 续跑的 sessionId 在 JSON body 里，拦截器拿不到，由 {@code AssistantController}
 * 在工作线程打开。本拦截器覆盖 {@code GET/DELETE /api/sessions/{id}} 这类路径带 id 的请求。
 */
@Component
public class SessionMdcInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    /** 把自己注册到 {@code /api/**}。同一实例既是拦截器又是配置器，避免再拆一个 Config 类。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    /**
     * 请求进入 Controller 之前：能解析出 sessionId 就写入当前 HTTP 线程的 MDC。
     * 解析不到（列表、新建、聊天）保持空，后面的日志 SESSION_ID 段为空。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String sessionId = pathSessionId(request.getRequestURI());
        if (sessionId != null) {
            ConversationMdc.open(sessionId);
        }
        return true;
    }

    /**
     * 无论 Controller 成功还是抛异常都清 MDC。Tomcat 线程会复用，不清会把上一个会话的 id 漏到下一次请求。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        ConversationMdc.clear();
    }

    /**
     * 从 URI 抽出 {@code /api/sessions/{id}} 的 id。
     *
     * <ul>
     *   <li>{@code /api/sessions/abc-123} → {@code abc-123}</li>
     *   <li>{@code /api/sessions}、{@code /api/sessions/}、带多余路径段的都不算单条会话</li>
     *   <li>query string（{@code ?userId=}）先剥掉再解析</li>
     * </ul>
     */
    static String pathSessionId(String uri) {
        if (uri == null) {
            return null;
        }
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        String prefix = "/api/sessions/";
        int at = path.indexOf(prefix);
        if (at < 0) {
            return null;
        }
        String rest = path.substring(at + prefix.length());
        // 空、或还有下一级路径（例如未来 /sessions/{id}/messages）都不当作 sessionId
        if (rest.isEmpty() || rest.contains("/")) {
            return null;
        }
        return rest;
    }
}
```

### `pom.xml`

**作用：** Spring Boot 3.2.5 + agentscope-harness 2.0.1 + MCP 0.17.0 + agentscope-extensions-mysql + Lettuce

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>cn.deepassistant</groupId>
    <artifactId>agentscope-assistant-java</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    <name>agentscope-assistant-java</name>
    <description>
        小深助手：用 AgentScope Java 2.0 HarnessAgent 实现 Deep Agents 同类能力
        （统筹 + research-agent + general-purpose、工作区、记忆、技能、HITL、SSE）。
    </description>

    <properties>
        <java.version>17</java.version>
        <agentscope.version>2.0.1</agentscope.version>
        <!-- 与 agentscope-core 2.0.1 BOM 一致；0.17+ 才能和 json-schema-validator 2.0.0 共存 -->
        <mcp.version>0.17.0</mcp.version>
        <json-schema-validator.version>2.0.0</json-schema-validator.version>
        <!-- 让 ProcessEnv 能写 System.getenv，供 tools.json 的 ${MCP_API_KEY} -->
        <jvm.opens>--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED</jvm.opens>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-harness</artifactId>
            <version>${agentscope.version}</version>
        </dependency>
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-extensions-model-openai</artifactId>
            <version>${agentscope.version}</version>
        </dependency>
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp</artifactId>
            <version>${mcp.version}</version>
        </dependency>
        <!-- 显式钉住 2.0.0：agentscope ToolValidator 和 MCP 0.17 DefaultJsonSchemaValidator 都走这套 API -->
        <dependency>
            <groupId>com.networknt</groupId>
            <artifactId>json-schema-validator</artifactId>
            <version>${json-schema-validator.version}</version>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>
        <!-- 官方 AgentState MySQL 实现。DataSource 由 spring-boot-starter-jdbc 提供。 -->
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-extensions-mysql</artifactId>
            <version>${agentscope.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <!-- Redis 客户端：工作区 / 网页会话 / 待审批。钉 5.2.2（内部 nexus 拉不到 6.3.2）。 -->
        <dependency>
            <groupId>io.lettuce</groupId>
            <artifactId>lettuce-core</artifactId>
            <version>5.2.2.RELEASE</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <parameters>true</parameters>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <jvmArguments>${jvm.opens}</jvmArguments>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <argLine>${jvm.opens}</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## 十、前端

### `src/main/resources/static/index.html`

**作用：** 深色对话 UI。发送后立刻「思考中」动效；思考原文可折叠；工具/搜索/子任务做成步骤条（转圈 → 打勾）。侧栏用户框；SSE 审批卡；`localStorage.pa_userId` / `pa_sessionId`。过期 session 返回 400「会话不存在」时清本地 id。

````html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>小深 · AgentScope Java 2.0</title>
<style>
  :root {
    --bg: #0e1419;
    --surface: #162028;
    --surface2: #1c2a35;
    --border: #2a3b48;
    --accent: #2bb3a5;
    --accent2: #e8a54b;
    --text: #e7eef3;
    --text-dim: #8aa0b0;
    --user: #1e3a45;
    --ai: #1a242c;
    --tool: #243018;
    --danger: #e06c75;
    --ok: #6bcf7f;
    --font-display: "Iowan Old Style", "Palatino Linotype", Palatino, "Songti SC", serif;
    --font-body: "Avenir Next", "PingFang SC", "Hiragino Sans GB", sans-serif;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: var(--font-body);
    background:
      radial-gradient(1200px 600px at 10% -10%, #1a3a40 0%, transparent 55%),
      radial-gradient(900px 500px at 100% 0%, #3a2a18 0%, transparent 50%),
      var(--bg);
    color: var(--text);
    height: 100vh;
    display: flex;
    overflow: hidden;
  }
  .sidebar {
    width: 280px;
    background: rgba(22, 32, 40, 0.92);
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    flex-shrink: 0;
  }
  .sidebar-header {
    padding: 18px 16px 12px;
    border-bottom: 1px solid var(--border);
  }
  .brand {
    font-family: var(--font-display);
    font-size: 22px;
    letter-spacing: 0.02em;
    color: var(--accent);
  }
  .brand small {
    display: block;
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--text-dim);
    margin-top: 4px;
    font-weight: 400;
  }
  .sidebar-actions {
    display: flex;
    gap: 8px;
    margin-top: 12px;
  }
  .btn {
    background: var(--surface2);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 7px 12px;
    border-radius: 8px;
    cursor: pointer;
    font-size: 13px;
  }
  .btn:hover { border-color: var(--accent); color: var(--accent); }
  .btn.primary {
    background: linear-gradient(135deg, #1f8f84, #2bb3a5);
    border: none;
    color: #061216;
    font-weight: 600;
  }
  .session-list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
  }
  .session-item {
    padding: 10px 12px;
    border-radius: 10px;
    cursor: pointer;
    margin-bottom: 4px;
    border: 1px solid transparent;
  }
  .session-item:hover { background: var(--surface2); }
  .session-item.active {
    background: var(--surface2);
    border-color: var(--accent);
  }
  .session-item .title { font-size: 13px; font-weight: 600; }
  .session-item .preview {
    font-size: 11px;
    color: var(--text-dim);
    margin-top: 4px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .session-item .meta {
    display: flex;
    justify-content: space-between;
    margin-top: 6px;
    font-size: 10px;
    color: var(--text-dim);
  }
  .del {
    color: var(--danger);
    background: none;
    border: none;
    cursor: pointer;
    font-size: 11px;
  }
  .main {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 20px;
    border-bottom: 1px solid var(--border);
    background: rgba(22, 32, 40, 0.7);
    backdrop-filter: blur(8px);
  }
  .toggle {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    color: var(--text-dim);
  }
  .toggle input { accent-color: var(--accent); width: 16px; height: 16px; }
  .messages {
    flex: 1;
    overflow-y: auto;
    padding: 20px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .msg {
    max-width: 820px;
    width: 100%;
    align-self: flex-start;
  }
  .msg.user { align-self: flex-end; }
  .bubble {
    padding: 12px 14px;
    border-radius: 14px;
    line-height: 1.55;
    font-size: 14px;
    white-space: pre-wrap;
    word-break: break-word;
  }
  .msg.user .bubble {
    background: var(--user);
    border: 1px solid #2d5563;
  }
  .msg.assistant .bubble {
    background: var(--ai);
    border: 1px solid var(--border);
  }
  .msg .role {
    font-size: 11px;
    color: var(--text-dim);
    margin-bottom: 4px;
  }
  .tool-block, .agent-block, .plan-block {
    margin-top: 8px;
    padding: 8px 10px;
    border-radius: 8px;
    font-size: 12px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    background: var(--tool);
    border: 1px solid #3a4a28;
    color: #c8d9a8;
    white-space: pre-wrap;
  }
  .agent-block { border-color: #3a4a5a; background: #1a2834; color: #9ec9e0; }
  .plan-block { border-color: #5a4a28; background: #2a2418; color: #e8c98a; }
  .activity {
    max-width: 820px;
    margin: 0 0 8px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .status-row, .think-block, .step-row {
    color: var(--text-dim);
    font-size: 13px;
  }
  .status-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 4px 2px 8px;
  }
  .status-label.live, .think-title.live {
    background: linear-gradient(90deg, #6d8494 0%, #d7e6ef 45%, #6d8494 90%);
    background-size: 200% 100%;
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
    animation: shimmer 1.8s linear infinite;
  }
  @keyframes shimmer {
    from { background-position: 100% 0; }
    to { background-position: -100% 0; }
  }
  .spinner {
    width: 12px;
    height: 12px;
    border: 1.5px solid #3a4e5c;
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: spin .7s linear infinite;
    flex-shrink: 0;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  .step-icon {
    width: 14px;
    height: 14px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }
  .step-icon.done::before {
    content: "";
    width: 8px;
    height: 4px;
    border-left: 1.5px solid var(--ok);
    border-bottom: 1.5px solid var(--ok);
    transform: rotate(-45deg) translateY(-1px);
  }
  .think-block, .step-row {
    border: none;
    background: transparent;
  }
  .think-block > summary, .step-row > summary {
    list-style: none;
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 5px 2px;
    user-select: none;
  }
  .think-block > summary::-webkit-details-marker,
  .step-row > summary::-webkit-details-marker { display: none; }
  .think-block > summary::after, .step-row > summary::after {
    content: "";
    width: 6px;
    height: 6px;
    border-right: 1.5px solid var(--text-dim);
    border-bottom: 1.5px solid var(--text-dim);
    transform: rotate(-45deg);
    margin-left: 4px;
    opacity: .7;
  }
  .think-block[open] > summary::after, .step-row[open] > summary::after {
    transform: rotate(45deg);
  }
  .think-title, .step-label { color: var(--text-dim); }
  .think-block.done .think-title, .step-row.done .step-label { color: #9fb3c2; }
  .think-body, .step-detail {
    margin: 0 0 6px 22px;
    padding: 8px 10px;
    border-left: 1px solid #2a3b48;
    font-size: 12px;
    line-height: 1.55;
    color: #8aa0b0;
    white-space: pre-wrap;
    word-break: break-word;
    max-height: 240px;
    overflow: auto;
  }
  .step-detail { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  .msg.assistant .bubble.is-empty { display: none; }
  .approval-card {
    margin-top: 8px;
    padding: 12px 14px;
    border-radius: 10px;
    border: 1px solid var(--accent2);
    background: #2a2216;
    color: var(--text);
  }
  .approval-card .approval-title { font-weight: 600; color: var(--accent2); margin-bottom: 6px; font-size: 13px; }
  .approval-card pre {
    background: #1a1610;
    border-radius: 6px;
    padding: 8px;
    font-size: 12px;
    overflow-x: auto;
    margin: 6px 0 10px;
    white-space: pre-wrap;
    word-break: break-word;
  }
  .approval-actions { display: flex; gap: 8px; }
  .btn.approve { background: linear-gradient(135deg, #1f8f84, #2bb3a5); border: none; color: #061216; font-weight: 600; }
  .btn.reject { background: transparent; border: 1px solid var(--danger); color: var(--danger); }
  .approval-card.resolved { opacity: 0.6; }
  .approval-card .approval-result { margin-top: 8px; font-size: 12px; color: var(--text-dim); }
  .composer {
    padding: 14px 20px 18px;
    border-top: 1px solid var(--border);
    background: rgba(22, 32, 40, 0.85);
  }
  .hints {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 10px;
  }
  .hint {
    font-size: 12px;
    padding: 5px 10px;
    border-radius: 999px;
    border: 1px solid var(--border);
    color: var(--text-dim);
    cursor: pointer;
    background: transparent;
  }
  .hint:hover { border-color: var(--accent); color: var(--accent); }
  .input-row { display: flex; gap: 10px; }
  textarea {
    flex: 1;
    resize: none;
    height: 56px;
    border-radius: 12px;
    border: 1px solid var(--border);
    background: var(--surface2);
    color: var(--text);
    padding: 12px 14px;
    font-family: inherit;
    font-size: 14px;
  }
  textarea:focus { outline: none; border-color: var(--accent); }
  .empty {
    margin: auto;
    text-align: center;
    color: var(--text-dim);
    max-width: 420px;
  }
  .empty h2 {
    font-family: var(--font-display);
    color: var(--text);
    font-size: 28px;
    margin-bottom: 8px;
  }
  .user-id-box {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 10px;
    font-size: 12px;
    color: var(--text-dim);
  }
  .user-id-box input {
    flex: 1;
    border-radius: 6px;
    border: 1px solid var(--border);
    background: var(--surface2);
    color: var(--text);
    padding: 5px 8px;
    font-size: 12px;
    font-family: ui-monospace, monospace;
  }
  .user-id-box input:focus { outline: none; border-color: var(--accent); }
  .search-box {
    padding: 8px 8px 0;
  }
  .search-box input {
    width: 100%;
    border-radius: 8px;
    border: 1px solid var(--border);
    background: var(--surface2);
    color: var(--text);
    padding: 8px 10px;
    font-size: 13px;
    font-family: inherit;
  }
  .search-box input:focus { outline: none; border-color: var(--accent); }
  .search-hits {
    padding: 4px 8px 8px;
    max-height: 220px;
    overflow-y: auto;
    border-bottom: 1px solid var(--border);
  }
  .search-hit {
    padding: 8px 10px;
    border-radius: 8px;
    cursor: pointer;
    margin-bottom: 4px;
    background: var(--surface2);
    font-size: 12px;
  }
  .search-hit:hover { border: 1px solid var(--accent); padding: 7px 9px; }
  .search-hit .hit-title { font-weight: 600; color: var(--accent); }
  .search-hit .hit-snip { color: var(--text-dim); margin-top: 4px; }
  .drawer {
    position: fixed;
    top: 0; right: 0; bottom: 0;
    width: min(420px, 100%);
    background: rgba(22, 32, 40, 0.98);
    border-left: 1px solid var(--border);
    z-index: 30;
    display: none;
    flex-direction: column;
  }
  .drawer.open { display: flex; }
  .drawer-head {
    padding: 16px;
    border-bottom: 1px solid var(--border);
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .drawer-body { padding: 16px; overflow-y: auto; flex: 1; }
  .stat-row { display: flex; gap: 8px; margin-bottom: 14px; }
  .stat {
    flex: 1;
    background: var(--surface2);
    border-radius: 10px;
    padding: 10px;
    text-align: center;
  }
  .stat b { display: block; font-size: 18px; color: var(--accent); }
  .stat span { font-size: 11px; color: var(--text-dim); }
  .fact { font-size: 13px; padding: 6px 0; border-bottom: 1px solid var(--border); }
  .fact .cat { font-size: 11px; color: var(--accent2); }
  .md-preview { white-space: pre-wrap; font-size: 12px; color: var(--text-dim); line-height: 1.5; }
  .menu-btn { display: none; }
  @media (max-width: 800px) {
    .sidebar {
      position: absolute;
      z-index: 20;
      height: 100%;
      transform: translateX(-100%);
      transition: transform .2s;
    }
    .sidebar.open { transform: translateX(0); }
    .menu-btn { display: inline-block; }
  }
</style>
</head>
<body>
<aside class="sidebar" id="sidebar">
  <div class="sidebar-header">
    <div class="brand">小深<small>AgentScope 2.0 · 统筹 / research / general</small></div>
    <div class="sidebar-actions">
      <button class="btn primary" id="btnNew">新对话</button>
      <button class="btn" id="btnMemory">档案</button>
    </div>
    <div class="user-id-box">
      <label for="userIdInput">用户</label>
      <input id="userIdInput" type="text" placeholder="local" autocomplete="off" spellcheck="false">
    </div>
  </div>
  <div class="search-box">
    <input id="historySearch" type="search" placeholder="搜索历史对话…" autocomplete="off">
  </div>
  <div class="search-hits" id="searchHits" style="display:none;"></div>
  <div class="session-list" id="sessionList"></div>
</aside>

<section class="main">
  <header>
    <div style="display:flex;align-items:center;gap:10px;">
      <button class="btn menu-btn" id="btnMenu">菜单</button>
      <div>
        <div style="font-weight:600;" id="currentTitle">新对话</div>
        <div style="font-size:12px;color:var(--text-dim);" id="currentSession">未选择会话</div>
      </div>
    </div>
    <label class="toggle">
      <input type="checkbox" id="showTools" checked>
      显示思考与工具
    </label>
  </header>

  <div class="messages" id="messages">
    <div class="empty" id="emptyState">
      <h2>今天想做点什么？</h2>
      <p>每轮结束后，官方记忆管线会把稳定事实写入 memory/日期.md，再合并进 MEMORY.md。点左侧「档案」可查看。</p>
    </div>
  </div>

  <div class="composer">
    <div class="hints">
      <button class="hint" data-q="搜索一下 Deep Agents 框架是什么，并总结核心能力">联网调研</button>
      <button class="hint" data-q="对比一下 LangGraph 和 Deep Agents 的区别，列出要点并附来源">多源对比</button>
      <button class="hint" data-q="现在几点？帮我算一下 (18+7)*3">时间与计算</button>
    </div>
    <div class="input-row">
      <textarea id="input" placeholder="输入消息，Enter 发送，Shift+Enter 换行"></textarea>
      <button class="btn primary" id="btnSend" style="min-width:88px;">发送</button>
    </div>
  </div>
</section>

<aside class="drawer" id="memoryDrawer">
  <div class="drawer-head">
    <div>
      <div style="font-weight:600;">我的档案</div>
      <div style="font-size:12px;color:var(--text-dim);">官方 MEMORY.md + 使用概况</div>
    </div>
    <button class="btn" id="btnCloseMemory">关闭</button>
  </div>
  <div class="drawer-body" id="memoryBody"></div>
</aside>

<script>
const state = {
  userId: localStorage.getItem('pa_userId') || 'local',
  sessionId: localStorage.getItem('pa_sessionId') || null,
  showTools: true,
  streaming: false,
  abort: null, // AbortController：切会话/新对话时取消上一次 SSE
  pendingApproval: false, // 有一个 interrupt 卡片等待用户批准/拒绝时，禁止继续发消息
};

const el = {
  list: document.getElementById('sessionList'),
  messages: document.getElementById('messages'),
  empty: document.getElementById('emptyState'),
  input: document.getElementById('input'),
  showTools: document.getElementById('showTools'),
  currentTitle: document.getElementById('currentTitle'),
  currentSession: document.getElementById('currentSession'),
  sidebar: document.getElementById('sidebar'),
  btnSend: document.getElementById('btnSend'),
  historySearch: document.getElementById('historySearch'),
  searchHits: document.getElementById('searchHits'),
  memoryDrawer: document.getElementById('memoryDrawer'),
  memoryBody: document.getElementById('memoryBody'),
  userIdInput: document.getElementById('userIdInput'),
};

el.showTools.checked = localStorage.getItem('pa_showTools') !== 'false';
state.showTools = el.showTools.checked;
el.userIdInput.value = state.userId;

el.userIdInput.addEventListener('change', () => {
  const v = el.userIdInput.value.trim() || 'local';
  state.userId = v;
  localStorage.setItem('pa_userId', v);
  // 切用户后清空当前会话，避免看到别的用户的数据
  newChat();
});

el.showTools.addEventListener('change', () => {
  state.showTools = el.showTools.checked;
  localStorage.setItem('pa_showTools', state.showTools);
  document.querySelectorAll('.tool-block,.agent-block,.plan-block,.step-row,.think-block').forEach(n => {
    n.style.display = state.showTools ? '' : 'none';
  });
});

document.getElementById('btnMenu').onclick = () => el.sidebar.classList.toggle('open');
document.getElementById('btnNew').onclick = () => newChat();
document.getElementById('btnSend').onclick = () => send();
document.getElementById('btnMemory').onclick = () => openMemory();
document.getElementById('btnCloseMemory').onclick = () => el.memoryDrawer.classList.remove('open');

let searchTimer = null;
el.historySearch.addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => runHistorySearch(el.historySearch.value), 280);
});
document.querySelectorAll('.hint').forEach(b => b.onclick = () => {
  el.input.value = b.dataset.q;
  send();
});
el.input.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    send();
  }
});

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

function mdLite(text) {
  let t = escapeHtml(text);
  t = t.replace(/```([\s\S]*?)```/g, '<pre>$1</pre>');
  t = t.replace(/`([^`]+)`/g, '<code>$1</code>');
  t = t.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  t = t.replace(/^### (.+)$/gm, '<div style="font-weight:700;margin:6px 0 2px;">$1</div>');
  t = t.replace(/\|(.+)\|/g, m => m); // keep tables as preformatted via whitespace
  return t;
}

function clearMessages() {
  el.messages.innerHTML = '';
  el.messages.appendChild(el.empty);
  el.empty.style.display = 'block';
}

function ensureEmptyHidden() {
  el.empty.style.display = 'none';
}

function appendBubble(role, content) {
  ensureEmptyHidden();
  const wrap = document.createElement('div');
  wrap.className = 'msg ' + role;
  const body = role === 'assistant' ? mdLite(content) : escapeHtml(content);
  wrap.innerHTML = `<div class="role">${role === 'user' ? '你' : '助手'}</div>
    <div class="activity"></div>
    <div class="bubble">${body}</div>
    <div class="extras"></div>`;
  const bubble = wrap.querySelector('.bubble');
  if (role === 'assistant') {
    bubble.dataset.raw = content || '';
    if (!content) bubble.classList.add('is-empty');
  }
  el.messages.appendChild(wrap);
  el.messages.scrollTop = el.messages.scrollHeight;
  return wrap;
}

function appendAssistantShell() {
  const wrap = appendBubble('assistant', '');
  showStatus(wrap, '思考中');
  return wrap;
}

function activityOf(wrap) {
  let act = wrap.querySelector('.activity');
  if (!act) {
    act = document.createElement('div');
    act.className = 'activity';
    const bubble = wrap.querySelector('.bubble');
    wrap.insertBefore(act, bubble || wrap.querySelector('.extras'));
  }
  return act;
}

function showStatus(wrap, label) {
  const act = activityOf(wrap);
  let row = act.querySelector('.status-row');
  if (!row) {
    row = document.createElement('div');
    row.className = 'status-row';
    act.insertBefore(row, act.firstChild);
  }
  row.innerHTML = `<span class="spinner"></span><span class="status-label live">${escapeHtml(label)}</span>`;
}

function hideStatus(wrap) {
  const row = wrap.querySelector('.status-row');
  if (row) row.remove();
}

function parseJson(data, fallback) {
  if (data && typeof data === 'object') return data;
  try { return JSON.parse(data); } catch (e) { return fallback || {}; }
}

const TOOL_LABELS = {
  webSearchPrime: '联网搜索',
  webReader: '阅读网页',
  web_search: '联网搜索',
  web_fetch: '读取网页',
  calculate: '计算',
  getCurrentDateTime: '查询时间',
  todo_write: '更新计划',
  agent_spawn: '委派子任务',
  agent_send: '联系子任务',
  read_file: '读取文件',
  write_file: '写入文件',
  edit_file: '编辑文件',
  list_files: '列出文件',
  memory_search: '检索记忆',
  memory_get: '读取记忆',
  memory_save: '保存记忆',
  session_search: '检索会话日志',
  search_conversation_history: '检索对话',
  get_user_usage: '查看使用概况',
  skill_manage: '管理技能',
};

function toolLabel(name) {
  if (!name) return '调用工具';
  return TOOL_LABELS[name] || ('调用 ' + name);
}

function ensureThink(wrap, startNew) {
  const act = activityOf(wrap);
  let block = act.querySelector('.think-block.live');
  if (!block || startNew) {
    if (block) finishThink(block);
    hideStatus(wrap);
    block = document.createElement('details');
    block.className = 'think-block live';
    block.open = true;
    block.innerHTML = `<summary>
        <span class="step-icon"><span class="spinner"></span></span>
        <span class="think-title live">思考中</span>
      </summary>
      <div class="think-body"></div>`;
    if (!state.showTools) block.style.display = 'none';
    act.appendChild(block);
  }
  return block;
}

function appendThinkDelta(wrap, text) {
  if (!text) return;
  const block = ensureThink(wrap, false);
  const body = block.querySelector('.think-body');
  body.textContent = (body.textContent || '') + text;
  el.messages.scrollTop = el.messages.scrollHeight;
}

function finishThink(block) {
  if (!block) return;
  block.classList.remove('live');
  block.classList.add('done');
  const title = block.querySelector('.think-title');
  if (title) {
    title.classList.remove('live');
    title.textContent = block.querySelector('.think-body')?.textContent?.trim()
      ? '已思考' : '已思考';
  }
  const icon = block.querySelector('.step-icon');
  if (icon) icon.innerHTML = '';
  icon && icon.classList.add('done');
  const body = block.querySelector('.think-body');
  if (!body || !body.textContent.trim()) {
    if (body) body.remove();
    block.open = false;
  } else {
    block.open = false;
  }
}

function renderThinkDone(wrap, text) {
  if (!text) return;
  hideStatus(wrap);
  const act = activityOf(wrap);
  const block = document.createElement('details');
  block.className = 'think-block done';
  block.innerHTML = `<summary>
      <span class="step-icon done"></span>
      <span class="think-title">已思考</span>
    </summary>
    <div class="think-body">${escapeHtml(text)}</div>`;
  if (!state.showTools) block.style.display = 'none';
  act.appendChild(block);
}

function upsertStep(wrap, payload, kind) {
  hideStatus(wrap);
  const name = payload.tool || payload.name || payload.event || '工具';
  const id = payload.id || name;
  const key = (kind || 'tool') + ':' + id;
  const act = activityOf(wrap);
  let row = Array.from(act.querySelectorAll('.step-row')).find(n => n.dataset.step === key);
  const running = payload.phase !== 'end' && payload.state !== 'ERROR';
  if (!row) {
    row = document.createElement('details');
    row.className = 'step-row ' + (kind || 'tool');
    row.dataset.step = key;
    row.innerHTML = `<summary>
        <span class="step-icon">${running ? '<span class="spinner"></span>' : ''}</span>
        <span class="step-label"></span>
      </summary>
      <pre class="step-detail"></pre>`;
    if (!state.showTools) row.style.display = 'none';
    act.appendChild(row);
  }
  const label = row.querySelector('.step-label');
  label.textContent = kind === 'agent'
    ? ('子任务' + (payload.name ? ' · ' + payload.name : ''))
    : (kind === 'plan' ? '更新计划' : toolLabel(name));
  const icon = row.querySelector('.step-icon');
  const done = payload.phase === 'end' || payload.state === 'ERROR' || payload.state === 'DENIED';
  if (done) {
    row.classList.add('done');
    row.classList.remove('live');
    icon.classList.add('done');
    icon.innerHTML = '';
    if (payload.state === 'ERROR' || payload.state === 'DENIED') {
      label.textContent += payload.state === 'DENIED' ? '（已拒绝）' : '（失败）';
    }
  } else {
    row.classList.add('live');
    if (!icon.querySelector('.spinner')) icon.innerHTML = '<span class="spinner"></span>';
  }
  const detail = row.querySelector('.step-detail');
  const extra = payload.args || payload.data || payload.event;
  if (extra && typeof extra === 'object') {
    detail.textContent = JSON.stringify(extra, null, 2);
  } else if (typeof extra === 'string' && extra && extra.charAt(0) === '{') {
    try { detail.textContent = JSON.stringify(JSON.parse(extra), null, 2); }
    catch (e) { detail.textContent = extra; }
  } else if (payload.tool || payload.name) {
    detail.textContent = payload.tool || payload.name;
  }
  el.messages.scrollTop = el.messages.scrollHeight;
}

function finishActivity(wrap) {
  hideStatus(wrap);
  wrap.querySelectorAll('.think-block.live').forEach(finishThink);
  wrap.querySelectorAll('.step-row.live').forEach(row => {
    row.classList.remove('live');
    row.classList.add('done');
    const icon = row.querySelector('.step-icon');
    if (icon) {
      icon.classList.add('done');
      icon.innerHTML = '';
    }
  });
}

function appendMeta(wrap, cls, text) {
  if (!state.showTools) {
    const hidden = document.createElement('div');
    hidden.className = cls;
    hidden.style.display = 'none';
    hidden.textContent = text;
    wrap.querySelector('.extras').appendChild(hidden);
    return;
  }
  const div = document.createElement('div');
  div.className = cls;
  div.textContent = text;
  wrap.querySelector('.extras').appendChild(div);
  el.messages.scrollTop = el.messages.scrollHeight;
}

async function loadSessions() {
  const res = await fetch('/api/sessions?userId=' + encodeURIComponent(state.userId));
  const list = await res.json();
  el.list.innerHTML = '';
  list.forEach(s => {
    const item = document.createElement('div');
    item.className = 'session-item' + (s.id === state.sessionId ? ' active' : '');
    item.innerHTML = `<div class="title">${escapeHtml(s.title || '未命名')}</div>
      <div class="preview">${escapeHtml(s.preview || '')}</div>
      <div class="meta"><span>${s.messageCount || 0} 条</span>
      <button class="del" data-id="${s.id}">删除</button></div>`;
    item.onclick = (e) => {
      if (e.target.classList.contains('del')) return;
      openSession(s.id);
    };
    item.querySelector('.del').onclick = async (e) => {
      e.stopPropagation();
      await fetch('/api/sessions/' + s.id + '?userId=' + encodeURIComponent(state.userId), { method: 'DELETE' });
      if (state.sessionId === s.id) newChat();
      loadSessions();
    };
    el.list.appendChild(item);
  });
}

async function runHistorySearch(q) {
  const query = (q || '').trim();
  if (!query) {
    el.searchHits.style.display = 'none';
    el.searchHits.innerHTML = '';
    return;
  }
  const res = await fetch('/api/history/search?q=' + encodeURIComponent(query) + '&limit=12&userId=' + encodeURIComponent(state.userId));
  const data = await res.json();
  const hits = data.hits || [];
  if (!hits.length) {
    el.searchHits.style.display = 'block';
    el.searchHits.innerHTML = '<div class="search-hit">没有找到「' + escapeHtml(query) + '」</div>';
    return;
  }
  el.searchHits.style.display = 'block';
  el.searchHits.innerHTML = '';
  hits.forEach(h => {
    const div = document.createElement('div');
    div.className = 'search-hit';
    div.innerHTML = `<div class="hit-title">${escapeHtml(h.sessionTitle || '会话')}</div>
      <div class="hit-snip">${escapeHtml(h.snippet || '')}</div>`;
    div.onclick = () => openSession(h.sessionId);
    el.searchHits.appendChild(div);
  });
}

function categoryLabel(c) {
  return ({preference:'偏好', working_style:'习惯', identity:'关于你', project:'项目', constraint:'约束', fact:'事实'})[c] || c;
}

async function openMemory() {
  el.memoryDrawer.classList.add('open');
  el.memoryBody.textContent = '加载中…';
  try {
    const res = await fetch('/api/memory?userId=' + encodeURIComponent(state.userId));
    const p = await res.json();
    const u = p.usage || {};
    const facts = p.facts || [];
    const dailies = p.dailyLedgers || [];
    el.memoryBody.innerHTML = `
      <div class="stat-row">
        <div class="stat"><b>${u.sessionCount || 0}</b><span>会话</span></div>
        <div class="stat"><b>${u.userMessageCount || 0}</b><span>你说的话</span></div>
        <div class="stat"><b>${dailies.length}</b><span>日流水文件</span></div>
      </div>
      <h3 style="margin:8px 0 6px;font-size:14px;">已沉淀的长期记忆</h3>
      ${facts.length ? facts.map(f => `<div class="fact"><span class="cat">${escapeHtml(categoryLabel(f.category))}</span> ${escapeHtml(f.content)}</div>`).join('') : '<div class="fact">还没有。聊过偏好/项目后，官方 Flush 会写入 memory/日期.md，再合并进 MEMORY.md。</div>'}
      <h3 style="margin:16px 0 6px;font-size:14px;">MEMORY.md 原文</h3>
      <div class="md-preview">${escapeHtml(p.memoryMarkdown || '（空）')}</div>
    `;
  } catch (e) {
    el.memoryBody.textContent = '加载失败: ' + e.message;
  }
}

function resetToNewChat() {
  state.sessionId = null;
  localStorage.removeItem('pa_sessionId');
  el.currentTitle.textContent = '新对话';
  el.currentSession.textContent = '未选择会话';
  state.pendingApproval = false;
  el.btnSend.disabled = false;
  clearMessages();
}

async function openSession(id) {
  if (state.abort) {
    state.abort.abort();
    state.abort = null;
  }
  state.streaming = false;
  state.sessionId = id;
  localStorage.setItem('pa_sessionId', id);
  const res = await fetch('/api/sessions/' + encodeURIComponent(id) + '?userId=' + encodeURIComponent(state.userId));
  let detail = null;
  try {
    detail = await res.json();
  } catch (e) {
    detail = null;
  }
  if (!res.ok || (detail && detail.error)) {
    const err = (detail && detail.error) ? String(detail.error) : '';
    const stale = err.includes('会话不存在') || err.includes('非法 sessionId') || res.status === 404;
    if (stale) {
      resetToNewChat();
      return;
    }
    clearMessages();
    appendBubble('assistant', '加载会话失败：' + (err || ('HTTP ' + res.status)));
    return;
  }
  el.currentTitle.textContent = detail.title || '对话';
  el.currentSession.textContent = id;
  clearMessages();
  state.pendingApproval = false;
  el.btnSend.disabled = false;
  const messages = detail.messages || [];
  messages.forEach((m, idx) => {
    if (m.role === 'user' || m.role === 'assistant') {
      const wrap = appendBubble(m.role, m.content || '');
      const isLast = idx === messages.length - 1;
      (m.events || []).forEach(ev => replayEvent(wrap, ev, isLast && detail.pendingApproval));
    }
  });
  loadSessions();
  el.sidebar.classList.remove('open');
}

function newChat() {
  if (state.abort) {
    state.abort.abort();
    state.abort = null;
  }
  state.streaming = false;
  state.sessionId = null;
  state.pendingApproval = false;
  el.btnSend.disabled = false;
  localStorage.removeItem('pa_sessionId');
  el.currentTitle.textContent = '新对话';
  el.currentSession.textContent = '未选择会话';
  clearMessages();
  loadSessions();
}

/**
 * 消费一个 SSE 响应体，把每个 event/data 对交给 onEvent。
 * chat / resume 两个入口共用同一套解析逻辑。
 */
async function consumeSse(res, onEvent) {
  if (!res.body) {
    throw new Error('响应无正文');
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  let eventName = 'message';
  let dataLines = [];
  const dispatch = () => {
    if (dataLines.length) {
      onEvent(eventName, dataLines.join('\n'));
    }
    eventName = 'message';
    dataLines = [];
  };
  const handleLine = (line) => {
    if (line === '' || line === '\r') {
      dispatch();
      return;
    }
    const raw = line.endsWith('\r') ? line.slice(0, -1) : line;
    if (raw.startsWith('event:')) {
      eventName = raw.slice(6).trim();
    } else if (raw.startsWith('data:')) {
      dataLines.push(raw.slice(5).replace(/^ /, ''));
    }
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const chunks = buf.split('\n');
    buf = chunks.pop();
    chunks.forEach(handleLine);
  }
  if (buf.trim()) {
    buf.split('\n').forEach(handleLine);
  }
  dispatch();
}

async function send() {
  const text = el.input.value.trim();
  if (!text || state.streaming || state.pendingApproval) return;
  state.streaming = true;
  el.input.value = '';
  appendBubble('user', text);
  const aiWrap = appendAssistantShell();
  const bubble = aiWrap.querySelector('.bubble');

  const abort = new AbortController();
  state.abort = abort;

  try {
    const res = await fetch('/api/assistant/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: state.userId, sessionId: state.sessionId, message: text, showTools: state.showTools }),
      signal: abort.signal
    });
    if (!res.ok) {
      bubble.classList.remove('is-empty');
      bubble.textContent = '请求失败 HTTP ' + res.status;
      hideStatus(aiWrap);
      return;
    }
    await consumeSse(res, (event, data) => handleSse(event, data, aiWrap, bubble));
    finishActivity(aiWrap);
  } catch (e) {
    if (e.name !== 'AbortError') {
      bubble.classList.remove('is-empty');
      bubble.textContent = '请求失败: ' + e.message;
      hideStatus(aiWrap);
    }
  } finally {
    if (state.abort === abort) state.abort = null;
    state.streaming = false;
    loadSessions();
  }
}

/** 用户在审批卡片上点了批准/拒绝：POST /api/assistant/resume，续跑同一个统筹图。 */
async function resumeApproval(card, approved) {
  if (!state.sessionId || state.streaming) return;
  card.querySelectorAll('button').forEach(b => b.disabled = true);
  state.pendingApproval = false;
  state.streaming = true;
  el.btnSend.disabled = false;
  el.btnSend.title = '';

  const aiWrap = card.closest('.msg');
  const bubble = aiWrap.querySelector('.bubble');
  showStatus(aiWrap, approved ? '继续执行' : '调整方案');
  const resultLine = document.createElement('div');
  resultLine.className = 'approval-result';
  resultLine.textContent = approved ? '✅ 已批准，继续执行…' : '🚫 已拒绝，让模型调整方案…';
  card.appendChild(resultLine);
  card.classList.add('resolved');

  const abort = new AbortController();
  state.abort = abort;
  try {
    const res = await fetch('/api/assistant/resume', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: state.userId, sessionId: state.sessionId, approved }),
      signal: abort.signal
    });
    if (!res.ok) {
      resultLine.textContent += '（续跑请求失败 HTTP ' + res.status + '）';
      hideStatus(aiWrap);
      return;
    }
    await consumeSse(res, (event, data) => handleSse(event, data, aiWrap, bubble));
    finishActivity(aiWrap);
  } catch (e) {
    if (e.name !== 'AbortError') {
      resultLine.textContent += '（续跑失败: ' + e.message + '）';
    }
  } finally {
    if (state.abort === abort) state.abort = null;
    state.streaming = false;
    loadSessions();
  }
}

/** 渲染一个待审批卡片（write_file/edit_file 等破坏性工具执行前的人工确认）。 */
function appendApprovalCard(aiWrap, payload) {
  ensureEmptyHidden();
  const card = document.createElement('div');
  card.className = 'approval-card';
  const tool = payload.tool || '未知工具';
  const args = payload.args ? JSON.stringify(payload.args, null, 2) : '';
  card.innerHTML = `<div class="approval-title">⏸ 等待人工审批：${escapeHtml(tool)}</div>
    ${args ? `<pre>${escapeHtml(args)}</pre>` : ''}
    <div class="approval-actions">
      <button class="btn approve">批准执行</button>
      <button class="btn reject">拒绝</button>
    </div>`;
  card.querySelector('.approve').onclick = () => resumeApproval(card, true);
  card.querySelector('.reject').onclick = () => resumeApproval(card, false);
  aiWrap.querySelector('.extras').appendChild(card);
  el.messages.scrollTop = el.messages.scrollHeight;
  state.pendingApproval = true;
  el.btnSend.disabled = true;
  el.btnSend.title = '有待审批的操作，请先在上方批准或拒绝';
}

function replayEvent(wrap, ev, pendingInterrupt) {
  const type = ev.type;
  if (type === 'thinking') {
    renderThinkDone(wrap, ev.text || '');
    return;
  }
  const payload = Object.assign({}, ev, parseJson(ev.data, {}));
  if (type === 'plan' || type === 'tool' || type === 'tool_start' || type === 'tool_end') {
    payload.phase = payload.phase || 'end';
    upsertStep(wrap, payload, type === 'plan' ? 'plan' : 'tool');
    return;
  }
  if (type === 'agent' || type === 'agent_start' || type === 'agent_end') {
    payload.phase = payload.phase || 'end';
    upsertStep(wrap, payload, 'agent');
    return;
  }
  if (type === 'interrupt') {
    if (pendingInterrupt) {
      appendApprovalCard(wrap, payload);
    } else if (state.showTools) {
      upsertStep(wrap, Object.assign({phase: 'end', state: 'DENIED'}, payload), 'tool');
    }
  }
}

function handleSse(event, data, aiWrap, bubble) {
  if (event === 'session') {
    state.sessionId = data;
    localStorage.setItem('pa_sessionId', data);
    el.currentSession.textContent = data;
  } else if (event === 'status') {
    const payload = parseJson(data, {});
    if (!aiWrap.querySelector('.think-block.live') && !aiWrap.querySelector('.step-row.live')) {
      showStatus(aiWrap, payload.label === 'thinking' ? '思考中' : (payload.label || '处理中'));
    }
  } else if (event === 'thinking') {
    const payload = parseJson(data, {});
    if (payload.phase === 'start') {
      ensureThink(aiWrap, true);
    } else if (payload.phase === 'delta') {
      appendThinkDelta(aiWrap, payload.text || '');
    } else if (payload.phase === 'end') {
      const live = aiWrap.querySelector('.think-block.live');
      if (live) finishThink(live);
    }
  } else if (event === 'token') {
    hideStatus(aiWrap);
    const live = aiWrap.querySelector('.think-block.live');
    if (live) finishThink(live);
    bubble.classList.remove('is-empty');
    bubble.innerHTML = mdLite((bubble.dataset.raw || '') + data);
    bubble.dataset.raw = (bubble.dataset.raw || '') + data;
    el.messages.scrollTop = el.messages.scrollHeight;
  } else if (event === 'plan') {
    upsertStep(aiWrap, parseJson(data, {tool: 'todo_write', phase: 'start'}), 'plan');
  } else if (event === 'tool') {
    upsertStep(aiWrap, parseJson(data, {phase: 'start'}), 'tool');
  } else if (event === 'agent') {
    upsertStep(aiWrap, parseJson(data, {phase: 'start'}), 'agent');
  } else if (event === 'interrupt') {
    hideStatus(aiWrap);
    finishActivity(aiWrap);
    appendApprovalCard(aiWrap, parseJson(data, {}));
  } else if (event === 'error') {
    hideStatus(aiWrap);
    bubble.classList.remove('is-empty');
    bubble.textContent = (bubble.dataset.raw || '') + '\n[错误] ' + data;
  } else if (event === 'done') {
    finishActivity(aiWrap);
  }
}

loadSessions();
if (state.sessionId) openSession(state.sessionId);
</script>
</body>
</html>
````

## 十一、工作区模板与仓库文件

这些文件在 `workspace/` 和仓库根目录，启动后会被 Harness 读进系统提示 / 技能 / 子 Agent。`MEMORY.md` 在 `.gitignore` 中，运行时会被 Consolidation 整文件重写；下面贴的是仓库里的占位稿，不含用户私有记忆。

### `workspace/MEMORY.md`

**作用：** 长期记忆占位；Consolidation 会按框架规则整文件重写

```markdown
# 长期记忆

（官方 MemoryConsolidator 会把 `memory/YYYY-MM-DD.md` 日流水合并到这里。
请不要手改后指望自动抽取覆盖——Consolidation 会按框架规则整文件重写。）
```

### `workspace/AGENTS.md`

**作用：** 统筹人格与工具选择优先级

```markdown
你是私人智能助手「小深」的统筹 Agent：负责理解目标、必要时规划与委派，并汇总成对用户的最终答复。

## 行为风格
- 简洁直接；不要客套开场，不要预告「我现在去做 X」——直接调用工具或作答
- 用清晰中文回答；条目化优于长段落
- 准确性优先：不确定就标明不确定性，不要编造事实、数据或链接
- 缺推进下一步所必需的关键信息时，只追问最少的一点
- 长任务可简短汇报进度（一句：已完成什么 / 下一步做什么）

## 记忆怎么用（AgentScope 2.0 官方两层）
系统会在每轮对话结束后**自动**把稳定事实写入 `memory/YYYY-MM-DD.md`，再定期合并进 `MEMORY.md`。
你不必每轮都 memory_save。用户说「记住这个」时才主动 memory_save。

1. 回答「你还记得我吗 / 我的偏好」：先看已经注入的 MEMORY.md；不够再 `memory_search` 或 `get_user_usage`
2. 回答「我上次原话怎么说的」：`search_conversation_history`（网页会话）或 `session_search`（压缩前卸载的 jsonl）
3. 不要编造用户没说过的偏好

## 怎么选路径（按优先级）
1. **直接回答**：闲聊、定义解释、已有上下文足够的问题 —— 不用工具
2. **轻量工具**：只需当前时间或算术 —— 用 getCurrentDateTime / calculate
3. **联网**：用 `webSearchPrime` 搜索、`webReader` 打开链接（智谱 MCP）。不要找 `web_search` / `browser` / `web_fetch`，那些已关闭。简单查询直接调这两个工具；不要编造搜索结果
4. **规划**：≥3 步、多目标、或用户明确要求清单 —— 用 todo_write 或 Plan Mode；简单任务不要为了规划而规划
5. **委派**：需要多角度交叉验证的调研 —— 用 agent_spawn 调 research-agent（它同样使用 webSearchPrime / webReader）
6. **工作区**：需要落盘长文、草稿、中间结果 —— 用 read_file / write_file / edit_file / list_files（write_file / edit_file 会触发人工审批）
7. **技能**：需要某个技能细节时按需加载；值得沉淀的做法可以写成技能草稿

## 如何写好 agent_spawn 委派
- task 必须写清：目标、约束、期望输出格式（例如「分点结论 + 来源链接」）
- agent_id 必须使用可用子 Agent 名称（常见：research-agent / general-purpose）
- 同步等待结果：timeout_seconds 用 60～120；不要把调研改成后台任务除非用户明确说可以稍后再看
- 收到子 Agent 结果后：综合、去噪、核对冲突；用你的话写最终答复，不要整段粘贴原始工具输出
- 子 Agent 失败或结果不足：换描述重试一次，或改派，或如实告知用户卡点

## 交付要求
- 最终答复面向用户：先给结论/答案，再补依据与来源
- 有 todos 时，完成前对照清单；全部完成后仍须输出实质内容
- 必须通过平台函数调用使用工具，禁止在正文里用 JSON/伪代码假装调工具
```

### `workspace/PREFERENCES.md`

**作用：** 额外注入的用户档案（additionalContextFile）

```markdown
# 用户档案

- 沟通语言：中文
- 回答风格：简洁、有条理
- 偏好：尚未记录（可通过对话告诉助手）
```

### `workspace/knowledge/KNOWLEDGE.md`

**作用：** 相对稳定的项目知识，和 MEMORY.md 分开

```markdown
# 领域知识

把相对稳定、需要每轮都看见的项目知识放这里。长期「关于这个用户」的事实走 MEMORY.md，不要混在一起。
```

### `workspace/skills/weekly-report/SKILL.md`

**作用：** 周报技能示例

```markdown
---
name: weekly-report
description: 周报撰写规范：固定章节、字数与语气。需要写周报时先加载本技能再按规范落盘。
---

# 周报技能

写周报时必须使用以下结构，不要自由发挥章节名：

1. **本周进展**（3～5 条，每条一句话）
2. **风险与阻塞**（没有就写「无」）
3. **下周计划**（2～4 条）
4. **需要协同**（点名角色/部门，没有就写「无」）

约束：
- 全文不超过 400 字
- 用条目，不用长段落
- 不要出现「好的」「我来帮你」等客套
- 落盘路径约定：`reports/weekly-YYYYMMDD.md`（日期用当天）
```

### `workspace/subagents/research-agent.md`

**作用：** 官方子 Agent 声明（YAML frontmatter + 正文）。Harness `DynamicSubagentsMiddleware` Layer 2 自动扫描本目录，**无需** Java `.subagents(...)`。

```markdown
---
description: 复杂联网调研：多角度搜索、交叉验证、带来源的结论摘要（智谱 Web Search MCP）
workspace:
  mode: isolated
tools: [webSearchPrime, webReader, getCurrentDateTime, calculate]
maxIters: 25
---

你是 research-agent——复杂联网查询专科子 Agent（ephemeral leaf）。

联网工具由父 Agent Toolkit 继承：`webSearchPrime`（搜索）、`webReader`（读网页）。
这两个是只读工具，**不需要人工审批**；直接调用即可。
本子 Agent 是 isolated 工作区，**不要**根据这里有没有 `tools.json` 判断工具是否可用。
函数列表里有这两个工具时必须直接调用；禁止声称「工具未加载」后用训练知识编造调研。

## 工作流（必须遵守）
1. 把用户子任务拆成 2~5 个可检索角度（不同关键词 / 时间 / 来源侧重点）
2. 对每个角度调用 webSearchPrime；需要深读时用 webReader
3. 交叉比对多源结果，标出一致点与冲突点
4. 输出结构化结论（最终回复会回传父 Agent「小深」）：
   - 核心结论（分点）
   - 证据与来源链接（真实来自工具结果，禁止编造）
   - 时效性说明（何时的信息）
   - 不确定性 / 仍待核实项

## 约束
- 只完成统筹分配的这一个子任务；做完即止，不要扮演主助手
- **不要**维护或写入长期记忆（MEMORY.md / memory/ 日流水）；持久事实由父 Agent 负责
- 搜索词尽量具体；必要时换关键词重搜，不要用同一词盲目重试超过 2 次
- 没有检索到就如实说明，不要编造链接或数据
```

### `workspace/tools.json`

**作用：** 官方工具 deny + MCP server。`getenv` 有密钥时 `ToolsConfigLoader` 读文件；否则 yml 密钥替换 `${MCP_API_KEY}` 后 `toolsConfig()`。传输用智谱 SSE 回退（Java SDK 对 `/mcp` GET 会 405）。

```json
{
  "deny": ["web_search", "web_fetch", "execute"],
  "mcpServers": {
    "zhipu-web-search": {
      "transport": "sse",
      "url": "https://open.bigmodel.cn/api/mcp/web_search_prime/sse?Authorization=${MCP_API_KEY}",
      "timeout": "PT60S"
    },
    "zhipu-web-reader": {
      "transport": "sse",
      "url": "https://open.bigmodel.cn/api/mcp/web_reader/sse?Authorization=${MCP_API_KEY}",
      "timeout": "PT60S"
    }
  }
}
```

### `.gitignore`

**作用：** 忽略编译产物与工作区运行时文件（记忆 / 会话走 Redis）

```text
target/
.idea/
*.iml
.classpath
.project
.settings/
.DS_Store
*.log
.env
# Harness 运行时产物（记忆 / 会话 / 子 Agent 工作区走 Redis，本地仅可能留下索引）
# workspace/agents/ = 运行时隔离目录；声明文件在 workspace/subagents/*.md（应提交）
workspace/.index/
workspace/agents/
workspace/memory/*.md
!workspace/memory/.gitkeep
workspace/MEMORY.md
workspace/sessions/
workspace/plans/*.md
!workspace/plans/.gitkeep
```

## 十二、测试

`mvn test` 覆盖 `tools.json` MCP 声明、MDC、记忆解析、会话检索，以及本机 Redis / MySQL 集成（Redis 只 FLUSHDB **db=15**，MySQL 只用 `agentscope_assistant_test`）。

### `src/test/java/cn/deepassistant/integration/mcp/ToolsJsonTest.java`

**作用：** 断言 `workspace/tools.json` 声明智谱 MCP SSE 回退，且可反序列化为官方 `ToolsConfig`。不要写 `allow`。

```java
package cn.deepassistant.integration.mcp;

class ToolsJsonTest {

    @Test
    void officialMcpServersAreDeclared() throws Exception {
        Path file = Path.of("workspace/tools.json");
        assertTrue(Files.isRegularFile(file), "workspace/tools.json 应存在");
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        assertTrue(root.path("allow").isMissingNode() || root.path("allow").isEmpty(),
                "不要用 allow 白名单，否则 Harness 内置工具会被一起砍掉");
        assertTrue(root.path("deny").toString().contains("web_search"));
        JsonNode servers = root.path("mcpServers");
        assertTrue(servers.has("zhipu-web-search"));
        assertTrue(servers.has("zhipu-web-reader"));
        JsonNode search = servers.get("zhipu-web-search");
        assertEquals("sse", search.path("transport").asText());
        assertTrue(search.path("url").asText().contains("web_search_prime/sse"));
        assertTrue(search.path("url").asText().contains("${MCP_API_KEY}"));
        assertTrue(servers.get("zhipu-web-reader").path("url").asText().contains("web_reader/sse"));
    }

    @Test
    void toolsJsonParsesAsOfficialToolsConfig() throws Exception {
        String raw = Files.readString(Path.of("workspace/tools.json"));
        ToolsConfig cfg = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .readValue(raw, ToolsConfig.class);
        assertNotNull(cfg.getMcpServers());
        assertEquals(2, cfg.getMcpServers().size());
        assertTrue(cfg.getDeny().contains("web_search"));
        assertEquals(Duration.parse("PT60S"), cfg.getMcpServers().get("zhipu-web-search").getTimeout());
        assertEquals("sse", cfg.getMcpServers().get("zhipu-web-search").getTransport());
        assertTrue(cfg.getMcpServers().get("zhipu-web-search").getUrl().contains("/sse?Authorization=${MCP_API_KEY}"));
    }
}
```

### `src/test/java/cn/deepassistant/config/WorkspaceRootsTest.java`

**作用：** 配置路径已有种子则沿用；否则回退到模块 `workspace/`。

```java
package cn.deepassistant.config;

class WorkspaceRootsTest {

    @TempDir
    Path temp;

    @Test
    void keepsConfiguredPathWhenItAlreadyHasSeeds() throws Exception {
        Path ws = temp.resolve("workspace");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("tools.json"), "{}");
        Path resolved = WorkspaceRoots.resolve(ws.toString(), WorkspaceRoots.class);
        assertEquals(ws.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void fallsBackToModuleWorkspaceWhenConfiguredPathHasNoSeeds() {
        Path bogus = temp.resolve("agentscope").resolve("workspace");
        Path resolved = WorkspaceRoots.resolve(bogus.toString(), WorkspaceRoots.class);
        assertTrue(Files.isRegularFile(resolved.resolve("tools.json")), resolved.toString());
        assertTrue(Files.isRegularFile(resolved.resolve("AGENTS.md")), resolved.toString());
    }
}
```

### `src/test/java/cn/deepassistant/util/ProcessEnvTest.java`

**作用：** 空值不写 getenv；JVM 允许写入时不覆盖已有非空值，不允许则返回 false。

```java
package cn.deepassistant.util;

class ProcessEnvTest {

    @Test
    void blankValueIsNotWritten() {
        assertFalse(ProcessEnv.ensure("MCP_API_KEY_TEST_BLANK", "  "));
        assertFalse(ProcessEnv.ensure(" ", "x"));
    }

    @Test
    void writesWhenJvmAllowsOrReportsFailure() {
        String key = "AS_TEST_ENV_" + UUID.randomUUID().toString().replace("-", "");
        boolean ok = ProcessEnv.ensure(key, "v1");
        if (ok) {
            assertEquals("v1", System.getenv(key));
            assertTrue(ProcessEnv.ensure(key, "v2"));
            assertEquals("v1", System.getenv(key), "已有非空 env 不覆盖");
        } else {
            String now = System.getenv(key);
            assertTrue(now == null || now.isBlank());
        }
    }
}
```

### `src/test/java/cn/deepassistant/service/AgentEventMapperTest.java`

**作用：** 断言 thinking / status 为 live；工具 start 带 name/id；todo → plan 频道。

```java
package cn.deepassistant.service;

class AgentEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentEventMapper mapper = new AgentEventMapper(objectMapper);

    @Test
    void modelCallStartBecomesLiveStatus() throws Exception {
        AgentEventMapper.MappedEvent mapped = mapper.map(new ModelCallStartEvent("r1"));
        assertEquals("status", mapped.event());
        assertFalse(mapped.persist());
        assertEquals("thinking", objectMapper.readTree(mapped.data()).path("label").asText());
    }

    @Test
    void thinkingDeltasAreLiveAndEndPersists() throws Exception {
        AgentEventMapper.MappedEvent started = mapper.map(new ThinkingBlockStartEvent("r1", "b1"));
        assertEquals("thinking", started.event());
        assertFalse(started.persist());

        AgentEventMapper.MappedEvent streamed = mapper.map(
                new ThinkingBlockDeltaEvent("r1", "b1", "先搜一下"));
        assertEquals("thinking", streamed.event());
        assertFalse(streamed.persist());
        assertEquals("先搜一下", objectMapper.readTree(streamed.data()).path("text").asText());

        AgentEventMapper.MappedEvent finished = mapper.map(new ThinkingBlockEndEvent("r1", "b1"));
        assertTrue(finished.persist());
        assertEquals("end", objectMapper.readTree(finished.data()).path("phase").asText());
    }

    @Test
    void emptyThinkingDeltaIsSkipped() {
        assertTrue(mapper.map(new ThinkingBlockDeltaEvent("r1", "b1", "")).skipped());
    }

    @Test
    void toolLifecycleKeepsNameAndId() throws Exception {
        AgentEventMapper.MappedEvent mapped = mapper.map(
                new ToolCallStartEvent("r1", "tc-1", "webSearchPrime"));
        assertEquals("tool", mapped.event());
        JsonNode json = objectMapper.readTree(mapped.data());
        assertEquals("webSearchPrime", json.path("tool").asText());
        assertEquals("tc-1", json.path("id").asText());
        assertEquals("start", json.path("phase").asText());
    }

    @Test
    void todoToolGoesToPlanChannel() {
        AgentEventMapper.MappedEvent mapped = mapper.map(
                new ToolCallStartEvent("r1", "tc-2", "todo_write"));
        assertEquals("plan", mapped.event());
    }

    @Test
    void toolResultEndIncludesState() throws Exception {
        JsonNode json = objectMapper.readTree(mapper.map(
                new ToolResultEndEvent("r1", "tc-2", "calculate", ToolResultState.SUCCESS)).data());
        assertEquals("end", json.path("phase").asText());
        assertEquals("SUCCESS", json.path("state").asText());
    }

    @Test
    void textDeltaIsToken() {
        AgentEventMapper.MappedEvent mapped = mapper.map(new TextBlockDeltaEvent("r1", "b1", "你好"));
        assertEquals("token", mapped.event());
        assertEquals("你好", mapped.data());
        assertTrue(mapped.persist());
    }
}
```

### `src/test/java/cn/deepassistant/service/AgentActivityLoggerTest.java`

**作用：** clip 超长加字数；api-key 打码；从 JSON args 抽出搜索词 / URL。

```java
package cn.deepassistant.service;

class AgentActivityLoggerTest {

    @Test
    void clipAddsLengthWhenTruncated() {
        String clipped = AgentActivityLogger.clip("abcdefghij", 4);
        assertEquals("abcd...(10字)", clipped);
    }

    @Test
    void redactHidesApiKeys() {
        String raw = "{\"query\":\"杭州天气\",\"api-key\":\"sk-secret\"}";
        String redacted = AgentActivityLogger.redact(raw);
        assertTrue(redacted.contains("杭州天气"));
        assertTrue(redacted.contains("***"));
        assertFalse(redacted.contains("sk-secret"));
    }

    @Test
    void searchQueryReadsCommonKeys() {
        assertEquals("杭州天气",
                AgentActivityLogger.searchQuery("webSearchPrime", "{\"query\":\"杭州天气\"}"));
        assertEquals("https://example.com",
                AgentActivityLogger.searchQuery("webReader", "{\"url\":\"https://example.com\"}"));
        assertTrue(AgentActivityLogger.isSearch("webSearchPrime"));
        assertTrue(AgentActivityLogger.isSearch("webReader"));
        assertFalse(AgentActivityLogger.isSearch("calculate"));
    }

    @Test
    void toolDeltasAccumulateWithoutThrowing() {
        AgentActivityLogger logger = new AgentActivityLogger();
        logger.accept(new ToolCallStartEvent("r1", "tc-1", "webSearchPrime"));
        logger.accept(new ToolCallDeltaEvent("r1", "tc-1", "webSearchPrime", "{\"query\":\""));
        logger.accept(new ToolCallDeltaEvent("r1", "tc-1", "webSearchPrime", "杭州\"}"));
        logger.accept(new ToolCallEndEvent("r1", "tc-1", "webSearchPrime"));
        logger.accept(new ToolResultTextDeltaEvent("r1", "tc-1", "webSearchPrime", "晴 18℃"));
        logger.accept(new ToolResultEndEvent("r1", "tc-1", "webSearchPrime", ToolResultState.SUCCESS));
        logger.finish("今天晴", false, false);
    }
}
```

### `src/test/java/cn/deepassistant/util/ConversationMdcTest.java`

**作用：** MDC 写入 SESSION_ID；`run` 抛异常也要 clear

```java
package cn.deepassistant.util;

class ConversationMdcTest {

    @AfterEach
    void tearDown() {
        ConversationMdc.clear();
    }

    @Test
    void openPutsSessionIdForLogback() {
        ConversationMdc.open("sess-1");
        assertEquals("sess-1", MDC.get(ConversationMdc.SESSION_ID));
    }

    @Test
    void runClearsMdcEvenWhenActionThrows() {
        try {
            ConversationMdc.run("sess-1", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // expected
        }
        assertNull(MDC.get(ConversationMdc.SESSION_ID));
    }
}
```

### `src/test/java/cn/deepassistant/config/SessionMdcInterceptorTest.java`

**作用：** 从 `/api/sessions/{id}` 抽出 sessionId，列表/聊天路径返回 null

```java
package cn.deepassistant.config;

class SessionMdcInterceptorTest {

    @Test
    void extractsSessionIdFromSessionsPath() {
        assertEquals("abc-123", SessionMdcInterceptor.pathSessionId("/api/sessions/abc-123"));
        assertEquals("abc-123", SessionMdcInterceptor.pathSessionId("/api/sessions/abc-123?userId=alice"));
        assertNull(SessionMdcInterceptor.pathSessionId("/api/sessions"));
        assertNull(SessionMdcInterceptor.pathSessionId("/api/sessions/"));
        assertNull(SessionMdcInterceptor.pathSessionId("/api/assistant/chat"));
    }
}
```

### `src/test/java/cn/deepassistant/memory/HarnessMemoryCatalogTest.java`

**作用：** MEMORY.md 事实解析

```java
package cn.deepassistant.memory;

class HarnessMemoryCatalogTest {

    @Test
    void parseFactsReadsBulletsUnderHeadings() {
        String md = """
                # 长期记忆
                ## 偏好
                - 回答用中文
                ## 项目与技术
                - 正在做 AgentScope Java 2.0
                """;
        List<MemoryFact> facts = HarnessMemoryCatalog.parseFacts(md);
        assertEquals(2, facts.size());
        assertEquals("preference", facts.get(0).getCategory());
        assertEquals("回答用中文", facts.get(0).getContent());
        assertEquals("project", facts.get(1).getCategory());
    }

    @Test
    void parseFactsIgnoresPlaceholderLines() {
        assertTrue(HarnessMemoryCatalog.parseFacts("（还没有）\n- （空）").isEmpty());
    }
}
```

### `src/test/java/cn/deepassistant/memory/SessionStoreSearchTest.java`

**作用：** 历史关键词计数与摘要

```java
package cn.deepassistant.memory;

class SessionStoreSearchTest {

    @Test
    void countMatchesFindsOverlappingKeywordHits() {
        assertEquals(2, SessionStore.countMatches("agentscope agentscope", "agentscope"));
        assertEquals(0, SessionStore.countMatches("hello", "world"));
    }

    @Test
    void snippetKeepsKeywordAndAddsEllipsis() {
        String text = "AAAAABBBBB" + "关键词" + "CCCCCDDDDD";
        String snippet = SessionStore.snippetAround(text, "关键词");
        assertTrue(snippet.contains("关键词"));
    }
}
```

### `src/test/java/cn/deepassistant/mysql/MysqlAgentStateStoreTest.java`

**作用：** 直连官方 `io.agentscope.extensions.mysql.state.MysqlAgentStateStore`。只用独立库 `agentscope_assistant_test`，连不上则跳过。

```java
package cn.deepassistant.mysql;

/**
 * 直连本地 MySQL 验证官方 {@link MysqlAgentStateStore}。
 *
 * <p>只用独立库 {@code agentscope_assistant_test}，绝不碰应用默认的
 * {@code agentscope_assistant}。连不上或建库失败时测试方法直接 return。
 */
class MysqlAgentStateStoreTest {

    private static final String TEST_DATABASE = "agentscope_assistant_test";

    private static MysqlAgentStateStore store;
    private static boolean available;

    @BeforeAll
    static void connect() {
        String host = env("MYSQL_HOST", "127.0.0.1");
        String port = env("MYSQL_PORT", "3306");
        String user = env("MYSQL_USER", "root");
        String password = env("MYSQL_PASSWORD", "");
        String adminUrl = "jdbc:mysql://" + host + ":" + port
                + "/?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS " + TEST_DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (Exception e) {
            available = false;
            return;
        }
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + host + ":" + port + "/" + TEST_DATABASE
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        ds.setUsername(user);
        ds.setPassword(password);
        try {
            store = new MysqlAgentStateStore(ds, TEST_DATABASE, "agentscope_sessions", true);
            available = true;
        } catch (Exception e) {
            available = false;
        }
    }

    @BeforeEach
    void truncate() {
        if (available) {
            store.truncateAllSessions();
        }
    }

    @Test
    void agentStateStoreSaveGetDeleteRoundTrip() {
        if (!available) return;

        MapState state = wrap(Map.of("summary", "测试对话", "curIter", 3));
        store.save("alice", "sess1", "agent", state);

        Optional<MapState> got = store.get("alice", "sess1", "agent", MapState.class);
        assertTrue(got.isPresent());
        assertEquals("测试对话", got.get().getSummary());
        assertEquals(3, got.get().getCurIter());

        assertTrue(store.exists("alice", "sess1"));
        assertFalse(store.exists("alice", "sess2"));
        assertFalse(store.exists("bob", "sess1"));

        assertEquals(Set.of("sess1"), store.listSessionIds("alice"));

        store.delete("alice", "sess1");
        assertFalse(store.exists("alice", "sess1"));
        assertTrue(store.listSessionIds("alice").isEmpty());
    }

    @Test
    void agentStateStoreIsolatesUsers() {
        if (!available) return;

        store.save("alice", "s1", "agent", wrap(Map.of("summary", "alice的对话")));
        store.save("bob", "s1", "agent", wrap(Map.of("summary", "bob的对话")));

        assertEquals("alice的对话",
                store.get("alice", "s1", "agent", MapState.class).orElseThrow().getSummary());
        assertEquals("bob的对话",
                store.get("bob", "s1", "agent", MapState.class).orElseThrow().getSummary());

        assertEquals(Set.of("s1"), store.listSessionIds("alice"));
        assertEquals(Set.of("s1"), store.listSessionIds("bob"));

        store.delete("alice", "s1");
        assertFalse(store.exists("alice", "s1"));
        assertTrue(store.exists("bob", "s1"));
    }

    @Test
    void listSlotRewritesWhenContentChanges() {
        if (!available) return;

        store.save("alice", "sess1", "tasks", List.of(
                wrap(Map.of("summary", "t1")),
                wrap(Map.of("summary", "t2"))));
        List<MapState> first = store.getList("alice", "sess1", "tasks", MapState.class);
        assertEquals(2, first.size());
        assertEquals("t1", first.get(0).getSummary());
        assertEquals("t2", first.get(1).getSummary());

        store.save("alice", "sess1", "tasks", List.of(wrap(Map.of("summary", "only"))));
        List<MapState> replaced = store.getList("alice", "sess1", "tasks", MapState.class);
        assertEquals(1, replaced.size());
        assertEquals("only", replaced.get(0).getSummary());
    }

    private static MapState wrap(Map<String, Object> m) {
        MapState s = new MapState();
        s.setSummary((String) m.get("summary"));
        Object c = m.get("curIter");
        s.setCurIter(c == null ? 0 : ((Number) c).intValue());
        return s;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    public static class MapState implements io.agentscope.core.state.State {
        private String summary;
        private int curIter;

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public int getCurIter() { return curIter; }
        public void setCurIter(int curIter) { this.curIter = curIter; }
    }
}
```

### `src/test/java/cn/deepassistant/redis/RedisStoresIntegrationTest.java`

**作用：** Redis 工作区隔离与 CAS。连 **db=15**，只 FLUSHDB 测试库。AgentState 见上节 MySQL 测例。

```java
package cn.deepassistant.redis;

/**
 * 直连本地 Redis（127.0.0.1:6379 <b>db=15</b>）验证 {@link RedisBaseStore}。
 *
 * <p>需要本地 Redis 在跑。只 FLUSHDB <b>测试库 15</b>，绝不碰应用默认的 db=0
 * （网页会话 {@code as:web:} 也在 db=0，以前测例会把真实对话清掉）。
 * AgentState 已迁到官方 {@link io.agentscope.extensions.mysql.state.MysqlAgentStateStore}，
 * 见 {@link cn.deepassistant.mysql.MysqlAgentStateStoreTest}。
 * 如果本地没 Redis，测试方法直接 return。
 */
class RedisStoresIntegrationTest {

    /** 和应用 {@code redis.database=0} 错开，避免 mvn test 删掉开发对话。 */
    private static final int TEST_DATABASE = 15;

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;
    private static RedisCommands<String, String> redis;
    private static boolean available;

    @BeforeAll
    static void connect() {
        try {
            client = RedisClient.create(RedisURI.builder()
                    .withHost("127.0.0.1")
                    .withPort(6379)
                    .withDatabase(TEST_DATABASE)
                    .build());
            conn = client.connect();
            redis = conn.sync();
            redis.ping();
            available = true;
        } catch (Exception e) {
            available = false;
        }
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @BeforeEach
    void flush() {
        if (available) {
            redis.flushdb();
        }
    }

    @Test
    void baseStorePutGetSearchDelete() {
        if (!available) return;
        RedisBaseStore store = new RedisBaseStore(redis);

        List<String> ns = List.of("alice");
        store.put(ns, "MEMORY.md", Map.of("content", "# 长期记忆\n- 喜欢中文"));
        store.put(ns, "memory/2024-01-01.md", Map.of("content", "日流水1"));
        store.put(ns, "memory/2024-01-02.md", Map.of("content", "日流水2"));

        // get
        var item = store.get(ns, "MEMORY.md");
        assertTrue(item != null);
        assertEquals("MEMORY.md", item.key());
        assertEquals("# 长期记忆\n- 喜欢中文", ((Map<?, ?>) item.value()).get("content"));
        assertEquals(1L, item.version());

        // put 再写一次，version 递增
        store.put(ns, "MEMORY.md", Map.of("content", "更新"));
        assertEquals(2L, store.get(ns, "MEMORY.md").version());

        // search：列 memory/ 下的文件
        var hits = store.search(ns, 100, 0);
        assertEquals(3, hits.size());

        // 不同用户隔离
        store.put(List.of("bob"), "MEMORY.md", Map.of("content", "bob的"));
        var bobHits = store.search(List.of("bob"), 100, 0);
        assertEquals(1, bobHits.size());
        var aliceHits = store.search(ns, 100, 0);
        assertEquals(3, aliceHits.size());

        // putIfVersion 乐观锁
        assertTrue(store.putIfVersion(ns, "MEMORY.md", Map.of("content", "v3"), 2L));
        assertFalse(store.putIfVersion(ns, "MEMORY.md", Map.of("content", "stale"), 1L));
        assertEquals(3L, store.get(ns, "MEMORY.md").version());

        // delete
        store.delete(ns, "MEMORY.md");
        assertEquals(null, store.get(ns, "MEMORY.md"));
    }
}
```

## 3. 设计取舍与踩坑笔记

**存储怎么分（最佳实践，详见上篇 1.6）**

对话状态进 MySQL 求稳，热文件和短生命周期进 Redis 求快。`MysqlDistributedStore.create()` 是「全进 MySQL、不跑 Redis」的开关，不是更高级的写法：它会把 MEMORY.md 也改成 `JdbcStore`，并带上用不到的沙箱快照 / `GET_LOCK()`，网页会话和 pending 它还是管不到。`create()` 默认库名是 `agentscope`，和本项目 JDBC 的 `agentscope_assistant` 对不上。

1. **用官方 Harness，不复刻 LangGraph 类结构**：产品能力对齐 `deepagents-assistant-java`，代码走 `HarnessAgent.builder()`。
2. **记忆只接线、不自抽**：`.memory(MemoryConfig)` + 中文 flush/consolidation 提示；`HarnessMemoryCatalog` 只读 `WorkspaceManager`。
3. **循环依赖用 `ObjectProvider`**：`harnessAgent → historyMemoryTools → harnessMemoryCatalog → harnessAgent`。`@Lazy` 失败是因为 `HarnessAgent` 没有可见构造器，CGLIB 代理不了。
4. **MCP 必须 0.17.0+**：0.14.1 的 `DefaultJsonSchemaValidator` 引用 `SpecVersion.VersionFlag`（1.x），agentscope 的 `ToolValidator` 需要 `json-schema-validator:2.0.0`（该类已删除）。两者不能靠 pin 1.x 共存。
5. **MCP 初始化失败不能拖垮启动**：2.0.1 `McpServerRegistrar` 单条失败只 warn，其余 server 继续注册。没有 REST 回退。
6. **密钥：getenv 优先，yml 回退**：官方 `${MCP_API_KEY}` 只认 `System.getenv`。JDK 17 默认写不进 getenv（`substituting empty string` 然后智谱 401「令牌已过期」其实是空 Bearer）。`ProcessEnv.ensure` 失败后用 yml `mcp.api-key` 做同等替换。必须是智谱 Key，不要用百炼 Key。
7. **智谱 `/mcp` + Java SDK = GET 405**：Streamable HTTP 握手成功后 SDK 再 GET SSE，智谱 `handleGet` 返回 405 / `Session not found`。`tools.json` 按文档走 `/sse?Authorization=`。
8. **IDEA Working directory**：`${user.dir}/workspace` 指错工程会找不到 `tools.json`。`WorkspaceRoots` 用 `target/classes` 反推模块工作区。
9. **`/api/agents` 不要写死工具名**：清单和 `health.mcp_tools` 读 Toolkit 真实 `McpTool`，否则 MCP 没注册也会显示 `webSearchPrime`。
10. **按 inputSchema 选参数名**：不要盲猜 `query` / `search_query`。
11. **隔离从 (userId, sessionId) 做起**：`UserIds`/`SessionIds` 白名单；AgentState 在 MySQL 列上；会话 `as:web:{userId}:{sessionId}`；pending `as:pending:{userId}:{sessionId}`。
12. **RemoteFilesystem 必须配 DistributedStore**：官方 `build()` 会检查。AgentState 可以不是 Redis，但 `DistributedStore` 两边都要有实现。
13. **Lettuce 版本看内网仓库**：本示例钉 `5.2.2.RELEASE`，因为内部 Nexus 拉不到 6.3.x。
14. **关掉无约束 Shell**：个人助手默认 `.disableShellTool()`，避免模型乱跑系统命令。
15. **思考模式与前端活动流**：yml 当前 `enable-thinking: true`，会推 `ThinkingBlock*`，页面可展开「已思考」。Qwen 开思考时流式 tool_call 可能不稳，异常时改回 `false`。关掉后仍有 `ModelCallStart` → SSE `status`（思考中）和工具步骤。后台 `AgentActivityLogger` 打工具参数、`[Search]` 检索词/结果、思考与作答预览；过长截断，api-key 打码，**不打 userId**。
16. **SSE 多行 `data:`**：前端按规范拼接到空行为止，否则只显示第一句。
17. **网页历史 ≠ 官方 session_search**：一个是 Redis `as:web:`，一个是压缩卸载 jsonl。工具描述里写清楚。
18. **`-parameters` 编译**：否则 `@ToolParam` 可能变成 `arg0`。
19. **Redis 集成测试只清 db=15**：以前测例 `FLUSHDB` db=0 会把开发对话整库删掉。MySQL 测例只用 `agentscope_assistant_test`。
20. **日志不要打 userId**：`logback.xml` 只放 `%X{SESSION_ID}`。聊天在 `boundedElastic` 上跑，必须在工作线程 `ConversationMdc.run`，HTTP 线程设一次会丢。
21. **过期 localStorage sessionId**：Redis 被清或换库后，打开旧 id 返回 400「会话不存在」；前端 `resetToNewChat()`，不要只弹「加载会话失败」。
22. **Java 重启不丢对话**：AgentState 在 MySQL。网页会话 / MEMORY.md 在 Redis——生产给 Redis 开 AOF 或 RDB，否则重启 Redis 会丢长期记忆。侧栏空了先查 db=0 的 `as:web-index:{userId}`，以及侧栏当前用户是不是 `kpye` / `local`。
23. **官方 MysqlAgentStateStore 库名必须和 JDBC URL 一致**：SQL 用 `` `库`.`表` ``。`createIfNotExist=true` 可自动建库建表 `agentscope_sessions`。用 `DistributedStore.builder()` 混搭，不要 `MysqlDistributedStore.create()`。
24. **SSE 必须真 Flux**：`AssistantChatService` 直接订阅 `streamEvents`，不要 `toIterable()` 阻塞；Controller 也不要再包一层阻塞循环。
25. **同会话互斥**：`RedisSessionRunLock`；删会话时取消进行中的订阅，避免 finish 写回已删会话。
26. **审批用 claim**：`resume` 先 claim 再续跑；抢锁失败要把审批单写回，否则用户卡死。
27. **子 Agent 用官方 md**：放 `workspace/subagents/*.md`，不要再维护 `SubagentConfigs` / `.subagents(...)`。isolated 专科默认仍会继承父记忆 hooks——若要「完全不 Flush」，需额外定制（本仓库当前按官方默认）。
28. **MCP 给子 Agent**：`build()` 前 `McpServerRegistrar.register` + `auto-allow-tools` 强制只读；否则 isolated 工作区没有 `tools.json`，子 Agent 看不到 `webSearchPrime`。

---

## 4. API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/assistant/chat` | SSE 对话，body 带 `userId` |
| POST | `/api/assistant/resume` | 文件写入审批后续跑 |
| GET/POST | `/api/sessions` | 列表 / 新建，`?userId=` 或 `X-User-Id` |
| GET/DELETE | `/api/sessions/{id}` | 详情 / 删除 |
| GET | `/api/history/search?q=` | 网页会话库关键词检索 |
| GET | `/api/memory` | 使用概况 + MEMORY.md + 日流水 |
| GET | `/api/agents` | 子 Agent + Toolkit 真实工具名 |
| GET | `/api/health` | `storage=mysql+redis`；`mcp_enabled` / `mcp_tools` 看是否真注册了 MCP |

SSE 事件：`session` / `status` / `thinking` / `token` / `plan` / `tool` / `agent` / `interrupt` / `error` / `done`。

---

## 5. 结语

「小深」在这边不是又一个聊天框包装，而是把 AgentScope Java 2.0 的 **Harness 分层**接到 Spring：

- 统筹负责决策、规划、委派与交付
- 官方记忆管线负责跨会话事实，应用只读、不抢写
- 文件权限 + 人工审批保证写操作可控
- `(userId, sessionId)` + `IsolationScope.USER` 保证多用户不串数据
- AgentState 进 MySQL 求稳，工作区 / 网页会话 / 审批进 Redis 求快；任意副本可读可续跑
- 不要用 `MysqlDistributedStore.create()`：那是全进 MySQL 的开关，会把热路径工作区也改成 JDBC

如果你要扩展，最自然的切入点通常是：在 `workspace/subagents/` 加一个子 Agent 提示词、在 `Toolkit` 上再注册一个 `@Tool`，或给 MySQL / Redis 换地址——不必改 ReAct 内核，也不必把存储收成单一后端。

对照阅读：`deepagents-assistant-java/docs/小深-Deep-Agents-Harness-Java架构博客.md`（LangGraph4j 自研实现）和本文（官方 Harness 接线）是同一产品的两条学习路径。

---

**上篇：** [小深-AgentScope-Java-2.0-Harness架构博客-01.md](./小深-AgentScope-Java-2.0-Harness架构博客-01.md)
