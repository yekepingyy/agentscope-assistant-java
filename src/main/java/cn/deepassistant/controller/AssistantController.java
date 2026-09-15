package cn.deepassistant.controller;

import cn.deepassistant.memory.SessionStore;
import cn.deepassistant.model.ChatRequest;
import cn.deepassistant.model.HistorySearchResponse;
import cn.deepassistant.model.ResumeRequest;
import cn.deepassistant.model.SessionDetail;
import cn.deepassistant.model.SessionSummary;
import cn.deepassistant.model.UserMemoryProfile;
import cn.deepassistant.service.AssistantChatService;
import cn.deepassistant.service.UserMemoryQueryService;
import cn.deepassistant.util.ConversationMdc;
import cn.deepassistant.util.SessionIds;
import cn.deepassistant.util.UserIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP API。聊天走 SSE；历史检索和档案读官方记忆文件。
 *
 * <p>多用户隔离：所有端点都接收 userId——POST 请求从 body 的 {@code userId} 字段取，
 * GET 请求从 {@code X-User-Id} header 或 {@code ?userId=} 查询参数取。
 * 不传时回退到 {@code "local"}，等价于原来的单用户模式。
 *
 * <p>日志里的 {@code SESSION_ID}：聊天 / 续跑的 sessionId 在 JSON body 里，拦截器读不到，
 * 必须在本类里打开 MDC；且 SSE 真正跑在 {@code boundedElastic} 工作线程，要在那条线程再打开一次。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantChatService chatService;
    private final SessionStore sessionStore;
    private final UserMemoryQueryService memoryQueryService;

    /** 健康检查回显当前大模型名，方便运维确认配的是哪套。 */
    @Value("${llm.model}")
    private String model;
    /** 健康检查回显是否启用智谱 MCP 搜索。 */
    @Value("${mcp.zhipu.enabled:true}")
    private boolean mcpEnabled;

    public AssistantController(AssistantChatService chatService,
                               SessionStore sessionStore,
                               UserMemoryQueryService memoryQueryService) {
        this.chatService = chatService;
        this.sessionStore = sessionStore;
        this.memoryQueryService = memoryQueryService;
    }

    /**
     * 新一轮对话：校验入参 → 立刻把 sessionId 推给前端 → 在工作线程里跑 Harness 并转成 SSE。
     *
     * <p><b>何时调用：</b>前端聊天框发送，{@code POST /api/assistant/chat}。AgentScope 不会调 Controller。
     *
     * <p>返回的 Flux 分两段 {@code concat}：先发 {@code session} 事件（新建会话时前端要存这个 id），
     * 再发 token / tool / interrupt / done。前一段在 HTTP 线程就能发出去，不必等模型。
     *
     * @param request {@code message} 必填；{@code sessionId} 空则新建；{@code userId} 空则 {@code local}
     */
    @PostMapping(value = "/assistant/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
        // 空消息直接结束流，避免空跑一次大模型
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Flux.just(sse("error", "消息不能为空"), sse("done", "[DONE]"));
        }
        final String userId;
        final String sessionId;
        try {
            userId = UserIds.normalize(request.getUserId());
            // 没带 sessionId 就现场生成 UUID，后续所有 Redis 槽位都用这个 id
            sessionId = SessionIds.normalizeOrCreate(request.getSessionId());
        } catch (IllegalArgumentException e) {
            return Flux.just(sse("error", e.getMessage()), sse("done", "[DONE]"));
        }
        // HTTP 线程上的 START 日志：拦截器读不到 body 里的 sessionId，这里临时打开再清掉
        ConversationMdc.open(sessionId);
        try {
            log.info("[Chat] START session={} msgChars={}", sessionId, request.getMessage().length());
        } finally {
            ConversationMdc.clear();
        }
        Flux<ServerSentEvent<String>> sessionEvent = Flux.just(sse("session", sessionId));
        Flux<ServerSentEvent<String>> chatStream = Flux.<ServerSentEvent<String>>create(sink -> {
            // 浏览器断开 SSE 时 Reactor 会 cancel/dispose；标志位置位后不再往已关的流里写
            AtomicBoolean cancelled = new AtomicBoolean(false);
            sink.onCancel(() -> cancelled.set(true));
            sink.onDispose(() -> cancelled.set(true));
            // 必须在 boundedElastic 线程里打开 MDC：Harness 的 log 也打在这条线程
            ConversationMdc.run(sessionId, () -> {
                try {
                    chatService.chat(userId, sessionId, request.getMessage(), event -> {
                        if (!cancelled.get()) {
                            sink.next(sse(event.event(), event.data()));
                        }
                    }, cancelled);
                    if (!cancelled.get()) {
                        sink.next(sse("done", "[DONE]"));
                    }
                    sink.complete();
                } catch (Exception e) {
                    log.error("[Chat] FAIL session={}", sessionId, e);
                    if (!cancelled.get()) {
                        String msg = friendlyError(e);
                        sink.next(sse("error", msg));
                        // 再发一条 token，让前端聊天气泡里也能看到失败原因，而不是只有侧栏 error
                        sink.next(sse("token", "抱歉，本次请求失败：" + msg));
                        sink.next(sse("done", "[DONE]"));
                    }
                    sink.complete();
                }
            });
        }).subscribeOn(Schedulers.boundedElastic());
        return Flux.concat(sessionEvent, chatStream);
    }

    /**
     * HITL 续跑：用户批准或拒绝写文件后，把确认结果塞回 Harness 继续往下跑。
     *
     * <p><b>何时调用：</b>前端点批准/拒绝，{@code POST /api/assistant/resume}。
     * 框架侧对应「上次 {@code RequireUserConfirmEvent} 之后的下一次 {@code streamEvents}」。
     *
     * <p>sessionId 必须已存在（不能现场新建）。先查 Redis 里有没有待审批记录，
     * 没有就立刻返回 error，避免空跑。
     */
    @PostMapping(value = "/assistant/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> resume(@RequestBody ResumeRequest request) {
        final String userId;
        final String sessionId;
        try {
            userId = UserIds.normalize(request.getUserId());
            sessionId = SessionIds.requireValid(request.getSessionId());
        } catch (IllegalArgumentException e) {
            return Flux.just(sse("error", e.getMessage()), sse("done", "[DONE]"));
        }
        ConversationMdc.open(sessionId);
        try {
            if (!chatService.hasPendingApproval(userId, sessionId)) {
                return Flux.just(sse("error", "当前会话没有待审批的操作"), sse("done", "[DONE]"));
            }
            log.info("[Resume] START session={} approved={}", sessionId, request.isApproved());
        } finally {
            ConversationMdc.clear();
        }
        return Flux.<ServerSentEvent<String>>create(sink -> {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            sink.onCancel(() -> cancelled.set(true));
            sink.onDispose(() -> cancelled.set(true));
            ConversationMdc.run(sessionId, () -> {
                try {
                    chatService.resume(userId, sessionId, request.isApproved(), event -> {
                        if (!cancelled.get()) {
                            sink.next(sse(event.event(), event.data()));
                        }
                    }, cancelled);
                    if (!cancelled.get()) {
                        sink.next(sse("done", "[DONE]"));
                    }
                    sink.complete();
                } catch (Exception e) {
                    log.error("[Resume] FAIL session={}", sessionId, e);
                    if (!cancelled.get()) {
                        String msg = friendlyError(e);
                        sink.next(sse("error", msg));
                        sink.next(sse("token", "抱歉，续跑失败：" + msg));
                        sink.next(sse("done", "[DONE]"));
                    }
                    sink.complete();
                }
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 列出指定用户的所有会话摘要（侧栏）。query 优先于 header。
     */
    @GetMapping("/sessions")
    public List<SessionSummary> listSessions(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        return sessionStore.listSessions(userId);
    }

    /**
     * 打开一条会话的全文。不存在抛 400（前端据此清掉过期的 localStorage sessionId）。
     * {@code pendingApproval} 现查 Redis，告诉前端这条会话是否还卡在写文件审批。
     */
    @GetMapping("/sessions/{id}")
    public SessionDetail getSession(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        SessionDetail detail = sessionStore.get(userId, id);
        if (detail == null) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        detail.setPendingApproval(chatService.hasPendingApproval(userId, id));
        return detail;
    }

    /**
     * 删除网页会话 JSON，同时清掉 AgentState 和待审批记录，避免幽灵会话续跑。
     */
    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> deleteSession(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        chatService.clearSessionMemory(userId, id);
        return Map.of("ok", true, "id", id);
    }

    /**
     * 侧栏「新对话」：只建一条空会话记录，不调大模型。标题先写成「新对话」。
     */
    @PostMapping("/sessions")
    public SessionDetail createSession(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        return sessionStore.getOrCreate(userId, null, "新对话");
    }

    /**
     * 历史检索：搜网页会话库里的原文，只搜指定用户。
     *
     * @param q     关键词，空则返回空列表
     * @param limit 最多返回条数，底层还会再 cap 到 50
     */
    @GetMapping("/history/search")
    public HistorySearchResponse searchHistory(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        return memoryQueryService.searchHistory(userId, q, limit);
    }

    /**
     * 用户档案：使用次数 + 官方 MEMORY.md + 日流水账，全部按用户隔离。
     */
    @GetMapping("/memory")
    public UserMemoryProfile memoryProfile(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = UserIds.normalize(queryUserId != null ? queryUserId : headerUserId);
        return memoryQueryService.profile(userId);
    }

    /**
     * 前端展示用的能力清单：子 Agent 名 + Harness 已打开的工具名。不访问 Redis。
     */
    @GetMapping("/agents")
    public Map<String, Object> agents() {
        return Map.of(
                "agents", List.of("research-agent", "general-purpose"),
                "harness", List.of(
                        "todo_write", "filesystem", "agent_spawn",
                        "memory_search", "memory_get", "memory_save", "session_search",
                        "search_conversation_history", "get_user_usage",
                        "plan_mode", "skill_manage"));
    }

    /**
     * 存活探测 + 运行时关键开关。{@code storage=redis} / {@code multi_replica=true} 表示生产只走 Redis。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "framework", "AgentScope-Java-2.0-HarnessAgent",
                "edition", "agentscope",
                "model", model,
                "mcp_enabled", mcpEnabled,
                "memory", "official-flush-consolidation",
                "storage", "redis",
                "multi_user", true,
                "multi_replica", true,
                "version", "2.0.0");
    }

    /**
     * 非法 userId / sessionId、会话不存在等业务校验失败统一成 400 JSON，前端可据此重置本地会话。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage());
    }

    /** 组装一条 SSE：event 名给前端 switch，data 为空时发空串避免客户端拿到 null。 */
    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data == null ? "" : data).build();
    }

    /**
     * 把底层异常收成给人看的短句。鉴权失败单独提示要配 {@code LLM_API_KEY}；其它超长堆栈截断到 300 字。
     */
    private static String friendlyError(Throwable e) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (raw.contains("401") || raw.contains("令牌") || raw.contains("Unauthorized")) {
            return "大模型鉴权失败，请设置环境变量 LLM_API_KEY。";
        }
        if (raw.length() > 300) {
            return raw.substring(0, 300) + "...";
        }
        return raw;
    }
}
