package cn.deepassistant.service;

import cn.deepassistant.redis.PendingToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 AgentScope 官方 {@link AgentEvent} 收成前端 SSE 认识的几种名字：
 * {@code token} / {@code thinking} / {@code status} / {@code tool} / {@code plan} /
 * {@code agent} / {@code interrupt}。
 *
 * <p><b>何时调用：</b>仅本项目 {@code AssistantChatService} 在订阅
 * {@code harnessAgent.streamEvents} 时逐条映射。框架自己不会调这个类。
 */
@Component
@RequiredArgsConstructor
public class AgentEventMapper {

    private final ObjectMapper objectMapper;

    /**
     * 一条官方事件 → 一条前端事件；不关心的类型返回 {@link MappedEvent#none()} 被丢掉。
     *
     * <p><b>何时调用：</b>{@code AssistantChatService} 订阅流时，每个 {@link AgentEvent} 一次。
     */
    public MappedEvent map(AgentEvent event) {
        if (event instanceof ModelCallStartEvent) {
            return MappedEvent.live("status", json(status("thinking", "start")));
        }
        if (event instanceof ThinkingBlockStartEvent) {
            return MappedEvent.live("thinking", json(thinking("start", null)));
        }
        if (event instanceof ThinkingBlockDeltaEvent delta) {
            String text = delta.getDelta();
            if (text == null || text.isEmpty()) {
                return MappedEvent.none();
            }
            return MappedEvent.live("thinking", json(thinking("delta", text)));
        }
        if (event instanceof ThinkingBlockEndEvent) {
            return MappedEvent.of("thinking", json(thinking("end", null)));
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            String text = delta.getDelta();
            if (text == null || text.isEmpty()) {
                return MappedEvent.none();
            }
            return MappedEvent.of("token", text);
        }
        if (event instanceof RequireUserConfirmEvent confirm) {
            ToolUseBlock first = confirm.getToolCalls() == null || confirm.getToolCalls().isEmpty()
                    ? null : confirm.getToolCalls().get(0);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", first == null ? "未知工具" : first.getName());
            payload.put("args", first == null ? Map.of() : first.getInput());
            payload.put("replyId", confirm.getReplyId());
            payload.put("path", PendingToolCall.pathOf(first));
            payload.put("preview", clipPreview(PendingToolCall.contentOf(first), 1200));
            payload.put("summary", PendingToolCall.describe(confirm.getToolCalls()));
            return MappedEvent.of("interrupt", toJson(payload), true);
        }
        if (event instanceof ToolCallStartEvent start) {
            return MappedEvent.of(classify(start.getToolCallName()),
                    json(toolPayload(start.getToolCallId(), start.getToolCallName(), "start", null)));
        }
        if (event instanceof ToolResultStartEvent start) {
            return MappedEvent.of(classify(start.getToolCallName()),
                    json(toolPayload(start.getToolCallId(), start.getToolCallName(), "running", null)));
        }
        if (event instanceof ToolResultEndEvent end) {
            String state = end.getState() == null ? null : end.getState().name();
            return MappedEvent.of(classify(end.getToolCallName()),
                    json(toolPayload(end.getToolCallId(), end.getToolCallName(), "end", state)));
        }
        String typeName = event.getType() == null ? "" : event.getType().name();
        if (typeName.contains("TOOL") && (typeName.contains("RESULT") || typeName.contains("END"))) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "tool");
            payload.put("phase", "end");
            payload.put("event", typeName);
            return MappedEvent.of("tool", toJson(payload));
        }
        if (typeName.contains("SUBAGENT") || typeName.contains("AGENT_SPAWN")) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "agent");
            payload.put("event", typeName);
            return MappedEvent.of("agent", toJson(payload));
        }
        return MappedEvent.none();
    }

    /**
     * 按工具名把 start 事件分到前端频道：todo → plan，spawn/task → agent，其余 → tool。
     *
     * <p><b>何时调用：</b>仅 {@link #map} 处理工具生命周期事件时。
     */
    static String classify(String toolName) {
        if (toolName == null) {
            return "tool";
        }
        String lower = toolName.toLowerCase();
        if (lower.contains("todo")) {
            return "plan";
        }
        if (lower.contains("agent_spawn") || lower.contains("agent_send") || lower.contains("task")) {
            return "agent";
        }
        return "tool";
    }

    private static Map<String, Object> status(String label, String phase) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("label", label);
        payload.put("phase", phase);
        return payload;
    }

    private static Map<String, Object> thinking(String phase, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        if (text != null) {
            payload.put("text", text);
        }
        return payload;
    }

    private static Map<String, Object> toolPayload(String id, String name, String phase, String state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool");
        payload.put("id", id);
        payload.put("tool", name);
        payload.put("phase", phase);
        if (state != null) {
            payload.put("state", state);
        }
        return payload;
    }

    private String json(Map<String, Object> payload) {
        return toJson(payload);
    }

    /** JSON 失败时退回 {@code String.valueOf}，避免映射抛错把整条 SSE 掐断。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    static String clipPreview(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n…（共 " + text.length() + " 字）";
    }

    /**
     * @param interrupt true 时 {@code AssistantChatService} 会先保存 AgentState，再结束当前订阅
     * @param skipped   true 时前端不展示（空 delta、内部心跳等）
     * @param persist   false 时只推 SSE，不写进网页会话 events（思考 delta / 状态条）
     */
    public record MappedEvent(String event, String data, boolean interrupt, boolean skipped, boolean persist) {
        static MappedEvent of(String event, String data) {
            return new MappedEvent(event, data, false, false, true);
        }

        static MappedEvent of(String event, String data, boolean interrupt) {
            return new MappedEvent(event, data, interrupt, false, true);
        }

        static MappedEvent live(String event, String data) {
            return new MappedEvent(event, data, false, false, false);
        }

        static MappedEvent none() {
            return new MappedEvent(null, null, false, true, false);
        }
    }
}
