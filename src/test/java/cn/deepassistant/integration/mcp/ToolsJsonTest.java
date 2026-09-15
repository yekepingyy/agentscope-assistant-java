package cn.deepassistant.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsJsonTest {

    private static final List<String> RESEARCH_TOOLS = List.of(
            "webSearchPrime",
            "webReader",
            "getCurrentDateTime",
            "calculate");

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
        assertTrue(cfg.getMcpServers().get("zhipu-web-search").getUrl()
                .contains("/sse?Authorization=${MCP_API_KEY}"));
    }

    @Test
    void researchAgentMarkdownIsOfficiallyLoadable() {
        Path workspace = Path.of("workspace");
        Path md = workspace.resolve("subagents/research-agent.md");
        assertTrue(Files.isRegularFile(md), "应有 workspace/subagents/research-agent.md（官方自动加载）");

        List<SubagentDeclaration> decls = AgentSpecLoader.loadFromDirectory(
                workspace.resolve("subagents"), workspace);
        assertFalse(decls.isEmpty(), "AgentSpecLoader 应能解析 subagents/*.md");

        SubagentDeclaration research = decls.stream()
                .filter(d -> "research-agent".equals(d.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 research-agent"));

        assertEquals(WorkspaceMode.ISOLATED, research.getWorkspaceMode());
        assertFalse(research.isPersistSession());
        assertTrue(research.isInheritParentPermissions());
        assertTrue(research.getTools().containsAll(RESEARCH_TOOLS));
        String prompt = research.getInlineAgentsBody();
        assertNotNull(prompt);
        assertTrue(prompt.contains("不要") && prompt.contains("tools.json"));
    }
}
