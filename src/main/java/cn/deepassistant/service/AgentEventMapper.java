package cn.deepassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 AgentScope 官方 {@link AgentEvent} 收成前端 SSE 认识的几种名字：
 * {@code token} / {@code tool} / {@code plan} / {@code agent} / {@code interrupt}。
 *
 * <p><b>何时调用：</b>仅本项目 {@code AssistantChatService.run} 在消费
 * {@code harnessAgent.streamEvents} 时逐条映射。框架自己不会调这个类。
 */
@Component
@RequiredArgsConstructor
public class AgentEventMapper {

    private final ObjectMapper objectMapper;

    /**
     * 一条官方事件 → 一条前端事件；不关心的类型返回 {@link MappedEvent#none()} 被丢掉。
     *
     * <p><b>何时调用：</b>{@code AssistantChatService.run} 的 for 循环，每个 {@link AgentEvent} 一次。
     * 事件来源都是框架：
     * <ul>
     *   <li>{@link TextBlockDeltaEvent} — 模型流式吐字</li>
     *   <li>{@link RequireUserConfirmEvent} — 权限 ASK（写文件）卡住，等人批准</li>
     *   <li>{@link ToolCallStartEvent} — 即将执行某个工具（含官方 todo / agent_spawn）</li>
     *   <li>名字里带 TOOL+RESULT/END、SUBAGENT、AGENT_SPAWN 的其它事件</li>
     * </ul>
     */
    public MappedEvent map(AgentEvent event) {
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
            return MappedEvent.of("interrupt", toJson(payload), true);
        }
        if (event instanceof ToolCallStartEvent start) {
            String name = start.getToolCallName();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "tool");
            payload.put("tool", name);
            payload.put("phase", "start");
            return MappedEvent.of(classify(name), toJson(payload));
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
     * <p><b>何时调用：</b>仅 {@link #map} 处理 {@link ToolCallStartEvent} 时。
     */
    private static String classify(String toolName) {
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

    /** JSON 失败时退回 {@code String.valueOf}，避免映射抛错把整条 SSE 掐断。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * @param interrupt true 时 {@code AssistantChatService.run} 会停止消费后续事件并写入 pending
     * @param skipped   true 时前端不展示（空 delta、内部心跳等）
     */
    public record MappedEvent(String event, String data, boolean interrupt, boolean skipped) {
        static MappedEvent of(String event, String data) {
            return new MappedEvent(event, data, false, false);
        }

        static MappedEvent of(String event, String data, boolean interrupt) {
            return new MappedEvent(event, data, interrupt, false);
        }

        static MappedEvent none() {
            return new MappedEvent(null, null, false, true);
        }
    }
}
