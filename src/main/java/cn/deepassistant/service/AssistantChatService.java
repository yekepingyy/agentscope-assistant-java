package cn.deepassistant.service;

import cn.deepassistant.memory.SessionStore;
import cn.deepassistant.model.ChatMessageRecord;
import cn.deepassistant.redis.PendingToolCall;
import cn.deepassistant.redis.RedisPendingApprovalStore;
import cn.deepassistant.redis.RedisSessionRunLock;
import cn.deepassistant.util.ConversationMdc;
import cn.deepassistant.util.SessionIds;
import cn.deepassistant.util.UserIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把一次用户消息交给官方 {@link HarnessAgent}，再把事件映射成网页 SSE。
 *
 * <p>流式：直接订阅 {@link HarnessAgent#streamEvents} 返回的 {@link Flux}，
 * 每个 {@link AgentEvent} 到达立刻向下推 SSE，<b>不用</b> {@code toIterable()} 阻塞拉取。
 * 浏览器断开时取消订阅即可，不必另开标志位去打断 for 循环。
 *
 * <p>多用户隔离靠 {@code (userId, sessionId)} 二元组：
 * <ul>
 *   <li>{@link RuntimeContext} 带上 userId + sessionId，框架据此寻址 AgentState 槽位和工作区文件</li>
 *   <li>{@link SessionStore} 把网页聊天 JSON 存 Redis，多副本共享</li>
 *   <li>{@link AgentStateStore#delete(String, String)} 也按 (userId, sessionId) 清理</li>
 * </ul>
 *
 * <p>跨会话记忆<b>不用</b>在这里再调一次模型。流式输出结束后，框架的
 * {@code MemoryFlushMiddleware} 会在后台把事实追加到该用户的 {@code memory/YYYY-MM-DD.md}。
 */
@Slf4j
@Service
public class AssistantChatService {

    /**
     * 推给 Controller 的一条 SSE 载荷。{@code event} 对应前端 switch
     * （session / status / thinking / token / tool / interrupt / done）。
     */
    public record SseEvent(String event, String data) {
    }

    private final HarnessAgent harnessAgent;
    private final SessionStore sessionStore;
    private final AgentEventMapper eventMapper;
    private final AgentStateStore agentStateStore;
    private final RedisPendingApprovalStore pendingApprovals;
    private final RedisSessionRunLock runLock;
    private final ObjectMapper objectMapper;

    /** 进行中的流：删会话时 cancel，避免 finish 把已删会话写回。 */
    private final ConcurrentHashMap<String, AtomicReference<reactor.core.Disposable>> activeRuns =
            new ConcurrentHashMap<>();

    public AssistantChatService(HarnessAgent harnessAgent,
                                SessionStore sessionStore,
                                AgentEventMapper eventMapper,
                                AgentStateStore agentStateStore,
                                RedisPendingApprovalStore pendingApprovals,
                                RedisSessionRunLock runLock,
                                ObjectMapper objectMapper) {
        this.harnessAgent = harnessAgent;
        this.sessionStore = sessionStore;
        this.eventMapper = eventMapper;
        this.agentStateStore = agentStateStore;
        this.pendingApprovals = pendingApprovals;
        this.runLock = runLock;
        this.objectMapper = objectMapper;
    }

    private static String runKey(String userId, String sessionId) {
        return userId + "/" + sessionId;
    }

    /**
     * 该会话是否有可读的写文件审批单。前端打开历史会话时用来决定要不要显示「批准 / 拒绝」。
     *
     * <p>走 Redis 反序列化，不只看 key 在不在：旧格式 Jackson 读不出 {@code ToolUseBlock} 时
     * 不能当成还有待审批。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions/{id}} 填 {@code pendingApproval}；
     * {@code POST /api/assistant/resume} 入口校验。AgentScope 不调这个方法。
     */
    public boolean hasPendingApproval(String userId, String sessionId) {
        return pendingApprovals.exists(UserIds.normalize(userId), sessionId);
    }

    /**
     * 新用户消息：确保会话存在 → 把 user 消息写入网页会话库 → 订阅 Harness 事件流。
     *
     * <p>{@link Flux#defer} 保证建会话 / 落库发生在真正有订阅者之后，而不是 Controller 组装管道时。
     *
     * <p><b>何时调用：</b>仅 {@code POST /api/assistant/chat}。进到 {@link HarnessAgent#streamEvents} 之后，
     * AgentScope 会自己 load/save AgentState、跑工具、Flush 记忆，本方法不再插手。
     */
    public Flux<SseEvent> chat(String userId, String sessionId, String userMessage) {
        String uid = UserIds.normalize(userId);
        return Flux.defer(() -> {
            var session = sessionStore.getOrCreate(uid, sessionId, userMessage);
            String id = session.getId();
            if (pendingApprovals.exists(uid, id)) {
                return Flux.just(
                        new SseEvent("error", "当前会话有待审批的操作，请先批准或拒绝"),
                        new SseEvent("done", "[DONE]"));
            }
            String token = runLock.tryAcquire(uid, id);
            if (token == null) {
                return Flux.just(
                        new SseEvent("error", "该会话正在处理中，请稍后再试"),
                        new SseEvent("done", "[DONE]"));
            }
            sessionStore.appendMessage(uid, id, ChatMessageRecord.builder()
                    .role("user")
                    .content(userMessage)
                    .timestamp(Instant.now())
                    .build());
            ConversationMdc.open(id);
            try {
                log.info("[Agent] 收到消息 chars={} preview={}",
                        userMessage == null ? 0 : userMessage.length(),
                        AgentActivityLogger.clip(userMessage, 200));
            } finally {
                ConversationMdc.clear();
            }
            return subscribe(uid, id, new UserMessage(userMessage), token, false);
        });
    }

    /**
     * 把用户对写文件工具的批准 / 拒绝塞进 {@link Msg} 的 metadata，让 Harness 从中断点继续。
     *
     * <p><b>何时调用：</b>仅 {@code POST /api/assistant/resume}（前端点批准/拒绝）。
     *
     * <p>框架约定：续跑消息必须带 {@link Msg#METADATA_CONFIRM_RESULTS}，值为
     * {@code List<ConfirmResult>}，每条对应当初 Ask 的一个 {@link ToolUseBlock}。
     * 同时带上 {@link Msg#METADATA_CONFIRM_REQUEST_REPLY_ID}，和上次 ASK 的 replyId 对齐。
     * {@code ReActAgent} 读到这份 metadata 后才会真正执行（或跳过）那个写文件工具。
     */
    public Flux<SseEvent> resume(String userId, String sessionId, boolean approved) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        return Flux.defer(() -> {
            RedisPendingApprovalStore.Snapshot snapshot = pendingApprovals.claim(uid, id);
            List<ToolUseBlock> toolCalls = snapshot == null ? null : snapshot.toolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Flux.error(new IllegalStateException(
                        "当前会话没有待审批的操作: " + uid + "/" + id));
            }
            String token = runLock.tryAcquire(uid, id);
            if (token == null) {
                // 抢锁失败：写回审批单，避免用户无法重试
                pendingApprovals.put(uid, id, snapshot.replyId(), toolCalls);
                return Flux.just(
                        new SseEvent("error", "该会话正在处理中，请稍后再试"),
                        new SseEvent("done", "[DONE]"));
            }
            List<ConfirmResult> confirmResults = toolCalls.stream()
                    .map(t -> new ConfirmResult(approved, t))
                    .toList();
            Map<String, Object> meta = new HashMap<>();
            meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
            if (snapshot.replyId() != null && !snapshot.replyId().isBlank()) {
                meta.put(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID, snapshot.replyId());
            }
            Msg resumeMsg = Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent(approved ? "approved" : "denied")
                    .metadata(meta)
                    .build();
            ConversationMdc.open(id);
            try {
                log.info("[Agent] 续跑审批 approved={} replyId={} detail={}",
                        approved, snapshot.replyId(), PendingToolCall.describe(toolCalls));
            } finally {
                ConversationMdc.clear();
            }
            return subscribe(uid, id, resumeMsg, token, true);
        });
    }

    /**
     * 删网页会话 + 待审批 + AgentState。顺序上先清 pending，避免删会话后审批记录残留。
     *
     * <p><b>何时调用：</b>{@code DELETE /api/sessions/{id}}。其中 {@code agentStateStore.delete}
     * 会进到 AgentScope 的 store 接口，清掉框架自己存的推理上下文。
     */
    public void clearSessionMemory(String userId, String sessionId) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        AtomicReference<reactor.core.Disposable> slot = activeRuns.remove(runKey(uid, id));
        if (slot != null) {
            reactor.core.Disposable d = slot.getAndSet(null);
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
        }
        pendingApprovals.remove(uid, id);
        sessionStore.delete(uid, id);
        deleteAgentState(uid, id);
    }

    /**
     * 订阅 Harness 事件流：每条事件立刻映射成 SSE；审批中断或浏览器取消时结束。
     *
     * <p>{@code handle} 把官方 {@link AgentEvent} 转成前端事件并 {@code sink.next}，
     * 不必先 {@code toIterable()} 把整段流拉完。{@code doOnCancel} 标记取消，
     * {@code doFinally} 再决定要不要把助手消息写入网页会话库。
     *
     * <p><b>何时调用：</b>{@link #chat} / {@link #resume} 内部。这是本项目进入 AgentScope 的唯一入口。
     * 进去之后框架会：load AgentState → 拼 system prompt（含 MEMORY.md）→ 调大模型 →
     * {@code ToolExecutor} 反射执行 {@code @Tool} 方法 → 需要 ASK 时发 {@code RequireUserConfirmEvent}
     * → 结束后 {@code MemoryFlushMiddleware} 后台抽日流水。
     */
    private Flux<SseEvent> subscribe(String userId, String sessionId, Msg msg,
                                       String lockToken, boolean resumeAfterInterrupt) {
        RunState state = new RunState(userId, sessionId);
        state.resumeAfterInterrupt = resumeAfterInterrupt;
        state.lockToken = lockToken;
        AtomicReference<reactor.core.Disposable> slot = new AtomicReference<>();
        activeRuns.put(runKey(userId, sessionId), slot);
        return harnessAgent.streamEvents(List.of(msg), runtimeContext(userId, sessionId))
                .handle((AgentEvent event, SynchronousSink<SseEvent> sink) -> onEvent(state, event, sink))
                .doOnSubscribe(s -> {
                    if (s instanceof reactor.core.Disposable disposable) {
                        slot.compareAndSet(null, disposable);
                    }
                })
                .doOnCancel(() -> state.cancelled.set(true))
                .doFinally(sig -> {
                    activeRuns.remove(runKey(userId, sessionId), slot);
                    try {
                        finish(state, userId, sessionId, sig);
                    } finally {
                        runLock.release(userId, sessionId, lockToken);
                    }
                });
    }

    /**
     * 处理一条官方事件：打活动日志 → 映射 SSE → 拼回复 / 思考 → 审批则结束流。
     *
     * <p><b>何时调用：</b>{@link #subscribe} 的 {@code handle}，Harness 每推一条 {@link AgentEvent} 一次。
     * 思考 delta 和 status 只推前端不落库；思考结束才把全文写入会话 events。
     */
    private void onEvent(RunState state, AgentEvent event, SynchronousSink<SseEvent> sink) {
        ConversationMdc.open(state.sessionId);
        try {
            if (state.cancelled.get()) {
                sink.complete();
                return;
            }
            state.activity.accept(event);
            // 审批单必须在推 interrupt 之前落 Redis；AgentState 也必须先落盘。
            // 任一失败都不要给前端可点的审批卡，否则续跑会把 approved 当新消息。
            if (event instanceof RequireUserConfirmEvent confirm) {
                state.confirmEvent = confirm;
                List<ToolUseBlock> toolCalls = confirm.getToolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    log.error("[Agent] ASK 事件没有 toolCalls，无法写入审批单");
                    sink.next(new SseEvent("error", "审批事件缺少工具调用，已中止本轮"));
                    sink.complete();
                    return;
                }
                try {
                    pendingApprovals.put(state.userId, state.sessionId, confirm.getReplyId(), toolCalls);
                } catch (Exception e) {
                    log.error("[Agent] 写入待审批失败: {}", e.getMessage());
                    sink.next(new SseEvent("error", "无法保存审批单，请重试"));
                    sink.complete();
                    return;
                }
                if (!persistHarnessState(state.userId, state.sessionId)) {
                    pendingApprovals.remove(state.userId, state.sessionId);
                    sink.next(new SseEvent("error", "无法保存中断点，请重试"));
                    sink.complete();
                    return;
                }
                state.interrupted = true;
                state.approvalDurable = true;
                log.info("[Agent] 已写入待审批 replyId={} detail={}",
                        confirm.getReplyId(), PendingToolCall.describe(toolCalls));
            }
            AgentEventMapper.MappedEvent mapped = eventMapper.map(event);
            if (mapped.skipped()) {
                return;
            }
            if ("token".equals(mapped.event())) {
                state.reply.append(mapped.data());
            } else if ("thinking".equals(mapped.event())) {
                absorbThinkingDelta(state.thinking, mapped.data());
                if (mapped.persist()) {
                    flushThinking(state.toolEvents, state.thinking);
                }
            } else if (mapped.persist()) {
                state.toolEvents.add(storedEvent(mapped));
            }
            sink.next(new SseEvent(mapped.event(), mapped.data()));
            if (mapped.interrupt()) {
                sink.complete();
            }
        } finally {
            ConversationMdc.clear();
        }
    }

    /**
     * 流结束落库：审批中断优先于取消（SSE complete 常被当成 CANCEL）；
     * 真取消且没有 ASK 才不写助手消息；正常结束清 pending 并写入完整回复。
     *
     * <p><b>何时调用：</b>{@link #subscribe} 的 {@code doFinally}，无论 complete / cancel / error。
     * {@code ON_ERROR} 且并非审批中断时不落库，错误文案由 Controller {@code onErrorResume} 推给前端。
     */
    private void finish(RunState state, String userId, String sessionId, SignalType sig) {
        ConversationMdc.open(sessionId);
        try {
            if (state.interrupted && state.confirmEvent != null) {
                if (state.approvalDurable) {
                    persistInterrupt(state, userId, sessionId);
                } else {
                    state.activity.finish("", false, true);
                }
                return;
            }
            if (sig == SignalType.CANCEL || state.cancelled.get()) {
                state.activity.finish("", false, true);
                return;
            }
            if (sig == SignalType.ON_ERROR) {
                return;
            }
            flushThinking(state.toolEvents, state.thinking);
            pendingApprovals.remove(userId, sessionId);
            String finalReply = state.reply.toString();
            ChatMessageRecord record = ChatMessageRecord.builder()
                    .role("assistant")
                    .content(finalReply)
                    .timestamp(Instant.now())
                    .events(new ArrayList<>(state.toolEvents))
                    .build();
            // HITL 续跑：合并到中断时那条助手消息，避免刷新后裂成两条
            if (state.resumeAfterInterrupt) {
                if (!sessionStore.completeLastAssistant(userId, sessionId, record)) {
                    sessionStore.appendMessage(userId, sessionId, record);
                }
            } else {
                sessionStore.appendMessage(userId, sessionId, record);
            }
            state.activity.finish(finalReply, false, false);
        } finally {
            ConversationMdc.clear();
        }
    }

    /**
     * 把等待审批的说明写入网页会话。Redis 审批单已在 {@link #onEvent} 写过；这里再 put 一次刷新 TTL。
     */
    private void persistInterrupt(RunState state, String userId, String sessionId) {
        flushThinking(state.toolEvents, state.thinking);
        List<ToolUseBlock> toolCalls = state.confirmEvent.getToolCalls();
        // 审批单 / AgentState 已在 onEvent 落盘；这里只写网页历史，并保留中断前已流出的正文
        String note = interruptNote(toolCalls);
        String prior = state.reply.toString();
        String content = prior.isBlank() ? note : prior + "\n\n" + note;
        sessionStore.appendMessage(userId, sessionId, ChatMessageRecord.builder()
                .role("assistant")
                .content(content)
                .timestamp(Instant.now())
                .events(new ArrayList<>(state.toolEvents))
                .build());
        state.activity.finish(content, true, false);
    }

    /**
     * 审批中断说明：工具名、路径，以及即将写入的正文预览。刷新页面后用户仍能看见批了什么。
     */
    static String interruptNote(List<ToolUseBlock> toolCalls) {
        String header = "⏸ 等待人工审批：" + PendingToolCall.describe(toolCalls);
        if (toolCalls == null || toolCalls.isEmpty()) {
            return header + "\n（请在前端批准或拒绝后继续）";
        }
        StringBuilder note = new StringBuilder(header);
        for (ToolUseBlock block : toolCalls) {
            String content = PendingToolCall.contentOf(block);
            if (content.isBlank()) {
                continue;
            }
            note.append("\n\n即将写入内容：\n").append(AgentActivityLogger.clip(content, 800));
        }
        note.append("\n\n（请在前端批准或拒绝后继续）");
        return note.toString();
    }

    /**
     * 把当前会话内存里的 AgentState 立刻写入 MySQL。ASK 时若先 cancel 流再等 doFinally，
     * 框架可能跳过保存，续跑就丢了 pending {@code write_file}。
     */
    private boolean persistHarnessState(String userId, String sessionId) {
        try {
            harnessAgent.getDelegate().saveAgentState(userId, sessionId);
            log.info("[Agent] 已保存中断点 AgentState");
            return true;
        } catch (Exception e) {
            log.error("[Agent] 保存中断点 AgentState 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构建 {@link RuntimeContext}，带上 userId + sessionId。
     * 框架据此寻址 AgentState 槽位、工作区文件命名空间（IsolationScope.USER）。
     *
     * <p><b>何时调用：</b>{@link #subscribe} 调 {@code streamEvents} 之前。
     * 这是本项目把「当前用户/会话」交给 AgentScope 的唯一方式。
     */
    private RuntimeContext runtimeContext(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
    }

    /**
     * 把映射后的非 token 事件压成可 JSON 序列化的 Map，存进 {@link ChatMessageRecord#getEvents()}。
     * plan 事件额外抄一份 {@code todos}，方便前端任务列表直接读。
     */
    private Map<String, Object> storedEvent(AgentEventMapper.MappedEvent mapped) {
        Map<String, Object> stored = new HashMap<>();
        stored.put("type", mapped.event());
        stored.put("data", mapped.data());
        if ("plan".equals(mapped.event())) {
            stored.put("todos", mapped.data());
        }
        Map<String, Object> payload = readJsonMap(mapped.data());
        if (payload.containsKey("tool")) {
            stored.put("tool", payload.get("tool"));
        }
        if (payload.containsKey("phase")) {
            stored.put("phase", payload.get("phase"));
        }
        if (payload.containsKey("id")) {
            stored.put("id", payload.get("id"));
        }
        if (payload.containsKey("name")) {
            stored.put("name", payload.get("name"));
        }
        return stored;
    }

    /** 思考 delta 拼到缓冲区；start/end 没有 text，直接忽略。 */
    private void absorbThinkingDelta(StringBuilder thinking, String data) {
        Map<String, Object> payload = readJsonMap(data);
        if (!"delta".equals(payload.get("phase"))) {
            return;
        }
        Object text = payload.get("text");
        if (text != null) {
            thinking.append(text);
        }
    }

    /** 思考结束后把全文压进 events，刷新页面还能展开。缓冲区随即清空。 */
    private void flushThinking(List<Map<String, Object>> toolEvents, StringBuilder thinking) {
        if (thinking.length() == 0) {
            return;
        }
        Map<String, Object> stored = new HashMap<>();
        stored.put("type", "thinking");
        stored.put("text", thinking.toString());
        toolEvents.add(stored);
        thinking.setLength(0);
    }

    private Map<String, Object> readJsonMap(String data) {
        if (data == null || data.isBlank() || data.charAt(0) != '{') {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(data, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 尽力删 MySQL 里该会话的 AgentState。失败只打 warn：网页会话已经删了，残留 state 不会再被打开。
     */
    private void deleteAgentState(String userId, String sessionId) {
        try {
            agentStateStore.delete(userId, sessionId);
        } catch (Exception e) {
            log.warn("[AssistantChat] 清理 agent state 失败 session={}: {}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * 一轮 {@code streamEvents} 的可变累加器：回复正文、思考全文、可回放事件、审批现场。
     * 只活在这一次订阅里，不跨请求共享。
     */
    private static final class RunState {
        private final String userId;
        private final String sessionId;
        private final StringBuilder reply = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final List<Map<String, Object>> toolEvents = new ArrayList<>();
        private final AgentActivityLogger activity = new AgentActivityLogger();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private boolean interrupted;
        private boolean resumeAfterInterrupt;
        private boolean approvalDurable;
        private String lockToken;
        private RequireUserConfirmEvent confirmEvent;

        private RunState(String userId, String sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }
    }
}
