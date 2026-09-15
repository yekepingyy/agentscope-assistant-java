package cn.deepassistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.harness.agent.tools.ToolsConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 工作区 {@code tools.json} 的加载与拆分。
 *
 * <p>Harness 2.0.1 在 {@code HarnessAgent.build()} 里会先用父 Toolkit 快照构造
 * {@code research-agent} 等子 Agent 工厂，然后才 {@code McpServerRegistrar.register}。
 * isolated 子工作区没有 {@code tools.json}，子 Agent 自己的 {@code build()} 也读不到 MCP。
 * 因此必须在父 {@code build()} 前把 MCP 挂到 Toolkit 上，再把 {@code mcpServers} 从
 * {@link ToolsConfig} 拿掉，避免父 {@code build()} 再连一次。
 */
@Slf4j
final class WorkspaceToolsConfigs {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private WorkspaceToolsConfigs() {
    }

    static ToolsConfig load(Path workspace, String mcpApiKey) {
        Path file = workspace.resolve("tools.json");
        if (!Files.isRegularFile(file)) {
            log.warn("[Harness] 缺少 {}", file);
            return null;
        }
        try {
            String raw = Files.readString(file);
            if (notBlank(mcpApiKey)) {
                raw = raw.replace("${MCP_API_KEY}", mcpApiKey);
            } else if (raw.contains("${MCP_API_KEY}")) {
                log.warn("[Harness] 没有 MCP_API_KEY / mcp.api-key，智谱 MCP 会 401");
            }
            ToolsConfig cfg = MAPPER.readValue(raw, ToolsConfig.class);
            int servers = cfg.getMcpServers() == null ? 0 : cfg.getMcpServers().size();
            log.info("[Harness] 已加载 tools.json mcpServers={}", servers);
            return cfg;
        } catch (Exception e) {
            throw new IllegalStateException("解析 tools.json 失败: " + file, e);
        }
    }

    /**
     * 只保留 allow/deny，给父 {@code build()} 的 {@code ToolFilter} 用。
     * {@code mcpServers} 必须为空，否则框架会再注册一遍 MCP 客户端。
     */
    static ToolsConfig filterOnly(ToolsConfig full) {
        if (full == null) {
            return null;
        }
        ToolsConfig filter = new ToolsConfig();
        filter.setAllow(full.getAllow());
        filter.setDeny(full.getDeny());
        return filter;
    }

    static boolean hasMcpServers(ToolsConfig config) {
        if (config == null) {
            return false;
        }
        Map<?, ?> servers = config.getMcpServers();
        return servers != null && !servers.isEmpty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
