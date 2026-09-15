package cn.deepassistant.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 智谱 MCP 关闭时的 REST 搜索。
 *
 * <p><b>何时调用（框架间接）：</b>与 {@link ZhipuMcpWebSearchService} 相同，只是 Bean 条件相反。
 */
@Slf4j
//@Service
// 仅当显式关闭智谱 MCP 时走 REST（直连 LLM web_search）。默认 mcp.zhipu.enabled=true。
//@ConditionalOnProperty(name = "mcp.zhipu.enabled", havingValue = "false")
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

    /**
     * REST {@code /web_search}。
     *
     * <p><b>何时调用（框架间接）：</b>{@code WebResearchTools.webSearch} ← {@code ToolExecutor}。
     */
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

    /**
     * REST 模式没有 reader。
     *
     * <p><b>何时调用（框架间接）：</b>模型仍可能发 {@code webRead}，这里返回说明文案。
     */
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
