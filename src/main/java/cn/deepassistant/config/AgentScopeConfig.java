package cn.deepassistant.config;

import cn.deepassistant.redis.RedisBaseStore;
import cn.deepassistant.tool.CommonTools;
import cn.deepassistant.tool.HistoryMemoryTools;
import cn.deepassistant.util.ProcessEnv;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
 * <p>刻意<b>不要</b>对父 Agent 调用 {@code disableMemoryHooks()} / {@code disableMemoryTools()}。
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

    /**
     * 只读联网 MCP 工具名单：智谱常不带 {@code readOnlyHint}。
     * 仅用于 {@link #forceMcpReadToolsReadonly} 强制 {@code readOnly=true}；
     * 权限模式已是 BYPASS，不必再写 ALLOW 规则。
     */
    @Value("#{'${agent.auto-allow-tools:webReader,webSearchPrime}'.split(',')}")
    private List<String> autoAllowTools;

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
     * {@code AssistantChatService} 调 {@code harnessAgent.streamEvents}，
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
            // 智谱 webReader/webSearchPrime 无 readOnlyHint → McpTool.checkPermissions 恒 ASK；
            // isolated 子 Agent 又继承不到父级 BYPASS，所以必须在快照前强制标成只读。
            forceMcpReadToolsReadonly(toolkit);
        }

        // 默认 BYPASS：读文件、搜索、读网页、spawn 不弹窗。
        // MCP 只读放行靠 forceMcpReadToolsReadonly（auto-allow-tools），不必再叠一层 ALLOW 规则。
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
                .maxIters(Math.max(15, maxIters))
                // 审批拒绝后立即停止循环，避免模型反复尝试写文件被拒、空转耗轮数
                .stopOnReject(true)
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
                .enablePendingToolRecovery(true)
                // 关掉 Agent Tracing Log：默认会在 workspace/{userId}/agents/{name}/sessions/
                // 下写 *.jsonl 追踪日志，生产不需要，省磁盘。对话上下文仍在 MySQL、网页全文仍在 Redis。
                .enableAgentTracingLog(false)
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
        log.info("[Harness] workspace={} flushTrigger={} approvalTools={} autoAllowTools={} mcpApiKeyEnv={} mcpTools={} tools={}",
                ws,
                memoryConfig.flushTrigger(),
                approvalTools == null ? List.of() : new ArrayList<>(approvalTools),
                autoAllowTools == null ? List.of() : new ArrayList<>(autoAllowTools),
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
     * 把配置的只读 MCP 工具重建为 {@code readOnly=true}。
     * {@link McpTool#checkPermissions} 对只读工具直接 ALLOW，不再发 RequireUserConfirmEvent。
     */
    private void forceMcpReadToolsReadonly(Toolkit toolkit) {
        for (String raw : autoAllowTools == null ? List.<String>of() : autoAllowTools) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            Object tool = toolkit.getTool(name);
            if (!(tool instanceof McpTool mcp)) {
                continue;
            }
            if (mcp.isReadOnly()) {
                continue;
            }
            try {
                var field = McpTool.class.getDeclaredField("clientWrapper");
                field.setAccessible(true);
                Object client = field.get(mcp);
                toolkit.removeTool(name);
                toolkit.registerAgentTool(new McpTool(
                        mcp.getName(),
                        mcp.getDescription(),
                        mcp.getParameters(),
                        mcp.getOutputSchema(),
                        (io.agentscope.core.tool.mcp.McpClientWrapper) client,
                        mcp.getPresetArguments(),
                        mcp.getClientName(),
                        true));
                log.info("[Harness] MCP 工具已强制只读（跳过审批）: {}", name);
            } catch (ReflectiveOperationException e) {
                log.warn("[Harness] 无法将 MCP 工具标为只读 name={}: {}", name, e.toString());
            }
        }
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
     * {@code research-agent} 等子 Agent 由 Harness 官方从 {@code subagents/*.md} 自动加载（DynamicSubagents Layer 2）。
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
        Files.createDirectories(ws.resolve("subagents"));
        return ws;
    }
}
