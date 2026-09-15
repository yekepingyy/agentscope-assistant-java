package cn.deepassistant.util;

import org.slf4j.MDC;

/**
 * 把本轮对话的 sessionId 写入 SLF4J MDC，供 {@code logback.xml} 的
 * {@code %X{SESSION_ID}} 打印。线程局部，用完必须 {@link #clear()}，避免线程池串话。
 *
 * <p>不写入 userId：日志里出现用户标识会泄露用户信息。
 *
 * <p>SSE 订阅在 {@code Schedulers.boundedElastic()}，HTTP 线程上的 MDC 传不过去。
 * Controller 的 START 日志在 HTTP 线程 {@link #open}/{@link #clear}；
 * Harness 每条事件到达时 {@code AssistantChatService} 再 open 一次。
 */
public final class ConversationMdc {

    /** 与 {@code logback.xml} 里 {@code %X{SESSION_ID}} 的 key 必须一字不差。 */
    public static final String SESSION_ID = "SESSION_ID";

    private ConversationMdc() {
    }

    /**
     * 把 sessionId 放进当前线程 MDC。空白则移除，避免上一轮残留的 id 被下一条日志带走。
     *
     * <p><b>何时调用：</b>本项目拦截器 / Controller START 日志；{@code AssistantChatService}
     * 处理每条 Harness 事件和 {@code doFinally} 收尾。不是 AgentScope 调的。
     */
    public static void open(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            MDC.put(SESSION_ID, sessionId);
        } else {
            MDC.remove(SESSION_ID);
        }
    }

    /** 只清 SESSION_ID，不影响其它业务自己放进 MDC 的 key。 */
    public static void clear() {
        MDC.remove(SESSION_ID);
    }

    /**
     * 在目标线程打开 MDC，执行完（含抛异常）后清掉。
     *
     * <p><b>何时调用：</b>单测或需要「整段 Runnable 都带 SESSION_ID」的同步代码。
     * 真流式对话不再包一整段 {@code run}，改为事件到达时 {@link #open}。
     */
    public static void run(String sessionId, Runnable action) {
        open(sessionId);
        try {
            action.run();
        } finally {
            clear();
        }
    }
}
