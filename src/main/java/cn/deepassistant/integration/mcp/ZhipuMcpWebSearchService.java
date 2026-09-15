package cn.deepassistant.integration.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智谱 MCP 搜索 / 读网页。
 *
 * <p><b>何时调用（框架间接）：</b>模型在 ReAct 循环里点 {@code webSearch}/{@code webRead} 之后，
 * 经 {@code WebResearchTools} 进来。HTTP 没有对应接口。
 */
@Slf4j
//@Service
// MCP SDK 已升到 0.17.0，与 agentscope 的 json-schema-validator 2.0.0 兼容。
// 缺省启用智谱 MCP；设 mcp.zhipu.enabled=false 回退 REST。
//@ConditionalOnProperty(name = "mcp.zhipu.enabled", havingValue = "true", matchIfMissing = true)
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

    /**
     * 启动时连智谱 MCP，解析真实工具名和参数名。
     *
     * <p><b>何时调用：</b>Spring {@code @PostConstruct}，进程起来一次。失败则 searchClient 为 null，后续 search 返回提示文案。
     */
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

    /**
     * MCP 联网搜索。
     *
     * <p><b>何时调用（框架间接）：</b>{@code WebResearchTools.webSearch} ← {@code ToolExecutor}。
     * 会按 {@code searchArgKeys} 依次试 query/search_query/q，哪个参数名被服务端接受就返回。
     */
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

    /**
     * MCP 读网页。
     *
     * <p><b>何时调用（框架间接）：</b>{@code WebResearchTools.webRead} ← {@code ToolExecutor}。
     */
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
