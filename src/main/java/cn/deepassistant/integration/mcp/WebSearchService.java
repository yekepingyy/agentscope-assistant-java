package cn.deepassistant.integration.mcp;

/**
 * 联网搜索抽象。两个实现互斥：{@code mcp.zhipu.enabled=true} 用智谱 MCP，否则 REST。
 *
 * <p><b>何时调用（框架间接）：</b>{@code WebResearchTools.webSearch/webRead} ←
 * {@code ToolExecutor} ← 模型在 {@code streamEvents} 里点名工具。没有单独的搜索 HTTP。
 */
public interface

WebSearchService {

    String search(String query);

    default String readUrl(String url) {
        return "当前未启用网页读取能力: " + url;
    }
}
