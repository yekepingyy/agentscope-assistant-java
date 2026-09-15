package cn.deepassistant.service;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把一轮 Harness 事件打成可读的后台日志：模型在干什么、调了哪些工具、搜索词和返回摘要。
 * 只打 {@code SESSION_ID}（MDC），<b>不打 userId</b>。
 *
 * <p>子 Agent 的事件通过同一 {@code streamEvents} 流进来，{@link AgentEvent#getSource()}
 * 区分来源：主 Agent 是 {@code "xiao-shen"}，子 Agent 是 {@code "research-agent"} 等。
 * 日志前缀据此显示 {@code [Agent]} 或 {@code [research-agent]}。
 *
 * <p><b>何时调用：</b>{@code AssistantChatService} 每来一条官方 {@link AgentEvent} 就 {@link #accept}，
 * 流结束再 {@link #finish}。
 */
@Slf4j
final class AgentActivityLogger {

    /** 主 Agent 名称，来自 {@code HarnessAgent.Builder.name("xiao-shen")}。 */
    private static final String MAIN_AGENT = "xiao-shen";

    static final int ARGS_LIMIT = 800;
    static final int RESULT_LIMIT = 2000;
    static final int THINKING_LIMIT = 800;
    static final int REPLY_LIMIT = 400;

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(\"(?:api[-_]?key|token|secret|authorization|password|bearer)\"\\s*:\\s*\")([^\"]*)(\")");

    private final List<String> steps = new ArrayList<>();
    private final Map<String, StringBuilder> argsById = new LinkedHashMap<>();
    private final Map<String, StringBuilder> resultsById = new LinkedHashMap<>();
    private final Set<String> argsLogged = new HashSet<>();
    private final StringBuilder thinking = new StringBuilder();
    private int modelCalls;
    /** 最近一次已知来源：子 Agent 后续事件若没带 source，仍显示子 Agent 名。 */
    private String activeSource = MAIN_AGENT;

    void accept(AgentEvent event) {
        String prefix = tag(event);
        rememberSource(event);
        if (event instanceof AgentStartEvent start) {
            String name = displayName(rawSource(start));
            if (!"Agent".equals(name)) {
                log.info("{} 子Agent开始 name={} session={}", prefix, name, start.getSessionId());
                steps.add("委派:" + name);
            }
            return;
        }
        if (event instanceof AgentEndEvent) {
            if (!"Agent".equals(displayName(rawSource(event)))) {
                log.info("{} 子Agent结束", prefix);
            }
            activeSource = MAIN_AGENT;
            return;
        }
        if (event instanceof ModelCallStartEvent) {
            modelCalls++;
            log.info("{} 调用模型 #{}", prefix, modelCalls);
            steps.add("模型#" + modelCalls);
            return;
        }
        if (event instanceof ModelCallEndEvent end) {
            ChatUsage usage = end.getUsage();
            if (usage != null) {
                log.info("{} 模型返回 #{} in={} out={} total={} time={}s",
                        prefix, modelCalls,
                        usage.getInputTokens(),
                        usage.getOutputTokens(),
                        usage.getTotalTokens(),
                        usage.getTime());
            } else {
                log.info("{} 模型返回 #{}", prefix, modelCalls);
            }
            return;
        }
        if (event instanceof ThinkingBlockStartEvent) {
            thinking.setLength(0);
            log.info("{} 开始思考", prefix);
            return;
        }
        if (event instanceof ThinkingBlockDeltaEvent delta) {
            if (delta.getDelta() != null) {
                thinking.append(delta.getDelta());
            }
            return;
        }
        if (event instanceof ThinkingBlockEndEvent end) {
            String text = thinking.toString().trim();
            if (!text.isEmpty()) {
                log.info("{} 思考内容 {}", prefix, clip(text, THINKING_LIMIT));
                steps.add("思考");
            }
            thinking.setLength(0);
            return;
        }
        if (event instanceof ToolCallStartEvent start) {
            remember(start.getToolCallId(), start.getToolCallName());
            log.info("{} 准备调用工具 name={} id={}", prefix, start.getToolCallName(), start.getToolCallId());
            return;
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            remember(delta.getToolCallId(), delta.getToolCallName());
            append(argsById, delta.getToolCallId(), delta.getDelta());
            return;
        }
        if (event instanceof ToolCallEndEvent end) {
            remember(end.getToolCallId(), end.getToolCallName());
            logToolArgs(end, end.getToolCallId(), end.getToolCallName());
            return;
        }
        if (event instanceof ToolResultStartEvent start) {
            remember(start.getToolCallId(), start.getToolCallName());
            if (!loggedArgs(start.getToolCallId())) {
                logToolArgs(start, start.getToolCallId(), start.getToolCallName());
            }
            log.info("{} 工具执行中 name={} id={}", prefix, start.getToolCallName(), start.getToolCallId());
            return;
        }
        if (event instanceof ToolResultTextDeltaEvent delta) {
            remember(delta.getToolCallId(), delta.getToolCallName());
            append(resultsById, delta.getToolCallId(), delta.getDelta());
            return;
        }
        if (event instanceof ToolResultEndEvent end) {
            remember(end.getToolCallId(), end.getToolCallName());
            logToolResult(end);
            return;
        }
        if (event instanceof RequireUserConfirmEvent confirm) {
            ToolUseBlock first = confirm.getToolCalls() == null || confirm.getToolCalls().isEmpty()
                    ? null : confirm.getToolCalls().get(0);
            String name = first == null ? "未知工具" : first.getName();
            String args = first == null || first.getInput() == null ? "" : String.valueOf(first.getInput());
            log.info("{} 等待审批 name={} args={}", prefix, name, clip(redact(args), ARGS_LIMIT));
            steps.add("审批:" + name);
            return;
        }
        if (event instanceof TextBlockStartEvent) {
            log.info("{} 开始作答", prefix);
        }
    }

    void finish(String reply, boolean interrupted, boolean cancelled) {
        if (cancelled) {
            log.info("[Agent] 本轮被取消 steps={}", steps);
            return;
        }
        if (interrupted) {
            log.info("[Agent] 本轮停在审批 steps={}", steps);
            return;
        }
        if (reply != null && !reply.isBlank()) {
            log.info("[Agent] 作答 {}", clip(reply, REPLY_LIMIT));
            steps.add("作答" + reply.length() + "字");
        }
        log.info("[Agent] 本轮结束 steps={}", steps.isEmpty() ? List.of("直接作答") : steps);
    }

    private void logToolArgs(AgentEvent event, String id, String name) {
        String args = buf(argsById, id);
        String query = searchQuery(name, args);
        if (isSearch(name) && query != null) {
            log.info("{} 检索词 tool={} query={}", tag(event), name, clip(query, ARGS_LIMIT));
            steps.add(name + "(" + clip(query, 80) + ")");
        } else {
            log.info("{} 工具参数 name={} id={} args={}", tag(event), name, id, clip(redact(args), ARGS_LIMIT));
            steps.add(name);
        }
        if (id != null) {
            argsLogged.add(id);
        }
    }

    private boolean loggedArgs(String id) {
        return id != null && argsLogged.contains(id);
    }

    private void logToolResult(ToolResultEndEvent end) {
        String id = end.getToolCallId();
        String name = end.getToolCallName();
        String state = end.getState() == null ? "?" : end.getState().name();
        String result = buf(resultsById, id);
        if (isSearch(name)) {
            log.info("{} 检索结果 tool={} state={} content={}",
                    tag(end), name, state, clip(result, RESULT_LIMIT));
        } else {
            log.info("{} 工具结果 name={} id={} state={} content={}",
                    tag(end), name, id, state, clip(redact(result), RESULT_LIMIT));
        }
        if (id != null) {
            resultsById.remove(id);
        }
    }

    private void remember(String id, String name) {
        if (id == null) {
            return;
        }
        argsById.computeIfAbsent(id, k -> new StringBuilder());
        resultsById.computeIfAbsent(id, k -> new StringBuilder());
    }

    private static void append(Map<String, StringBuilder> buf, String id, String delta) {
        if (id == null || delta == null || delta.isEmpty()) {
            return;
        }
        buf.computeIfAbsent(id, k -> new StringBuilder()).append(delta);
    }

    private static String buf(Map<String, StringBuilder> map, String id) {
        if (id == null) {
            return "";
        }
        StringBuilder b = map.get(id);
        return b == null ? "" : b.toString();
    }

    static boolean isSearch(String tool) {
        if (tool == null) {
            return false;
        }
        String n = tool.toLowerCase(Locale.ROOT);
        return n.contains("search") || n.contains("webreader") || n.contains("web_fetch") || n.contains("webread");
    }

    static String searchQuery(String tool, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        for (String key : List.of("q", "query", "search_query", "searchQuery", "keyword", "keywords", "url")) {
            String value = jsonField(argsJson, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return isSearch(tool) ? argsJson : null;
    }

    static String jsonField(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != '"') {
            return null;
        }
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int j = p + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (esc) {
                out.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '"') {
                return out.toString();
            }
            out.append(c);
        }
        return out.toString();
    }

    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return SECRET_KEY.matcher(text).replaceAll("$1***$3");
    }

    static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() <= max) {
            return flat;
        }
        return flat.substring(0, max) + "...(" + flat.length() + "字)";
    }

    private void rememberSource(AgentEvent event) {
        if (event == null) {
            return;
        }
        String source = event.getSource();
        if (source != null && !source.isBlank()) {
            activeSource = source;
            return;
        }
        if (event instanceof AgentStartEvent start && start.getName() != null && !start.getName().isBlank()) {
            activeSource = start.getName();
        }
    }

    /**
     * 日志前缀：主 Agent 显示 {@code [Agent]}，子 Agent 显示 {@code [research-agent]}。
     * 优先读 {@link AgentEvent#getSource()}（Harness spawn 会打上 agentId）；
     * 没有 source 时用 {@link AgentStartEvent#getName()} 或本轮记住的来源。
     */
    String tag(AgentEvent event) {
        return "[" + displayName(rawSource(event)) + "]";
    }

    private String rawSource(AgentEvent event) {
        if (event != null) {
            String source = event.getSource();
            if (source != null && !source.isBlank()) {
                return source;
            }
            if (event instanceof AgentStartEvent start
                    && start.getName() != null && !start.getName().isBlank()) {
                return start.getName();
            }
        }
        return activeSource;
    }

    /**
     * 只保留 Agent 名。Harness 可能把 source 打成
     * {@code sessionId/research-agent} 或 {@code agent:research-agent:uuid}，
     * sessionId 已在 logback 的 {@code SESSION_ID} 里，中括号不再重复。
     */
    static String displayName(String source) {
        if (source == null || source.isBlank()) {
            return "Agent";
        }
        String name = source.trim();
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        if (name.startsWith("agent:")) {
            String[] parts = name.split(":");
            if (parts.length >= 2 && !parts[1].isBlank()) {
                name = parts[1];
            }
        }
        if (name.isBlank() || MAIN_AGENT.equals(name)) {
            return "Agent";
        }
        return name;
    }
}
