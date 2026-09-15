# 小深：用 AgentScope Java 2.0 Harness 做私人助手（下）

> 本篇是系列 **下篇**：工具、智谱 MCP/REST 联网、对话 SSE 与审批续跑、DTO、配置与前端、设计取舍。  
> 上篇：[小深-AgentScope-Java-2.0-Harness架构博客-01.md](./小深-AgentScope-Java-2.0-Harness架构博客-01.md)（架构总览、装配层、Redis、记忆隔离）  
> 项目：`agentscope-assistant-java` · 技术栈：**Spring Boot 3.2.5 + AgentScope Java 2.0.1 `HarnessAgent` + MCP SDK 0.17.0**
>
> 生产只走 Redis，没有 `distributed` profile。前端是自定义深色页。日志带 `SESSION_ID`、不带 userId。

Java 源码为便于发布已**省略 `import` 行**，落盘后请对照仓库或用 IDE 补全。

---

## 2. 模块详解与源码（续）

承接上篇第四节。下面从领域工具开始，一路看到产品层与前端。

## 五、工具

### `src/main/java/cn/deepassistant/tool/CommonTools.java`

**作用：** 时间与安全四则运算（@Tool）

```java
package cn.deepassistant.tool;

@Component
public class CommonTools {

    @Tool(name = "getCurrentDateTime", description = "获取当前精确日期、时间与星期。",
            readOnly = true, concurrencySafe = true)
    public String getCurrentDateTime() {
        LocalDateTime now = LocalDateTime.now();
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
        return now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")) + "，" + weekday;
    }

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

### `src/main/java/cn/deepassistant/tool/WebResearchTools.java`

**作用：** webSearch / webRead，具体协议走 WebSearchService

```java
package cn.deepassistant.tool;

@Component
public class WebResearchTools {

    private final WebSearchService webSearchService;

    public WebResearchTools(WebSearchService webSearchService) {
        this.webSearchService = webSearchService;
    }

    @Tool(name = "webSearch",
            description = "联网搜索最新信息。适合新闻、政策、事实核查、多角度调研。",
            readOnly = true, concurrencySafe = true)
    public String webSearch(
            @ToolParam(name = "query", description = "搜索关键词或问题，尽量具体")
            String query) {
        return webSearchService.search(query);
    }

    @Tool(name = "webRead",
            description = "读取指定 URL 的网页正文（需启用 mcp.zhipu.enabled=true 并配置 web-reader-url）。",
            readOnly = true, concurrencySafe = true)
    public String webRead(
            @ToolParam(name = "url", description = "网页 URL")
            String url) {
        return webSearchService.readUrl(url);
    }
}
```

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
 * 这个工具专门搜那份 JSON，回答「我在这个聊天页里上次说过什么」。
 */
@Component
public class HistoryMemoryTools {

    private final SessionStore sessionStore;
    private final HarnessMemoryCatalog memoryCatalog;

    public HistoryMemoryTools(SessionStore sessionStore, HarnessMemoryCatalog memoryCatalog) {
        this.sessionStore = sessionStore;
        this.memoryCatalog = memoryCatalog;
    }

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

public final class SafeMathEval {

    private SafeMathEval() {
    }

    public static double eval(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式为空");
        }
        return new Parser(expression.replaceAll("\\s+", "")).parse();
    }

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

    private static final class Parser {
        private final String s;
        private int pos = -1;
        private int ch;

        Parser(String s) {
            this.s = s;
        }

        void next() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

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

## 六、联网集成（MCP / REST）

### `src/main/java/cn/deepassistant/integration/mcp/WebSearchService.java`

**作用：** 搜索后端接口，MCP 与 REST 二选一

```java
package cn.deepassistant.integration.mcp;

public interface WebSearchService {

    String search(String query);

    default String readUrl(String url) {
        return "当前未启用网页读取能力: " + url;
    }
}
```

### `src/main/java/cn/deepassistant/integration/mcp/McpToolSupport.java`

**作用：** MCP Java SDK 0.17：按 inputSchema 选参、CallToolResult 收成文本

```java
package cn.deepassistant.integration.mcp;

/**
 * MCP Java SDK 0.17+ {@link McpSchema} 适配：从工具 inputSchema 选参数名，并把
 * {@link McpSchema.CallToolResult}（content + Object structuredContent）收成文本。
 */
final class McpToolSupport {

    private McpToolSupport() {
    }

    static String toolName(McpSchema.Tool tool) {
        return tool == null ? "" : nullToEmpty(tool.name());
    }

    static boolean matchesTool(McpSchema.Tool tool, String... tokens) {
        if (tool == null) {
            return false;
        }
        String haystack = (nullToEmpty(tool.name()) + " " + nullToEmpty(tool.title())
                + " " + nullToEmpty(tool.description())).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (token != null && haystack.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 0.17 {@link McpSchema.JsonSchema} 的 required / properties 选调用参数名。
     * 先用 schema 里真实存在的首选名，没有再退回 required，再退回 properties。
     */
    static List<String> argumentKeys(McpSchema.JsonSchema schema, List<String> preferred) {
        List<String> properties = schema == null || schema.properties() == null
                ? List.of()
                : List.copyOf(schema.properties().keySet());
        List<String> required = schema == null || schema.required() == null
                ? List.of()
                : List.copyOf(schema.required());
        Set<String> known = new LinkedHashSet<>();
        known.addAll(required);
        known.addAll(properties);
        if (preferred != null) {
            List<String> matched = new ArrayList<>();
            for (String want : preferred) {
                String actual = findIgnoreCase(known, want);
                if (actual != null && !matched.contains(actual)) {
                    matched.add(actual);
                }
            }
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        if (!required.isEmpty()) {
            return required;
        }
        return new ArrayList<>(properties);
    }

    static String stringify(McpSchema.CallToolResult result) {
        if (result == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text
                        && text.text() != null
                        && !text.text().isBlank()) {
                    parts.add(text.text());
                }
            }
        }
        if (parts.isEmpty() && result.structuredContent() != null) {
            parts.add(stringifyStructured(result.structuredContent()));
        }
        return String.join("\n", parts);
    }

    private static String stringifyStructured(Object structured) {
        if (structured instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining("\n"));
        }
        if (structured instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining("\n"));
        }
        return String.valueOf(structured);
    }

    private static String findIgnoreCase(Set<String> known, String want) {
        if (want == null) {
            return null;
        }
        for (String key : known) {
            if (key != null && key.equalsIgnoreCase(want)) {
                return key;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
```

### `src/main/java/cn/deepassistant/integration/mcp/ZhipuMcpWebSearchService.java`

**作用：** 智谱 Streamable HTTP MCP；默认启用

```java
package cn.deepassistant.integration.mcp;

@Slf4j
@Service
// MCP SDK 已升到 0.17.0，与 agentscope 的 json-schema-validator 2.0.0 兼容。
// 缺省启用智谱 MCP；设 mcp.zhipu.enabled=false 回退 REST。
@ConditionalOnProperty(name = "mcp.zhipu.enabled", havingValue = "true", matchIfMissing = true)
public class ZhipuMcpWebSearchService implements WebSearchService {

    private static final List<String> SEARCH_ARG_PREFERENCES = List.of("query", "search_query", "q");
    private static final List<String> READER_ARG_PREFERENCES = List.of("url", "link", "uri");

    @Value("${mcp.zhipu.web-search-url}")
    private String searchUrl;
    @Value("${mcp.zhipu.web-reader-url:}")
    private String readerUrl;
    @Value("${mcp.zhipu.api-key:}")
    private String apiKey;

    private McpClientWrapper searchClient;
    private McpClientWrapper readerClient;
    private String searchToolName = "webSearchPrime";
    private String readerToolName = "webReader";
    private List<String> searchArgKeys = SEARCH_ARG_PREFERENCES;
    private List<String> readerArgKeys = READER_ARG_PREFERENCES;

    @PostConstruct
    public void init() {
        this.searchClient = tryBuildClient("zhipu-web-search", resolveUrl(searchUrl), true);
        if (readerUrl != null && !readerUrl.isBlank()) {
            this.readerClient = tryBuildClient("zhipu-web-reader", resolveUrl(readerUrl), false);
        }
        log.info("[MCP] 智谱 Web Search searchTool={} readerTool={} readerEnabled={} searchReady={}",
                searchToolName, readerToolName, readerClient != null, searchClient != null);
    }

    private McpClientWrapper tryBuildClient(String name, String url, boolean search) {
        try {
            McpClientWrapper client = buildClient(name, url);
            resolveTool(client, search);
            return client;
        } catch (Exception e) {
            log.warn("[MCP] 初始化 {} 失败，该能力暂不可用: {}", name, e.getMessage());
            return null;
        }
    }

    private McpClientWrapper buildClient(String name, String url) {
        McpClientBuilder builder = McpClientBuilder.create(name)
                .streamableHttpTransport(url)
                .timeout(Duration.ofSeconds(60));
        if (!urlContainsAuth(url) && apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        McpClientWrapper client = builder.buildSync();
        client.initialize().block(Duration.ofSeconds(30));
        return client;
    }

    private String resolveUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (urlContainsAuth(raw) || apiKey == null || apiKey.isBlank()) {
            return raw;
        }
        if (raw.contains("mcp-broker") || raw.contains("Authorization=")) {
            String sep = raw.contains("?") ? "&" : "?";
            return raw + sep + "Authorization=" + apiKey;
        }
        return raw;
    }

    private static boolean urlContainsAuth(String url) {
        return url != null && url.toLowerCase().contains("authorization=");
    }

    private void resolveTool(McpClientWrapper client, boolean search) {
        try {
            List<McpSchema.Tool> tools = client.listTools().block(Duration.ofSeconds(20));
            if (tools == null || tools.isEmpty()) {
                return;
            }
            for (McpSchema.Tool tool : tools) {
                if (search && McpToolSupport.matchesTool(tool, "search", "webSearchPrime")) {
                    searchToolName = McpToolSupport.toolName(tool);
                    List<String> keys = McpToolSupport.argumentKeys(tool.inputSchema(), SEARCH_ARG_PREFERENCES);
                    if (!keys.isEmpty()) {
                        searchArgKeys = keys;
                    }
                }
                if (!search && McpToolSupport.matchesTool(tool, "reader", "read", "webReader")) {
                    readerToolName = McpToolSupport.toolName(tool);
                    List<String> keys = McpToolSupport.argumentKeys(tool.inputSchema(), READER_ARG_PREFERENCES);
                    if (!keys.isEmpty()) {
                        readerArgKeys = keys;
                    }
                }
            }
            log.info("[MCP] listTools({}): {} argKeys={}",
                    search ? "search" : "reader",
                    tools.stream().map(McpToolSupport::toolName).toList(),
                    search ? searchArgKeys : readerArgKeys);
        } catch (Exception e) {
            log.warn("[MCP] listTools 失败，将按默认工具名/参数名调用: {}", e.getMessage());
        }
    }

    @Override
    public String search(String query) {
        if (query == null || query.isBlank()) {
            return "搜索词为空";
        }
        if (searchClient == null) {
            return "智谱 MCP 搜索客户端未就绪。请检查 API Key / mcp.zhipu.*，或设置 mcp.zhipu.enabled=false 回退 REST。";
        }
        log.info("[MCP] search START query={}", query);
        long t0 = System.currentTimeMillis();
        Exception last = null;
        for (String key : searchArgKeys) {
            try {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put(key, query);
                String text = call(searchClient, searchToolName, args);
                log.info("[MCP] search END   tool={} argKey={} elapsedMs={} resultChars={}",
                        searchToolName, key, System.currentTimeMillis() - t0, text.length());
                return text;
            } catch (Exception e) {
                last = e;
            }
        }
        log.warn("[MCP] search FAIL  elapsedMs={} error={}",
                System.currentTimeMillis() - t0, last == null ? "unknown" : last.getMessage());
        return "智谱 MCP 联网搜索失败: " + (last == null ? "unknown" : last.getMessage())
                + "。请检查 API Key / mcp.zhipu.* 配置，或设置 mcp.zhipu.enabled=false 回退 REST web_search。";
    }

    @Override
    public String readUrl(String url) {
        if (readerClient == null) {
            return "未配置或未连上 mcp.zhipu.web-reader-url，无法读取网页: " + url;
        }
        log.info("[MCP] readUrl START url={}", url);
        long t0 = System.currentTimeMillis();
        Exception last = null;
        for (String key : readerArgKeys) {
            try {
                String text = call(readerClient, readerToolName, Map.of(key, url));
                log.info("[MCP] readUrl END   tool={} argKey={} elapsedMs={} resultChars={}",
                        readerToolName, key, System.currentTimeMillis() - t0, text.length());
                return text;
            } catch (Exception e) {
                last = e;
            }
        }
        log.warn("[MCP] readUrl FAIL  elapsedMs={} error={}", System.currentTimeMillis() - t0,
                last == null ? "unknown" : last.getMessage());
        return "智谱 MCP 网页读取失败: " + (last == null ? "unknown" : last.getMessage());
    }

    private static String call(McpClientWrapper client, String toolName, Map<String, Object> args) {
        McpSchema.CallToolResult result = client.callTool(toolName, args).block(Duration.ofSeconds(60));
        if (result == null) {
            return "";
        }
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException(McpToolSupport.stringify(result));
        }
        return McpToolSupport.stringify(result);
    }

    @PreDestroy
    public void destroy() {
        closeQuietly(searchClient);
        closeQuietly(readerClient);
    }

    private static void closeQuietly(McpClientWrapper client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception ignored) {
            // 关闭阶段不再向上抛
        }
    }
}
```

### `src/main/java/cn/deepassistant/integration/mcp/RestWebSearchService.java`

**作用：** mcp.zhipu.enabled=false 时直连 LLM web_search

```java
package cn.deepassistant.integration.mcp;

@Slf4j
@Service
// 仅当显式关闭智谱 MCP 时走 REST（直连 LLM web_search）。默认 mcp.zhipu.enabled=true。
@ConditionalOnProperty(name = "mcp.zhipu.enabled", havingValue = "false")
public class RestWebSearchService implements WebSearchService {

    @Value("${llm.base-url}")
    private String baseUrl;
    @Value("${llm.api-key}")
    private String apiKey;
    @Value("${llm.timeout-seconds:120}")
    private int timeoutSeconds;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public String search(String query) {
        if (query == null || query.isBlank()) {
            return "搜索词为空";
        }
        log.info("[REST] search START query={}", query);
        long t0 = System.currentTimeMillis();
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "web_search" : baseUrl + "/web_search";
            String body = objectMapper.createObjectNode()
                    .put("search_query", query)
                    .put("search_engine", "search_pro")
                    .put("count", 8)
                    .put("content_size", "medium")
                    .toString();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("[REST] search FAIL  http={} elapsedMs={}",
                        response.statusCode(), System.currentTimeMillis() - t0);
                return "REST web_search 失败 HTTP " + response.statusCode() + ": " + response.body();
            }
            String formatted = format(response.body(), query);
            log.info("[REST] search END   elapsedMs={} resultChars={}",
                    System.currentTimeMillis() - t0, formatted.length());
            return formatted;
        } catch (Exception e) {
            log.error("[REST] search FAIL  elapsedMs={}", System.currentTimeMillis() - t0, e);
            return "联网搜索失败: " + e.getMessage();
        }
    }

    @Override
    public String readUrl(String url) {
        return "当前为 REST 模式，未启用网页读取。请设置 mcp.zhipu.enabled=true 使用智谱 web_reader MCP。"
                + " 目标 URL: " + url;
    }

    private String format(String json, String query) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode items = root.path("search_result");
        if (!items.isArray()) {
            items = root.path("search_results");
        }
        if (!items.isArray()) {
            items = root.path("data");
        }
        List<String> lines = new ArrayList<>();
        lines.add("搜索引擎: rest | 查询: " + query);
        lines.add("");
        if (!items.isArray() || items.isEmpty()) {
            lines.add(json.length() > 2000 ? json.substring(0, 2000) + "…" : json);
            return String.join("\n", lines);
        }
        int i = 1;
        for (JsonNode item : items) {
            String title = text(item, "title", "name");
            String link = text(item, "link", "url");
            String snippet = text(item, "content", "snippet", "summary", "abstract");
            String site = text(item, "media", "site_name", "source");
            lines.add(i + ". " + (title.isBlank() ? "(无标题)" : title));
            if (!site.isBlank()) {
                lines.add("   来源: " + site);
            }
            if (!link.isBlank()) {
                lines.add("   链接: " + link);
            }
            if (!snippet.isBlank()) {
                lines.add("   摘要: " + (snippet.length() > 400 ? snippet.substring(0, 400) : snippet));
            }
            lines.add("");
            i++;
        }
        return String.join("\n", lines);
    }

    private static String text(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return "";
    }
}
```

## 七、对话服务与 SSE API

### `src/main/java/cn/deepassistant/service/AssistantChatService.java`

**作用：** RuntimeContext 跑 Harness；HITL pending 走 Redis `as:pending:`（跨副本）。网页 JSON 走 SessionStore。

```java
package cn.deepassistant.service;

/**
 * 把一次用户消息交给官方 {@link HarnessAgent}，再把事件映射成网页 SSE。
 *
 * <p>多用户隔离靠 {@code (userId, sessionId)} 二元组：
 * <ul>
 *   <li>{@link RuntimeContext} 带上 userId + sessionId，框架据此寻址 AgentState 槽位和工作区文件</li>
 *   <li>{@link SessionStore} 把网页聊天 JSON 存 Redis，多副本共享</li>
 *   <li>{@link AgentStateStore#delete(String, String)} 也按 (userId, sessionId) 清理</li>
 * </ul>
 *
 * <p>跨会话记忆<b>不用</b>在这里再调一次模型。流式输出结束后，框架的
 * {@code MemoryFlushMiddleware} 会在后台把事实追加到该用户的 {@code memory/YYYY-MM-DD.md}。
 */
@Slf4j
@Service
public class AssistantChatService {

    private final HarnessAgent harnessAgent;
    private final SessionStore sessionStore;
    private final AgentEventMapper eventMapper;
    private final AgentStateStore agentStateStore;
    private final RedisPendingApprovalStore pendingApprovals;

    public AssistantChatService(HarnessAgent harnessAgent,
                                SessionStore sessionStore,
                                AgentEventMapper eventMapper,
                                AgentStateStore agentStateStore,
                                RedisPendingApprovalStore pendingApprovals) {
        this.harnessAgent = harnessAgent;
        this.sessionStore = sessionStore;
        this.eventMapper = eventMapper;
        this.agentStateStore = agentStateStore;
        this.pendingApprovals = pendingApprovals;
    }

    /**
     * 推给 Controller 的一条 SSE 载荷。{@code event} 对应前端 switch（token / tool / interrupt / done）。
     */
    public record SseEvent(String event, String data) {
    }

    /**
     * 该会话是否卡在写文件审批。前端打开历史会话时用来决定要不要显示「批准 / 拒绝」。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions/{id}} 填 {@code pendingApproval}；
     * {@code POST /api/assistant/resume} 入口校验。AgentScope 不调这个方法。
     */
    public boolean hasPendingApproval(String userId, String sessionId) {
        return pendingApprovals.exists(UserIds.normalize(userId), sessionId);
    }

    /**
     * 新用户消息：确保会话存在 → 把 user 消息写入网页会话库 → 把消息交给 Harness 流式跑完。
     *
     * <p><b>何时调用：</b>仅 {@code POST /api/assistant/chat}。进到 {@link #streamEvents} 之后，
     * AgentScope 会自己 load/save AgentState、跑工具、Flush 记忆，本方法不再插手。
     *
     * @param eventSink 每映射出一条前端事件就回调一次；Controller 再包装成 SSE
     * @param cancelled 浏览器断开时为 true，{@link #run} 据此提前退出循环
     * @return 最终助手回复全文（审批中断时返回等待说明）
     */
    public String chat(String userId, String sessionId, String userMessage,
                       Consumer<SseEvent> eventSink, AtomicBoolean cancelled) {
        String uid = UserIds.normalize(userId);
        // 没有会话就现场建一条，标题取首句；已有则原样返回
        var session = sessionStore.getOrCreate(uid, sessionId, userMessage);
        String id = session.getId();
        sessionStore.appendMessage(uid, id, ChatMessageRecord.builder()
                .role("user")
                .content(userMessage)
                .timestamp(Instant.now())
                .build());
        RuntimeContext ctx = runtimeContext(uid, id);
        return run(uid, id, streamEvents(new UserMessage(userMessage), ctx), eventSink, cancelled);
    }

    /**
     * 把用户对写文件工具的批准 / 拒绝塞进 {@link Msg} 的 metadata，让 Harness 从中断点继续。
     *
     * <p><b>何时调用：</b>仅 {@code POST /api/assistant/resume}（前端点批准/拒绝）。
     *
     * <p>框架约定：续跑消息必须带 {@link Msg#METADATA_CONFIRM_RESULTS}，值为
     * {@code List<ConfirmResult>}，每条对应当初 Ask 的一个 {@link ToolUseBlock}。
     * {@code ReActAgent} 读到这份 metadata 后才会真正执行（或跳过）那个写文件工具。
     */
    public String resume(String userId, String sessionId, boolean approved,
                         Consumer<SseEvent> eventSink, AtomicBoolean cancelled) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        List<ToolUseBlock> toolCalls = pendingApprovals.get(uid, id);
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalStateException("当前会话没有待审批的操作: " + uid + "/" + id);
        }
        // 同一审批单可能有多个写文件调用，每个都要带上同一份批准结果
        List<ConfirmResult> confirmResults = toolCalls.stream()
                .map(t -> new ConfirmResult(approved, t))
                .toList();
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
        Msg resumeMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(approved ? "approved" : "denied")
                .metadata(meta)
                .build();
        return run(uid, id, streamEvents(resumeMsg, runtimeContext(uid, id)), eventSink, cancelled);
    }

    /**
     * 删网页会话 + 待审批 + AgentState。顺序上先清 pending，避免删会话后审批记录残留。
     *
     * <p><b>何时调用：</b>{@code DELETE /api/sessions/{id}}。其中 {@code agentStateStore.delete}
     * 会进到 AgentScope 的 store 接口，清掉框架自己存的推理上下文。
     */
    public void clearSessionMemory(String userId, String sessionId) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        pendingApprovals.remove(uid, id);
        sessionStore.delete(uid, id);
        deleteAgentState(uid, id);
    }

    /**
     * 阻塞消费 Harness 事件流：映射 → 推 SSE → 拼回复 → 按中断/完成落库。
     *
     * <p>{@code toIterable()} 会卡住当前线程直到流结束，所以 Controller 必须把它放到
     * {@code boundedElastic}，否则会占死 Netty 的 HTTP 线程。
     *
     * <p><b>何时调用：</b>{@link #chat} / {@link #resume} 内部。循环里每一条 {@link AgentEvent}
     * 都是框架推的（token delta、工具起止、写文件 ASK 的 {@code RequireUserConfirmEvent}）。
     */
    private String run(String userId, String sessionId, Flux<AgentEvent> stream,
                       Consumer<SseEvent> eventSink, AtomicBoolean cancelled) {
        List<Map<String, Object>> toolEvents = new ArrayList<>();
        StringBuilder reply = new StringBuilder();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        RequireUserConfirmEvent confirmEvent = null;
        try {
            Iterable<AgentEvent> events = stream.toIterable();
            for (AgentEvent event : events) {
                if (cancelled.get()) {
                    break;
                }
                // 先记下原始审批事件，循环结束后才能把 toolCalls 写入 Redis
                if (event instanceof RequireUserConfirmEvent confirm) {
                    confirmEvent = confirm;
                    interrupted.set(true);
                }
                AgentEventMapper.MappedEvent mapped = eventMapper.map(event);
                if (mapped.skipped()) {
                    // 心跳、内部 delta 空串等前端不关心的事件直接丢掉
                    continue;
                }
                if ("token".equals(mapped.event())) {
                    reply.append(mapped.data());
                } else {
                    // tool / plan / agent / interrupt 进会话 JSON 的 events 数组，刷新后还能回放
                    toolEvents.add(storedEvent(mapped));
                }
                eventSink.accept(new SseEvent(mapped.event(), mapped.data()));
                if (mapped.interrupt()) {
                    // 审批事件已经推给前端，后面的流即使还有也不再消费
                    break;
                }
            }
        } catch (Exception e) {
            log.error("[AssistantChat] 执行失败 session={}", sessionId, e);
            throw new RuntimeException(e.getMessage(), e);
        }
        if (cancelled.get()) {
            // 用户关掉页面：不写助手消息，避免半截回复污染历史
            return "";
        }
        if (interrupted.get() && confirmEvent != null) {
            pendingApprovals.put(userId, sessionId, confirmEvent.getToolCalls());
            String note = "⏸ 等待人工审批："
                    + (confirmEvent.getToolCalls().isEmpty()
                    ? "未知工具"
                    : confirmEvent.getToolCalls().get(0).getName())
                    + "（请在前端批准或拒绝后继续）";
            sessionStore.appendMessage(userId, sessionId, ChatMessageRecord.builder()
                    .role("assistant")
                    .content(note)
                    .timestamp(Instant.now())
                    .events(new ArrayList<>(toolEvents))
                    .build());
            return note;
        }
        // 正常结束或拒绝后续跑完：清掉可能残留的 pending，把完整回复写入网页会话
        pendingApprovals.remove(userId, sessionId);
        String finalReply = reply.toString();
        sessionStore.appendMessage(userId, sessionId, ChatMessageRecord.builder()
                .role("assistant")
                .content(finalReply)
                .timestamp(Instant.now())
                .events(new ArrayList<>(toolEvents))
                .build());
        return finalReply;
    }

    /**
     * 把一条用户消息（或续跑确认消息）交给 Harness，拿回官方事件流。
     *
     * <p><b>何时调用：</b>{@link #chat} / {@link #resume}。这是本项目进入 AgentScope 的唯一入口。
     * 进去之后框架会：load AgentState → 拼 system prompt（含 MEMORY.md）→ 调大模型 →
     * {@code ToolExecutor} 反射执行 {@code @Tool} 方法 → 需要 ASK 时发 {@code RequireUserConfirmEvent}
     * → 结束后 {@code MemoryFlushMiddleware} 后台抽日流水。
     */
    private Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext ctx) {
        return harnessAgent.streamEvents(List.of(msg), ctx);
    }

    /**
     * 构建 {@link RuntimeContext}，带上 userId + sessionId。
     * 框架据此寻址 AgentState 槽位、工作区文件命名空间（IsolationScope.USER）。
     *
     * <p><b>何时调用：</b>{@link #chat}/{@link #resume} 调 {@code streamEvents} 之前。
     * 这是本项目把「当前用户/会话」交给 AgentScope 的唯一方式。
     */
    private RuntimeContext runtimeContext(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
    }

    /**
     * 把映射后的非 token 事件压成可 JSON 序列化的 Map，存进 {@link ChatMessageRecord#getEvents()}。
     * plan 事件额外抄一份 {@code todos}，方便前端任务列表直接读。
     */
    private Map<String, Object> storedEvent(AgentEventMapper.MappedEvent mapped) {
        Map<String, Object> stored = new HashMap<>();
        stored.put("type", mapped.event());
        stored.put("data", mapped.data());
        if ("plan".equals(mapped.event())) {
            stored.put("todos", mapped.data());
        }
        return stored;
    }

    /**
     * 尽力删 Redis 里该会话的 AgentState。失败只打 warn：网页会话已经删了，残留 state 不会再被打开。
     */
    private void deleteAgentState(String userId, String sessionId) {
        try {
            agentStateStore.delete(userId, sessionId);
        } catch (Exception e) {
            log.warn("[AssistantChat] 清理 agent state 失败 session={}: {}",
                    sessionId, e.getMessage());
        }
    }

}
```

### `src/main/java/cn/deepassistant/service/AgentEventMapper.java`

**作用：** 官方 AgentEvent → 前端 SSE：token / tool / plan / agent / interrupt

```java
package cn.deepassistant.service;

@Component
@RequiredArgsConstructor
public class AgentEventMapper {

    private final ObjectMapper objectMapper;

    public MappedEvent map(AgentEvent event) {
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
            String name = start.getToolCallName();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "tool");
            payload.put("tool", name);
            payload.put("phase", "start");
            return MappedEvent.of(classify(name), toJson(payload));
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

    private static String classify(String toolName) {
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public record MappedEvent(String event, String data, boolean interrupt, boolean skipped) {
        static MappedEvent of(String event, String data) {
            return new MappedEvent(event, data, false, false);
        }

        static MappedEvent of(String event, String data, boolean interrupt) {
            return new MappedEvent(event, data, interrupt, false);
        }

        static MappedEvent none() {
            return new MappedEvent(null, null, false, true);
        }
    }
}
```

### `src/main/java/cn/deepassistant/controller/AssistantController.java`

**作用：** HTTP API。聊天 SSE 在 `boundedElastic` 工作线程用 `ConversationMdc.run` 写入 `SESSION_ID`（不写 userId）。GET 会话不存在返回 400。

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
 * 必须在本类里打开 MDC；且 SSE 真正跑在 {@code boundedElastic} 工作线程，要在那条线程再打开一次。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantChatService chatService;
    private final SessionStore sessionStore;
    private final UserMemoryQueryService memoryQueryService;

    /** 健康检查回显当前大模型名，方便运维确认配的是哪套。 */
    @Value("${llm.model}")
    private String model;
    /** 健康检查回显是否启用智谱 MCP 搜索。 */
    @Value("${mcp.zhipu.enabled:true}")
    private boolean mcpEnabled;

    public AssistantController(AssistantChatService chatService,
                               SessionStore sessionStore,
                               UserMemoryQueryService memoryQueryService) {
        this.chatService = chatService;
        this.sessionStore = sessionStore;
        this.memoryQueryService = memoryQueryService;
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
     * 前端展示用的能力清单：子 Agent 名 + Harness 已打开的工具名。不访问 Redis。
     */
    @GetMapping("/agents")
    public Map<String, Object> agents() {
        return Map.of(
                "agents", List.of("research-agent", "general-purpose"),
                "harness", List.of(
                        "todo_write", "filesystem", "agent_spawn",
                        "memory_search", "memory_get", "memory_save", "session_search",
                        "search_conversation_history", "get_user_usage",
                        "plan_mode", "skill_manage"));
    }

    /**
     * 存活探测 + 运行时关键开关。{@code storage=redis} / {@code multi_replica=true} 表示生产只走 Redis。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "framework", "AgentScope-Java-2.0-HarnessAgent",
                "edition", "agentscope",
                "model", model,
                "mcp_enabled", mcpEnabled,
                "memory", "official-flush-consolidation",
                "storage", "redis",
                "multi_user", true,
                "multi_replica", true,
                "version", "2.0.0");
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

**作用：** 端口、模型、MCP、记忆节流、Redis；密钥已脱敏

```yaml
server:
  port: 8089

spring:
  application:
    name: agentscope-assistant-java
  mvc:
    async:
      request-timeout: 330000

# OpenAI 兼容（示例为阿里云百炼 / 智谱均可）
llm:
  base-url: https://llm-inr089bfe37yw1si.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
  api-key: ${LLM_API_KEY:YOUR_LLM_API_KEY}
  model: qwen3.8-27b
  temperature: 0.3
  timeout-seconds: 120
  enable-thinking: false

mcp:
  zhipu:
    # MCP SDK 0.17.0，与 agentscope 的 json-schema-validator 2.0.0 兼容。默认启用智谱 MCP。
    # 设 false 回退 RestWebSearchService（直连 LLM web_search）。
    enabled: true
    web-search-url: https://open.bigmodel.cn/api/mcp/web_search_prime/mcp
    web-reader-url: https://open.bigmodel.cn/api/mcp/web_reader/mcp
    api-key: ${MCP_API_KEY:${LLM_API_KEY:YOUR_ZHIPU_API_KEY}}

agent:
  workspace: ${user.dir}/workspace
  max-iters: 80
  approval-tools: write_file,edit_file
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

# 生产必填：AgentState、工作区文件、网页会话、待审批都进 Redis。启动 ping 失败直接退出。
# 不需要 spring.profiles.active=distributed
redis:
  host: ${REDIS_HOST:127.0.0.1}
  port: ${REDIS_PORT:6379}
  password: ${REDIS_PASSWORD:}        # 本地无密码留空
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

public final class ConversationMdc {
    public static final String SESSION_ID = "SESSION_ID";
    private ConversationMdc() {}
    public static void open(String sessionId) { /* MDC.put / remove */ }
    public static void clear() { MDC.remove(SESSION_ID); }
    public static void run(String sessionId, Runnable action) {
        open(sessionId);
        try { action.run(); } finally { clear(); }
    }
}
```

### `pom.xml`

**作用：** Spring Boot 3.2.5 + agentscope-harness 2.0.1 + MCP 0.17.0 + Lettuce

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
        <!-- Redis 客户端：多副本分布式状态存储用。用本地已缓存的 5.2.2（内部 nexus 拉不到 6.3.2）。 -->
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
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## 十、前端

### `src/main/resources/static/index.html`

**作用：** 深色对话 UI；侧栏用户框；SSE 审批卡；`localStorage.pa_userId` / `pa_sessionId`。过期 session 返回 400「会话不存在」时清本地 id，不再弹吓人的「加载会话失败」。

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
      显示工具调用
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
  document.querySelectorAll('.tool-block,.agent-block,.plan-block').forEach(n => {
    n.style.display = state.showTools ? 'block' : 'none';
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
  wrap.innerHTML = `<div class="role">${role === 'user' ? '你' : '助手'}</div>
    <div class="bubble">${role === 'assistant' ? mdLite(content) : escapeHtml(content)}</div>
    <div class="extras"></div>`;
  el.messages.appendChild(wrap);
  el.messages.scrollTop = el.messages.scrollHeight;
  return wrap;
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
      (m.events || []).forEach(ev => {
        if (ev.type === 'plan') appendMeta(wrap, 'plan-block', '计划\n' + (ev.todos || ''));
        if (ev.type === 'tool' || ev.type === 'tool_start' || ev.type === 'tool_end') {
          appendMeta(wrap, 'tool-block', '工具\n' + JSON.stringify(ev));
        }
        if (ev.type === 'agent' || ev.type === 'agent_start' || ev.type === 'agent_end') {
          appendMeta(wrap, 'agent-block', '子Agent\n' + JSON.stringify(ev));
        }
        if (ev.type === 'interrupt') {
          if (isLast && detail.pendingApproval) {
            appendApprovalCard(wrap, ev);
          } else if (state.showTools) {
            appendMeta(wrap, 'plan-block', '⏸ 审批记录\n' + JSON.stringify(ev));
          }
        }
      });
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
  const aiWrap = appendBubble('assistant', '思考中…');
  const bubble = aiWrap.querySelector('.bubble');
  bubble.textContent = '';

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
      bubble.textContent = '请求失败 HTTP ' + res.status;
      return;
    }
    await consumeSse(res, (event, data) => handleSse(event, data, aiWrap, bubble));
  } catch (e) {
    if (e.name !== 'AbortError') {
      bubble.textContent = '请求失败: ' + e.message;
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
      return;
    }
    await consumeSse(res, (event, data) => handleSse(event, data, aiWrap, bubble));
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

function handleSse(event, data, aiWrap, bubble) {
  if (event === 'session') {
    state.sessionId = data;
    localStorage.setItem('pa_sessionId', data);
    el.currentSession.textContent = data;
  } else if (event === 'token') {
    bubble.innerHTML = mdLite((bubble.dataset.raw || '') + data);
    bubble.dataset.raw = (bubble.dataset.raw || '') + data;
    el.messages.scrollTop = el.messages.scrollHeight;
  } else if (event === 'plan') {
    appendMeta(aiWrap, 'plan-block', '计划\n' + data);
  } else if (event === 'tool') {
    appendMeta(aiWrap, 'tool-block', '工具\n' + data);
  } else if (event === 'agent') {
    appendMeta(aiWrap, 'agent-block', '子Agent\n' + data);
  } else if (event === 'interrupt') {
    let payload = {};
    try { payload = JSON.parse(data); } catch (e) { /* 忽略解析失败，仍展示原文 */ }
    appendApprovalCard(aiWrap, payload);
  } else if (event === 'error') {
    bubble.textContent = (bubble.dataset.raw || '') + '\n[错误] ' + data;
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
3. **规划**：≥3 步、多目标、或用户明确要求清单 —— 用 todo_write 或 Plan Mode；简单任务不要为了规划而规划
4. **委派**：需要联网调研 / 隔离上下文的专科活 —— 用 agent_spawn；多个互相独立的子任务可以在同一轮并行 spawn。你自己不要假装已联网搜索
5. **工作区**：需要落盘长文、草稿、中间结果 —— 用 read_file / write_file / edit_file / list_files（write_file / edit_file 会触发人工审批）
6. **技能**：需要某个技能细节时按需加载；值得沉淀的做法可以写成技能草稿

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

**作用：** research-agent 子 Agent 提示词与工具白名单

```markdown
---
description: 复杂联网调研：多角度搜索、交叉验证、带来源的结论摘要（智谱 Web Search）
workspace:
  mode: isolated
tools: [webSearch, webRead, getCurrentDateTime, calculate]
---

你是 research-agent——复杂联网查询专科子 Agent。

## 工作流（必须遵守）
1. 把用户子任务拆成 2~5 个可检索角度（不同关键词 / 时间 / 来源侧重点）
2. 对每个角度调用 webSearch；需要深读时用 webRead
3. 交叉比对多源结果，标出一致点与冲突点
4. 输出结构化结论：
   - 核心结论（分点）
   - 证据与来源链接（真实来自工具结果，禁止编造）
   - 时效性说明（何时的信息）
   - 不确定性 / 仍待核实项

## 约束
- 只完成统筹分配的这一个子任务
- 搜索词尽量具体；必要时换关键词重搜，不要用同一词盲目重试超过 2 次
- 没有检索到就如实说明，不要编造链接或数据
```

### `workspace/tools.json`

**作用：** 工作区工具 deny 列表

```json
{
  "deny": ["web_search", "web_fetch", "execute"]
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

`mvn test` 覆盖 MCP 0.17 适配、记忆解析、会话检索、MDC，以及本机 Redis 集成（只 FLUSHDB **db=15**，不碰应用 db=0）。

### `src/test/java/cn/deepassistant/integration/mcp/McpToolSupportTest.java`

**作用：** MCP 0.17 schema 选参与 CallToolResult 文本化

```java
package cn.deepassistant.integration.mcp;

class McpToolSupportTest {

    @Test
    void argumentKeysPreferSchemaNamesOverHardcodedGuesses() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of("search_query", Map.of("type", "string")),
                List.of("search_query"),
                null,
                null,
                null);

        assertEquals(List.of("search_query"),
                McpToolSupport.argumentKeys(schema, List.of("query", "search_query", "q")));
    }

    @Test
    void argumentKeysFallBackToRequiredWhenPreferredMissing() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of("keyword", Map.of("type", "string")),
                List.of("keyword"),
                null,
                null,
                null);

        assertEquals(List.of("keyword"),
                McpToolSupport.argumentKeys(schema, List.of("query", "search_query")));
    }

    @Test
    void matchesToolUsesNameTitleAndDescription() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("webSearchPrime")
                .title("联网搜索")
                .description("Search the web")
                .build();
        assertTrue(McpToolSupport.matchesTool(tool, "search", "webSearchPrime"));
        assertEquals("webSearchPrime", McpToolSupport.toolName(tool));
    }

    @Test
    void stringifyReadsTextContentThenStructuredObject() {
        McpSchema.CallToolResult text = McpSchema.CallToolResult.builder()
                .addTextContent("hello")
                .isError(false)
                .build();
        assertEquals("hello", McpToolSupport.stringify(text));

        McpSchema.CallToolResult structured = McpSchema.CallToolResult.builder()
                .isError(false)
                .structuredContent(Map.of("title", "结果"))
                .build();
        assertTrue(McpToolSupport.stringify(structured).contains("title"));
        assertTrue(McpToolSupport.stringify(structured).contains("结果"));
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

### `src/test/java/cn/deepassistant/redis/RedisStoresIntegrationTest.java`

**作用：** Redis 状态/工作区隔离与 CAS。连 **db=15**，只 FLUSHDB 测试库。

```java
package cn.deepassistant.redis;

/**
 * 直连本地 Redis（127.0.0.1:6379 <b>db=15</b>）验证 {@link RedisAgentStateStore} 和 {@link RedisBaseStore}。
 *
 * <p>需要本地 Redis 在跑。只 FLUSHDB <b>测试库 15</b>，绝不碰应用默认的 db=0
 * （网页会话 {@code as:web:} 也在 db=0，以前测例会把真实对话清掉）。
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
    void agentStateStoreSaveGetDeleteRoundTrip() {
        if (!available) return;
        RedisAgentStateStore store = new RedisAgentStateStore(redis);

        // 用一个最简单的 State 实现：直接存 Map 当 JSON
        Map<String, Object> state = Map.of("summary", "测试对话", "curIter", 3);
        store.save("alice", "sess1", "agent", wrap(state));

        Optional<MapState> got = store.get("alice", "sess1", "agent", MapState.class);
        assertTrue(got.isPresent());
        assertEquals("测试对话", got.get().getSummary());
        assertEquals(3, got.get().getCurIter());

        assertTrue(store.exists("alice", "sess1"));
        assertFalse(store.exists("alice", "sess2"));
        assertFalse(store.exists("bob", "sess1"));

        Set<String> ids = store.listSessionIds("alice");
        assertEquals(Set.of("sess1"), ids);

        store.delete("alice", "sess1");
        assertFalse(store.exists("alice", "sess1"));
        assertTrue(store.listSessionIds("alice").isEmpty());
    }

    @Test
    void agentStateStoreIsolatesUsers() {
        if (!available) return;
        RedisAgentStateStore store = new RedisAgentStateStore(redis);

        store.save("alice", "s1", "agent", wrap(Map.of("summary", "alice的对话")));
        store.save("bob", "s1", "agent", wrap(Map.of("summary", "bob的对话")));

        // 同名 sessionId，不同 userId，互不干扰
        assertEquals("alice的对话", store.get("alice", "s1", "agent", MapState.class).orElseThrow().getSummary());
        assertEquals("bob的对话", store.get("bob", "s1", "agent", MapState.class).orElseThrow().getSummary());

        assertEquals(Set.of("s1"), store.listSessionIds("alice"));
        assertEquals(Set.of("s1"), store.listSessionIds("bob"));

        // 删 alice 不影响 bob
        store.delete("alice", "s1");
        assertFalse(store.exists("alice", "s1"));
        assertTrue(store.exists("bob", "s1"));
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

    /** 用 MapState 当一个能被 JsonCodec 序列化的 State。 */
    private static MapState wrap(Map<String, Object> m) {
        MapState s = new MapState();
        s.setSummary((String) m.get("summary"));
        Object c = m.get("curIter");
        s.setCurIter(c == null ? 0 : ((Number) c).intValue());
        return s;
    }

    /** 最简单的 State 实现，有 summary / curIter 两个字段，能被官方 JsonCodec 序列化。 */
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

## 3. 设计取舍与踩坑笔记

1. **用官方 Harness，不复刻 LangGraph 类结构**：产品能力对齐 `deepagents-assistant-java`，代码走 `HarnessAgent.builder()`。
2. **记忆只接线、不自抽**：`.memory(MemoryConfig)` + 中文 flush/consolidation 提示；`HarnessMemoryCatalog` 只读 `WorkspaceManager`。
3. **循环依赖用 `ObjectProvider`**：`harnessAgent → historyMemoryTools → harnessMemoryCatalog → harnessAgent`。`@Lazy` 失败是因为 `HarnessAgent` 没有可见构造器，CGLIB 代理不了。
4. **MCP 必须 0.17.0+**：0.14.1 的 `DefaultJsonSchemaValidator` 引用 `SpecVersion.VersionFlag`（1.x），agentscope 的 `ToolValidator` 需要 `json-schema-validator:2.0.0`（该类已删除）。两者不能靠 pin 1.x 共存。
5. **MCP 初始化失败不能拖垮启动**：`ZhipuMcpWebSearchService.tryBuildClient` 吃掉异常，搜索接口返回明确错误；设 `mcp.zhipu.enabled=false` 回退 REST。
6. **`structuredContent` 是 Object**：0.17 起不再是 `Map`；`McpToolSupport.stringify` 同时处理 TextContent / Map / List。
7. **按 inputSchema 选参数名**：不要盲猜 `query` / `search_query`。
8. **隔离从 Redis key 做起**：`UserIds`/`SessionIds` 白名单；会话 `as:web:{userId}:{sessionId}`；pending `as:pending:{userId}:{sessionId}`。
9. **RemoteFilesystem 必须配 DistributedStore**：官方 `build()` 会检查。默认启动就是这套，没有 JSON 互迁路径。
10. **Lettuce 版本看内网仓库**：本示例钉 `5.2.2.RELEASE`，因为内部 Nexus 拉不到 6.3.x。
11. **关掉无约束 Shell**：个人助手默认 `.disableShellTool()`，避免模型乱跑系统命令。
12. **Qwen 关掉思考模式**：`enable_thinking=false`，流式 tool_call 更稳。
13. **SSE 多行 `data:`**：前端按规范拼接到空行为止，否则只显示第一句。
14. **网页历史 ≠ 官方 session_search**：一个是 Redis `as:web:`，一个是压缩卸载 jsonl。工具描述里写清楚。
15. **`-parameters` 编译**：否则 `@ToolParam` 可能变成 `arg0`。
16. **Redis 集成测试只清 db=15**：以前测例 `FLUSHDB` db=0 会把开发对话整库删掉。
17. **日志不要打 userId**：`logback.xml` 只放 `%X{SESSION_ID}`。聊天在 `boundedElastic` 上跑，必须在工作线程 `ConversationMdc.run`，HTTP 线程设一次会丢。
18. **过期 localStorage sessionId**：Redis 被清或换库后，打开旧 id 返回 400「会话不存在」；前端 `resetToNewChat()`，不要只弹「加载会话失败」。
19. **Java 重启不丢对话**：数据在 Redis。侧栏空了先查 db=0 的 `as:web-index:{userId}`，以及侧栏当前用户是不是 `kpye` / `local`。

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
| GET | `/api/agents` | 子 Agent / 官方工具说明 |
| GET | `/api/health` | `storage=redis`，`multi_replica=true`，`multi_user=true` |

SSE 事件：`session` / `token` / `plan` / `tool` / `agent` / `interrupt` / `error` / `done`。

---

## 5. 结语

「小深」在这边不是又一个聊天框包装，而是把 AgentScope Java 2.0 的 **Harness 分层**接到 Spring：

- 统筹负责决策、规划、委派与交付
- 官方记忆管线负责跨会话事实，应用只读、不抢写
- 文件权限 + 人工审批保证写操作可控
- `(userId, sessionId)` + `IsolationScope.USER` 保证多用户不串数据
- 全部运行时状态进 Redis，任意副本可读可续跑；Java 重启不丢，`FLUSHDB` db=0 会丢

如果你要扩展，最自然的切入点通常是：在 `workspace/subagents/` 加一个子 Agent 提示词、在 `Toolkit` 上再注册一个 `@Tool`，或给 Redis 换集群地址——不必改 ReAct 内核。

对照阅读：`deepagents-assistant-java/docs/小深-Deep-Agents-Harness-Java架构博客.md`（LangGraph4j 自研实现）和本文（官方 Harness 接线）是同一产品的两条学习路径。

---

**上篇：** [小深-AgentScope-Java-2.0-Harness架构博客-01.md](./小深-AgentScope-Java-2.0-Harness架构博客-01.md)
