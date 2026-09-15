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
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * HTTP API。聊天走 SSE；历史检索和档案读官方记忆文件。
 *
 * <p>多用户隔离：所有端点都接收 userId——POST 请求从 body 的 {@code userId} 字段取，
 * GET 请求从 {@code X-User-Id} header 或 {@code ?userId=} 查询参数取。
 * 不传时回退到 {@code "local"}，等价于原来的单用户模式。
 *
 * <p>日志里的 {@code SESSION_ID}：聊天 / 续跑的 sessionId 在 JSON body 里，拦截器读不到，
 * 必须在本类 START 日志里打开 MDC。Harness 每条事件到达时 {@link AssistantChatService} 会再打开一次。
 * SSE 订阅在 {@code boundedElastic}，避免占死 Netty 的 HTTP 线程。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantChatService chatService;
    private final SessionStore sessionStore;
    private final UserMemoryQueryService memoryQueryService;
    private final HarnessAgent harnessAgent;

    /** 健康检查回显当前大模型名，方便运维确认配的是哪套。 */
    @Value("${llm.model}")
    private String model;

    public AssistantController(AssistantChatService chatService,
                               SessionStore sessionStore,
                               UserMemoryQueryService memoryQueryService,
                               HarnessAgent harnessAgent) {
        this.chatService = chatService;
        this.sessionStore = sessionStore;
        this.memoryQueryService = memoryQueryService;
        this.harnessAgent = harnessAgent;
    }

    /**
     * 新一轮对话：校验入参 → 立刻把 sessionId 推给前端 → 订阅 Harness {@code Flux} 转成 SSE。
     *
     * <p><b>何时调用：</b>前端聊天框发送，{@code POST /api/assistant/chat}。AgentScope 不会调 Controller。
     *
     * <p>返回的 Flux 分两段 {@code concat}：先发 {@code session} 事件（新建会话时前端要存这个 id），
     * 再按模型 delta 逐条推 token / thinking / tool / interrupt / done。
     * {@code session} 在 HTTP 线程就能发出去，不必等模型；后面的事件流订阅在 {@code boundedElastic}。
     *
     * @param request {@code message} 必填；{@code sessionId} 空则新建；{@code userId} 空则 {@code local}
     */
    @PostMapping(value = "/assistant/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> chat(@RequestBody ChatRequest request) {
        // 空消息直接结束流，避免空跑一次大模型
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return sseResponse(Flux.just(sse("error", "消息不能为空"), sse("done", "[DONE]")));
        }
        final String userId;
        final String sessionId;
        try {
            userId = UserIds.normalize(request.getUserId());
            // 没带 sessionId 就现场生成 UUID，后续所有 Redis 槽位都用这个 id
            sessionId = SessionIds.normalizeOrCreate(request.getSessionId());
        } catch (IllegalArgumentException e) {
            return sseResponse(Flux.just(sse("error", e.getMessage()), sse("done", "[DONE]")));
        }
        // HTTP 线程上的 START 日志：拦截器读不到 body 里的 sessionId，这里临时打开再清掉
        ConversationMdc.open(sessionId);
        try {
            if (chatService.hasPendingApproval(userId, sessionId)) {
                return sseResponse(Flux.just(sse("error", "当前会话有待审批的操作，请先批准或拒绝"), sse("done", "[DONE]")));
            }
            log.info("[Chat] START session={} msgChars={}", sessionId, request.getMessage().length());
        } finally {
            ConversationMdc.clear();
        }
        Flux<ServerSentEvent<String>> sessionEvent = Flux.just(sse("session", sessionId));
        Flux<ServerSentEvent<String>> chatStream = pipe(
                chatService.chat(userId, sessionId, request.getMessage()),
                sessionId,
                "[Chat] FAIL session={}",
                "抱歉，本次请求失败：");
        return sseResponse(Flux.concat(sessionEvent, chatStream));
    }

    /**
     * HITL 续跑：用户批准或拒绝写文件后，把确认结果塞回 Harness 继续往下跑。
     *
     * <p><b>何时调用：</b>前端点批准/拒绝，{@code POST /api/assistant/resume}。
     * 框架侧对应「上次 {@code RequireUserConfirmEvent} 之后的下一次 {@code streamEvents}」。
     *
     * <p>sessionId 必须已存在（不能现场新建）。先查 Redis 里有没有待审批记录，
     * 没有就立刻返回 error，避免空跑。事件流同样直接订阅 Harness {@code Flux}，token 随到随推。
     */
    @PostMapping(value = "/assistant/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> resume(@RequestBody ResumeRequest request) {
        final String userId;
        final String sessionId;
        try {
            userId = UserIds.normalize(request.getUserId());
            sessionId = SessionIds.requireValid(request.getSessionId());
        } catch (IllegalArgumentException e) {
            return sseResponse(Flux.just(sse("error", e.getMessage()), sse("done", "[DONE]")));
        }
        ConversationMdc.open(sessionId);
        try {
            if (!chatService.hasPendingApproval(userId, sessionId)) {
                return sseResponse(Flux.just(sse("error", "当前会话没有待审批的操作"), sse("done", "[DONE]")));
            }
            log.info("[Resume] START session={} approved={}", sessionId, request.isApproved());
        } finally {
            ConversationMdc.clear();
        }
        return sseResponse(pipe(
                chatService.resume(userId, sessionId, request.isApproved()),
                sessionId,
                "[Resume] FAIL session={}",
                "抱歉，续跑失败："));
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
     * 前端展示用的能力清单：子 Agent 名 + 当前 Toolkit 真实工具名。
     */
    @GetMapping("/agents")
    public Map<String, Object> agents() {
        List<String> tools = new ArrayList<>(harnessAgent.getToolkit().getToolNames());
        tools.sort(Comparator.naturalOrder());
        return Map.of(
                "agents", List.of("research-agent", "general-purpose"),
                "harness", tools);
    }

    /**
     * 存活探测 + 运行时关键开关。{@code storage=mysql+redis}：AgentState 在 MySQL，
     * 工作区 / 网页会话 / 待审批在 Redis。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        List<String> mcpTools = new ArrayList<>();
        for (String name : harnessAgent.getToolkit().getToolNames()) {
            AgentTool tool = harnessAgent.getToolkit().getTool(name);
            if (tool instanceof McpTool) {
                mcpTools.add(name);
            }
        }
        mcpTools.sort(Comparator.naturalOrder());
        return Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("framework", "AgentScope-Java-2.0-HarnessAgent"),
                Map.entry("edition", "agentscope"),
                Map.entry("model", model),
                Map.entry("mcp_enabled", !mcpTools.isEmpty()),
                Map.entry("mcp", "tools.json"),
                Map.entry("mcp_tools", mcpTools),
                Map.entry("memory", "official-flush-consolidation"),
                Map.entry("storage", "mysql+redis"),
                Map.entry("multi_user", true),
                Map.entry("multi_replica", true),
                Map.entry("version", "2.0.0"));
    }

    /**
     * 非法 userId / sessionId、会话不存在等业务校验失败统一成 400 JSON，前端可据此重置本地会话。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage());
    }

    /**
     * 把服务层事件流接成 SSE：映射 event 名、补 {@code done}、失败时推 error + 气泡文案。
     *
     * <p>{@code subscribeOn(boundedElastic)} 让 Harness / MCP 的阻塞 IO 离开 Netty 线程。
     * 浏览器断开 SSE 时 Reactor 会 cancel，服务层 {@code doOnCancel} 据此不落半截助手消息。
     */
    private Flux<ServerSentEvent<String>> pipe(Flux<AssistantChatService.SseEvent> source,
                                               String sessionId,
                                               String failLog,
                                               String failPrefix) {
        return source
                .map(ev -> sse(ev.event(), ev.data()))
                .concatWith(Flux.just(sse("done", "[DONE]")))
                .onErrorResume(e -> {
                    log.error(failLog, sessionId, e);
                    String msg = friendlyError(e);
                    return Flux.just(
                            sse("error", msg),
                            sse("token", failPrefix + msg),
                            sse("done", "[DONE]"));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 关 Nginx / 反向代理缓冲，否则 SSE 会攒一包才到浏览器，看起来又不流式了。
     */
    private static ResponseEntity<Flux<ServerSentEvent<String>>> sseResponse(
            Flux<ServerSentEvent<String>> body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    /** 组装一条 SSE：event 名给前端 switch，data 为空时发空串避免客户端拿到 null。 */
    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data == null ? "" : data).build();
    }

    /**
     * 把底层异常收成给人看的短句。沿 cause 链找到根因；鉴权失败单独提示要配 {@code LLM_API_KEY}；
     * 其它超长堆栈截断到 300 字。
     */
    private static String friendlyError(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String raw = cur.getMessage() == null ? e.getClass().getSimpleName() : cur.getMessage();
        if (raw.contains("401") || raw.contains("令牌") || raw.contains("Unauthorized")) {
            return "大模型鉴权失败，请设置环境变量 LLM_API_KEY。";
        }
        if (raw.length() > 300) {
            return raw.substring(0, 300) + "...";
        }
        return raw;
    }
}
