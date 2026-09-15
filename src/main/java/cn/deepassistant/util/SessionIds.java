package cn.deepassistant.util;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * sessionId 校验与生成。规则与 {@link UserIds} 相同：只允许字母数字开头，后跟字母数字、下划线、短横，
 * 最长 128，禁止 {@code ..} / {@code /} / {@code \\} / {@code \0}。
 *
 * <p>id 会拼进 Redis key（{@code as:web:}、{@code as:state:}），必须挡住路径穿越和分隔符注入。
 */
public final class SessionIds {

    private static final Pattern SAFE =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$");

    private SessionIds() {
    }

    /** 新会话 id：标准 UUID 字符串，符合 {@link #SAFE}。 */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 校验已有 sessionId。空白或格式非法抛 {@link IllegalArgumentException}，
     * 由 Controller 转成 SSE error 或 HTTP 400。
     */
    public static String requireValid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 为空");
        }
        String id = sessionId.trim();
        if (id.contains("..") || id.contains("/") || id.contains("\\") || id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法 sessionId");
        }
        if (!SAFE.matcher(id).matches()) {
            throw new IllegalArgumentException("非法 sessionId 格式");
        }
        return id;
    }

    /**
     * 聊天入口专用：没带 id 就现场生成；带了就校验。
     * 续跑 / 打开历史必须走 {@link #requireValid}，不能悄悄新建一条对不上的会话。
     *
     * <p><b>何时调用：</b>{@code POST /api/assistant/chat}、{@code SessionStore.getOrCreate}。
     * AgentScope 不生成网页 sessionId，只用我们放进 {@code RuntimeContext.sessionId} 的值。
     */
    public static String normalizeOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return newId();
        }
        return requireValid(sessionId);
    }
}
