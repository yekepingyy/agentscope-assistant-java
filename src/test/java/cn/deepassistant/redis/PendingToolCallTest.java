package cn.deepassistant.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingToolCallTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripKeepsNameArgsAndRebuildsToolUseBlock() throws Exception {
        ToolUseBlock original = ToolUseBlock.builder()
                .id("tc-1")
                .name("write_file")
                .input(Map.of("path", "hello.txt", "content", "ping"))
                .state(ToolCallState.ASKING)
                .build();
        String json = mapper.writeValueAsString(RedisPendingApprovalStore.encode(List.of(original)));
        List<ToolUseBlock> restored = RedisPendingApprovalStore.decode(mapper, json);
        assertEquals(1, restored.size());
        assertEquals("write_file", restored.get(0).getName());
        assertEquals("tc-1", restored.get(0).getId());
        assertEquals("hello.txt", restored.get(0).getInput().get("path"));
        assertEquals(ToolCallState.ASKING, restored.get(0).getState());
    }

    @Test
    void rawToolUseBlockJsonCannotBeReadAsDomainObject() throws Exception {
        ToolUseBlock original = ToolUseBlock.builder()
                .id("tc-1")
                .name("write_file")
                .input(Map.of("path", "hello.txt"))
                .build();
        String raw = mapper.writeValueAsString(List.of(original));
        assertThrows(Exception.class, () -> mapper.readValue(
                raw,
                mapper.getTypeFactory().constructCollectionType(List.class, ToolUseBlock.class)));
    }

    @Test
    void encodeSkipsNamelessBlocks() {
        ToolUseBlock named = ToolUseBlock.builder().id("1").name("edit_file").input(Map.of()).build();
        List<PendingToolCall> snapshot = RedisPendingApprovalStore.encode(List.of(named));
        assertEquals(1, snapshot.size());
        assertNotNull(snapshot.get(0).toBlock());
        assertTrue(RedisPendingApprovalStore.encode(List.of()).isEmpty());
    }

    @Test
    void describeIncludesToolNameAndPath() {
        ToolUseBlock original = ToolUseBlock.builder()
                .id("tc-1")
                .name("write_file")
                .input(Map.of("path", "reports/weekly-20260913.md", "content", "本周进展"))
                .build();
        assertEquals("write_file → reports/weekly-20260913.md", PendingToolCall.describe(List.of(original)));
        assertEquals("reports/weekly-20260913.md", PendingToolCall.pathOf(original));
        assertEquals("本周进展", PendingToolCall.contentOf(original));
    }

    @Test
    void wrapperJsonKeepsReplyIdAndOldArrayStillDecodes() throws Exception {
        ToolUseBlock original = ToolUseBlock.builder()
                .id("tc-1")
                .name("write_file")
                .input(Map.of("path", "hello.txt", "content", "ping"))
                .state(ToolCallState.ASKING)
                .build();
        String wrapped = mapper.writeValueAsString(Map.of(
                "replyId", "reply-9",
                "tools", RedisPendingApprovalStore.encode(List.of(original))));
        RedisPendingApprovalStore.Snapshot snapshot = RedisPendingApprovalStore.decodeSnapshot(mapper, wrapped);
        assertEquals("reply-9", snapshot.replyId());
        assertEquals("write_file", snapshot.toolCalls().get(0).getName());
        assertEquals("hello.txt", snapshot.toolCalls().get(0).getInput().get("path"));

        String legacy = mapper.writeValueAsString(RedisPendingApprovalStore.encode(List.of(original)));
        RedisPendingApprovalStore.Snapshot old = RedisPendingApprovalStore.decodeSnapshot(mapper, legacy);
        assertEquals("", old.replyId());
        assertEquals("write_file", old.toolCalls().get(0).getName());
    }
}
