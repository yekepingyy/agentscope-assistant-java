package cn.deepassistant.config;

import io.agentscope.harness.agent.tools.ToolsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceToolsConfigsTest {

    @TempDir
    Path temp;

    @Test
    void loadSubstitutesApiKeyPlaceholder() throws Exception {
        Files.writeString(temp.resolve("tools.json"), """
                {
                  "deny": ["web_search"],
                  "mcpServers": {
                    "zhipu-web-search": {
                      "transport": "sse",
                      "url": "https://example.test/sse?Authorization=${MCP_API_KEY}",
                      "timeout": "PT60S"
                    }
                  }
                }
                """);
        ToolsConfig cfg = WorkspaceToolsConfigs.load(temp, "secret-key");
        assertEquals(List.of("web_search"), cfg.getDeny());
        assertEquals("https://example.test/sse?Authorization=secret-key",
                cfg.getMcpServers().get("zhipu-web-search").getUrl());
        assertTrue(WorkspaceToolsConfigs.hasMcpServers(cfg));
    }

    @Test
    void filterOnlyDropsMcpServersAndKeepsDeny() throws Exception {
        Files.writeString(temp.resolve("tools.json"), """
                {
                  "deny": ["web_search", "execute"],
                  "mcpServers": {
                    "zhipu-web-search": {
                      "transport": "sse",
                      "url": "https://example.test/sse?Authorization=x",
                      "timeout": "PT60S"
                    }
                  }
                }
                """);
        ToolsConfig full = WorkspaceToolsConfigs.load(temp, null);
        ToolsConfig filter = WorkspaceToolsConfigs.filterOnly(full);
        assertEquals(List.of("web_search", "execute"), filter.getDeny());
        assertNull(filter.getMcpServers());
        assertFalse(WorkspaceToolsConfigs.hasMcpServers(filter));
        assertTrue(WorkspaceToolsConfigs.hasMcpServers(full));
    }

    @Test
    void missingFileReturnsNull() {
        assertNull(WorkspaceToolsConfigs.load(temp, "x"));
    }
}
