package cn.deepassistant.tool;

import cn.deepassistant.integration.mcp.WebSearchService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 联网搜索 / 读网页。真正发 HTTP 的是 {@link WebSearchService}（智谱 MCP 或 REST）。
 *
 * <p><b>何时调用（框架）：</b>与 {@link CommonTools} 相同——ReAct 循环里模型点名 {@code webSearch}/{@code webRead}
 * 后由 {@code ToolExecutor} 反射进来。主 Agent 和子 Agent {@code research-agent} 都可能调。
 * 前端没有单独的「搜索」HTTP 接口。
 */
//@Component
public class WebResearchTools {

    private final WebSearchService webSearchService;

    public WebResearchTools(WebSearchService webSearchService) {
        this.webSearchService = webSearchService;
    }

    /**
     * 按关键词联网搜索。
     *
     * <p><b>何时调用（框架）：</b>模型发出工具 {@code webSearch}。随后进 {@code ZhipuMcpWebSearchService}
     * 或 {@code RestWebSearchService}（由 {@code mcp.zhipu.enabled} 决定哪个 Bean 生效）。
     */
    @Tool(name = "webSearch",
            description = "联网搜索最新信息。适合新闻、政策、事实核查、多角度调研。",
            readOnly = true, concurrencySafe = true)
    public String webSearch(
            @ToolParam(name = "query", description = "搜索关键词或问题，尽量具体")
            String query) {
        return webSearchService.search(query);
    }

    /**
     * 抓取指定 URL 正文。
     *
     * <p><b>何时调用（框架）：</b>模型发出 {@code webRead}。REST 模式下会返回「未启用」说明，不会发请求。
     */
    @Tool(name = "webRead",
            description = "读取指定 URL 的网页正文（需启用 mcp.zhipu.enabled=true 并配置 web-reader-url）。",
            readOnly = true, concurrencySafe = true)
    public String webRead(
            @ToolParam(name = "url", description = "网页 URL")
            String url) {
        return webSearchService.readUrl(url);
    }
}
