package cn.deepassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentEventMapper mapper = new AgentEventMapper(objectMapper);

    @Test
    void modelCallStartBecomesLiveStatus() throws Exception {
        AgentEventMapper.MappedEvent mapped = mapper.map(new ModelCallStartEvent("r1"));
        assertEquals("status", mapped.event());
        assertFalse(mapped.persist());
        assertEquals("thinking", objectMapper.readTree(mapped.data()).path("label").asText());
    }

    @Test
    void thinkingDeltasAreLiveAndEndPersists() throws Exception {
        AgentEventMapper.MappedEvent started = mapper.map(new ThinkingBlockStartEvent("r1", "b1"));
        assertEquals("thinking", started.event());
        assertFalse(started.persist());

        AgentEventMapper.MappedEvent streamed = mapper.map(
                new ThinkingBlockDeltaEvent("r1", "b1", "先搜一下"));
        assertEquals("thinking", streamed.event());
        assertFalse(streamed.persist());
        assertEquals("先搜一下", objectMapper.readTree(streamed.data()).path("text").asText());

        AgentEventMapper.MappedEvent finished = mapper.map(new ThinkingBlockEndEvent("r1", "b1"));
        assertTrue(finished.persist());
        assertEquals("end", objectMapper.readTree(finished.data()).path("phase").asText());
    }

    @Test
    void emptyThinkingDeltaIsSkipped() {
        assertTrue(mapper.map(new ThinkingBlockDeltaEvent("r1", "b1", "")).skipped());
    }

    @Test
    void toolLifecycleKeepsNameAndId() throws Exception {
        AgentEventMapper.MappedEvent mapped = mapper.map(
                new ToolCallStartEvent("r1", "tc-1", "webSearchPrime"));
        assertEquals("tool", mapped.event());
        JsonNode json = objectMapper.readTree(mapped.data());
        assertEquals("webSearchPrime", json.path("tool").asText());
        assertEquals("tc-1", json.path("id").asText());
        assertEquals("start", json.path("phase").asText());
    }

    @Test
    void todoToolGoesToPlanChannel() {
        AgentEventMapper.MappedEvent mapped = mapper.map(
                new ToolCallStartEvent("r1", "tc-2", "todo_write"));
        assertEquals("plan", mapped.event());
    }

    @Test
    void toolResultEndIncludesState() throws Exception {
        JsonNode json = objectMapper.readTree(mapper.map(
                new ToolResultEndEvent("r1", "tc-2", "calculate", ToolResultState.SUCCESS)).data());
        assertEquals("end", json.path("phase").asText());
        assertEquals("SUCCESS", json.path("state").asText());
    }

    @Test
    void textDeltaIsToken() {
        AgentEventMapper.MappedEvent mapped = mapper.map(new TextBlockDeltaEvent("r1", "b1", "你好"));
        assertEquals("token", mapped.event());
        assertEquals("你好", mapped.data());
        assertTrue(mapped.persist());
    }

    @Test
    void interruptPayloadIncludesPathAndPreview() throws Exception {
        ToolUseBlock write = ToolUseBlock.builder()
                .id("tc-9")
                .name("write_file")
                .input(Map.of("path", "reports/weekly-20260913.md", "content", "## 本周进展\n- 学习 AgentScope"))
                .build();
        AgentEventMapper.MappedEvent mapped = mapper.map(
                new RequireUserConfirmEvent("reply-1", List.of(write)));
        assertEquals("interrupt", mapped.event());
        assertTrue(mapped.interrupt());
        JsonNode json = objectMapper.readTree(mapped.data());
        assertEquals("write_file", json.path("tool").asText());
        assertEquals("reports/weekly-20260913.md", json.path("path").asText());
        assertTrue(json.path("preview").asText().contains("本周进展"));
        assertEquals("write_file → reports/weekly-20260913.md", json.path("summary").asText());
        assertEquals("reply-1", json.path("replyId").asText());
    }

    @Test
    void interruptNoteShowsPathAndFileBody() {
        ToolUseBlock write = ToolUseBlock.builder()
                .id("tc-9")
                .name("write_file")
                .input(Map.of("path", "reports/weekly-20260913.md", "content", "## 本周进展\n- 学习 AgentScope"))
                .build();
        String note = AssistantChatService.interruptNote(List.of(write));
        assertTrue(note.contains("write_file → reports/weekly-20260913.md"));
        assertTrue(note.contains("本周进展"));
    }
}
