package cn.deepassistant.service;

import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentActivityLoggerTest {

    @Test
    void clipAddsLengthWhenTruncated() {
        String clipped = AgentActivityLogger.clip("abcdefghij", 4);
        assertEquals("abcd...(10字)", clipped);
    }

    @Test
    void redactHidesApiKeys() {
        String raw = "{\"query\":\"杭州天气\",\"api-key\":\"sk-secret\"}";
        String redacted = AgentActivityLogger.redact(raw);
        assertTrue(redacted.contains("杭州天气"));
        assertTrue(redacted.contains("***"));
        assertFalse(redacted.contains("sk-secret"));
    }

    @Test
    void searchQueryReadsCommonKeys() {
        assertEquals("杭州天气",
                AgentActivityLogger.searchQuery("webSearchPrime", "{\"query\":\"杭州天气\"}"));
        assertEquals("https://example.com",
                AgentActivityLogger.searchQuery("webReader", "{\"url\":\"https://example.com\"}"));
        assertTrue(AgentActivityLogger.isSearch("webSearchPrime"));
        assertTrue(AgentActivityLogger.isSearch("webReader"));
        assertFalse(AgentActivityLogger.isSearch("calculate"));
    }

    @Test
    void toolDeltasAccumulateWithoutThrowing() {
        AgentActivityLogger logger = new AgentActivityLogger();
        logger.accept(new ToolCallStartEvent("r1", "tc-1", "webSearchPrime"));
        logger.accept(new ToolCallDeltaEvent("r1", "tc-1", "webSearchPrime", "{\"query\":\""));
        logger.accept(new ToolCallDeltaEvent("r1", "tc-1", "webSearchPrime", "杭州\"}"));
        logger.accept(new ToolCallEndEvent("r1", "tc-1", "webSearchPrime"));
        logger.accept(new ToolResultTextDeltaEvent("r1", "tc-1", "webSearchPrime", "晴 18℃"));
        logger.accept(new ToolResultEndEvent("r1", "tc-1", "webSearchPrime", ToolResultState.SUCCESS));
        logger.finish("今天晴", false, false);
    }

    @Test
    void tagShowsMainAgentWhenSourceBlankOrXiaoShen() {
        AgentActivityLogger logger = new AgentActivityLogger();
        assertEquals("[Agent]", logger.tag(new ModelCallStartEvent("r1")));
        assertEquals("[Agent]", logger.tag(new ModelCallStartEvent("r1").withSource("xiao-shen")));
        assertEquals("[Agent]", logger.tag(null));
    }

    @Test
    void tagShowsSubAgentNameFromSource() {
        AgentActivityLogger logger = new AgentActivityLogger();
        assertEquals("[research-agent]",
                logger.tag(new ThinkingBlockStartEvent("r1", "b1").withSource("research-agent")));
        assertEquals("[research-agent]",
                logger.tag(new ModelCallStartEvent("r1").withSource("agent:research-agent:abc-uuid")));
        assertEquals("[research-agent]",
                logger.tag(new ThinkingBlockStartEvent("r1", "b1")
                        .withSource("0d24936f-97c6-4e44-860d-38c5e1414c11/research-agent")));
        assertEquals("[general-purpose]",
                logger.tag(new AgentStartEvent("sess", "reply", "general-purpose")));
    }

    @Test
    void displayNameNormalizesSpawnKey() {
        assertEquals("Agent", AgentActivityLogger.displayName("xiao-shen"));
        assertEquals("Agent", AgentActivityLogger.displayName(""));
        assertEquals("Agent", AgentActivityLogger.displayName("0d24936f-97c6-4e44-860d-38c5e1414c11/xiao-shen"));
        assertEquals("research-agent", AgentActivityLogger.displayName("research-agent"));
        assertEquals("research-agent", AgentActivityLogger.displayName("agent:research-agent:5d339f3a"));
        assertEquals("research-agent",
                AgentActivityLogger.displayName("0d24936f-97c6-4e44-860d-38c5e1414c11/research-agent"));
    }
}
