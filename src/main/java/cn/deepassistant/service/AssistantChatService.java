package cn.deepassistant.service;

import cn.deepassistant.memory.SessionStore;
import cn.deepassistant.model.ChatMessageRecord;
import cn.deepassistant.redis.RedisPendingApprovalStore;
import cn.deepassistant.util.SessionIds;
import cn.deepassistant.util.UserIds;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 把一次用户消息交给官方 {@link HarnessAgent}，再把事件映射成网页 SSE。
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

    private final HarnessAgent harnessAgent;
    private final SessionStore sessionStore;
    private final AgentEventMapper eventMapper;
    private final AgentStateStore agentStateStore;
    private final RedisPendingApprovalStore pendingApprovals;

    public AssistantChatService(HarnessAgent harnessAgent,
                                SessionStore sessionStore,
                                AgentEventMapper eventMapper,
                                AgentStateStore agentStateStore,
                                RedisPendingApprovalStore pendingApprovals) {
        this.harnessAgent = harnessAgent;
        this.sessionStore = sessionStore;
        this.eventMapper = eventMapper;
        this.agentStateStore = agentStateStore;
        this.pendingApprovals = pendingApprovals;
    }

    /**
     * 推给 Controller 的一条 SSE 载荷。{@code event} 对应前端 switch（token / tool / interrupt / done）。
     */
    public record SseEvent(String event, String data) {
    }

    /**
     * 该会话是否卡在写文件审批。前端打开历史会话时用来决定要不要显示「批准 / 拒绝」。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions/{id}} 填 {@code pendingApproval}；
     * {@code POST /api/assistant/resume} 入口校验。AgentScope 不调这个方法。
     */
    public boolean hasPendingApproval(String userId, String sessionId) {
        return pendingApprovals.exists(UserIds.normalize(userId), sessionId);
    }

    /**
     * 新用户消息：确保会话存在 → 把 user 消息写入网页会话库 → 把消息交给 Harness 流式跑完。
     *
     * <p><b>何时调用：</b>仅 {@code POST /api/assistant/chat}。进到 {@link #streamEvents} 之后，
     * AgentScope 会自己 load/save AgentState、跑工具、Flush 记忆，本方法不再插手。
     *
     * @param eventSink 每映射出一条前端事件就回调一次；Controller 再包装成 SSE
     * @param cancelled 浏览器断开时为 true，{@link #run} 据此提前退出循环
     * @return 最终助手回复全文（审批中断时返回等待说明）
     */
    public String chat(String userId, String sessionId, String userMessage,
                       Consumer<SseEvent> eventSink, AtomicBoolean cancelled) {
        String uid = UserIds.normalize(userId);
        // 没有会话就现场建一条，标题取首句；已有则原样返回
        var session = sessionStore.getOrCreate(uid, sessionId, userMessage);
        String id = session.getId();
        sessionStore.appendMessage(uid, id, ChatMessageRecord.builder()
                .role("user")
                .content(userMessage)
                .timestamp(Instant.now())
                .build());
        RuntimeContext ctx = runtimeContext(uid, id);
        return run(uid, id, streamEvents(new UserMessage(userMessage), ctx), eventSink, cancelled);
    }

    /**
     * 把用户对写文件工具的批准 / 拒绝塞进 {@link Msg} 的 metadata，让 Harness 从中断点继续。
     *
     * <p><b>何时调用：</b>仅 {@code POST /api/assistant/resume}（前端点批准/拒绝）。
     *
     * <p>框架约定：续跑消息必须带 {@link Msg#METADATA_CONFIRM_RESULTS}，值为
     * {@code List<ConfirmResult>}，每条对应当初 Ask 的一个 {@link ToolUseBlock}。
     * {@code ReActAgent} 读到这份 metadata 后才会真正执行（或跳过）那个写文件工具。
     */
    public String resume(String userId, String sessionId, boolean approved,
                         Consumer<SseEvent> eventSink, AtomicBoolean cancelled) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        List<ToolUseBlock> toolCalls = pendingApprovals.get(uid, id);
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalStateException("当前会话没有待审批的操作: " + uid + "/" + id);
        }
        // 同一审批单可能有多个写文件调用，每个都要带上同一份批准结果
        List<ConfirmResult> confirmResults = toolCalls.stream()
                .map(t -> new ConfirmResult(approved, t))
                .toList();
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
        Msg resumeMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(approved ? "approved" : "denied")
                .metadata(meta)
                .build();
        return run(uid, id, streamEvents(resumeMsg, runtimeContext(uid, id)), eventSink, cancelled);
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
        pendingApprovals.remove(uid, id);
        sessionStore.delete(uid, id);
        deleteAgentState(uid, id);
    }

    /**
     * 阻塞消费 Harness 事件流：映射 → 推 SSE → 拼回复 → 按中断/完成落库。
     *
     * <p>{@code toIterable()} 会卡住当前线程直到流结束，所以 Controller 必须把它放到
     * {@code boundedElastic}，否则会占死 Netty 的 HTTP 线程。
     *
     * <p><b>何时调用：</b>{@link #chat} / {@link #resume} 内部。循环里每一条 {@link AgentEvent}
     * 都是框架推的（token delta、工具起止、写文件 ASK 的 {@code RequireUserConfirmEvent}）。
     */
    private String run(String userId, String sessionId, Flux<AgentEvent> stream,
                       Consumer<SseEvent> eventSink, AtomicBoolean cancelled) {
        List<Map<String, Object>> toolEvents = new ArrayList<>();
        StringBuilder reply = new StringBuilder();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        RequireUserConfirmEvent confirmEvent = null;
        try {
            Iterable<AgentEvent> events = stream.toIterable();
            for (AgentEvent event : events) {
                if (cancelled.get()) {
                    break;
                }
                // 先记下原始审批事件，循环结束后才能把 toolCalls 写入 Redis
                if (event instanceof RequireUserConfirmEvent confirm) {
                    confirmEvent = confirm;
                    interrupted.set(true);
                }
                AgentEventMapper.MappedEvent mapped = eventMapper.map(event);
                if (mapped.skipped()) {
                    // 心跳、内部 delta 空串等前端不关心的事件直接丢掉
                    continue;
                }
                if ("token".equals(mapped.event())) {
                    reply.append(mapped.data());
                } else {
                    // tool / plan / agent / interrupt 进会话 JSON 的 events 数组，刷新后还能回放
                    toolEvents.add(storedEvent(mapped));
                }
                eventSink.accept(new SseEvent(mapped.event(), mapped.data()));
                if (mapped.interrupt()) {
                    // 审批事件已经推给前端，后面的流即使还有也不再消费
                    break;
                }
            }
        } catch (Exception e) {
            log.error("[AssistantChat] 执行失败 session={}", sessionId, e);
            throw new RuntimeException(e.getMessage(), e);
        }
        if (cancelled.get()) {
            // 用户关掉页面：不写助手消息，避免半截回复污染历史
            return "";
        }
        if (interrupted.get() && confirmEvent != null) {
            pendingApprovals.put(userId, sessionId, confirmEvent.getToolCalls());
            String note = "⏸ 等待人工审批："
                    + (confirmEvent.getToolCalls().isEmpty()
                    ? "未知工具"
                    : confirmEvent.getToolCalls().get(0).getName())
                    + "（请在前端批准或拒绝后继续）";
            sessionStore.appendMessage(userId, sessionId, ChatMessageRecord.builder()
                    .role("assistant")
                    .content(note)
                    .timestamp(Instant.now())
                    .events(new ArrayList<>(toolEvents))
                    .build());
            return note;
        }
        // 正常结束或拒绝后续跑完：清掉可能残留的 pending，把完整回复写入网页会话
        pendingApprovals.remove(userId, sessionId);
        String finalReply = reply.toString();
        sessionStore.appendMessage(userId, sessionId, ChatMessageRecord.builder()
                .role("assistant")
                .content(finalReply)
                .timestamp(Instant.now())
                .events(new ArrayList<>(toolEvents))
                .build());
        return finalReply;
    }

    /**
     * 把一条用户消息（或续跑确认消息）交给 Harness，拿回官方事件流。
     *
     * <p><b>何时调用：</b>{@link #chat} / {@link #resume}。这是本项目进入 AgentScope 的唯一入口。
     * 进去之后框架会：load AgentState → 拼 system prompt（含 MEMORY.md）→ 调大模型 →
     * {@code ToolExecutor} 反射执行 {@code @Tool} 方法 → 需要 ASK 时发 {@code RequireUserConfirmEvent}
     * → 结束后 {@code MemoryFlushMiddleware} 后台抽日流水。
     */
    private Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext ctx) {
        return harnessAgent.streamEvents(List.of(msg), ctx);
    }

    /**
     * 构建 {@link RuntimeContext}，带上 userId + sessionId。
     * 框架据此寻址 AgentState 槽位、工作区文件命名空间（IsolationScope.USER）。
     *
     * <p><b>何时调用：</b>{@link #chat}/{@link #resume} 调 {@code streamEvents} 之前。
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
        return stored;
    }

    /**
     * 尽力删 Redis 里该会话的 AgentState。失败只打 warn：网页会话已经删了，残留 state 不会再被打开。
     */
    private void deleteAgentState(String userId, String sessionId) {
        try {
            agentStateStore.delete(userId, sessionId);
        } catch (Exception e) {
            log.warn("[AssistantChat] 清理 agent state 失败 session={}: {}",
                    sessionId, e.getMessage());
        }
    }

}
