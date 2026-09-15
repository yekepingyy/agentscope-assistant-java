package cn.deepassistant.integration.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
