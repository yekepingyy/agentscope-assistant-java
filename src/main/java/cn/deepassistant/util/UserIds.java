package cn.deepassistant.util;

import java.util.regex.Pattern;

/**
 * userId 校验，和 {@link SessionIds} 用同一套安全规则：
 * 只允许 {@code [a-zA-Z0-9][a-zA-Z0-9_-]{0,127}}，禁止路径穿越。
 *
 * <p>多用户隔离靠 (userId, sessionId) 二元组寻址，userId 会直接变成磁盘目录名，
 * 所以必须挡住 {@code ..} / {@code /} / {@code \\} / {@code \0}。
 */
public final class UserIds {

    private static final Pattern SAFE =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$");

    /** 请求没带 userId 时的默认值。 */
    public static final String DEFAULT_USER_ID = "local";

    private UserIds() {
    }

    /**
     * 校验 userId，空白时回退到 {@link #DEFAULT_USER_ID}。
     * 这样前端不传 userId 也能跑，等价于原来的单用户模式。
     *
     * <p><b>何时调用：</b>几乎所有 HTTP 入口和工具里取当前用户时。AgentScope 用的是
     * {@code RuntimeContext.userId}，不会调这个方法；我们在进框架前先 normalize 再放进 Context。
     */
    public static String normalize(String userId) {
        if (userId == null || userId.isBlank()) {
            return DEFAULT_USER_ID;
        }
        return requireValid(userId);
    }

    /**
     * 强制校验：空白也报错，不回退到 {@code local}。
     * 给「必须明确知道是哪个用户」的内部调用预留；HTTP 入口一般用 {@link #normalize}。
     */
    public static String requireValid(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 为空");
        }
        String id = userId.trim();
        // 先挡路径穿越，再套白名单，避免奇怪 unicode 绕过正则
        if (id.contains("..") || id.contains("/") || id.contains("\\") || id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法 userId");
        }
        if (!SAFE.matcher(id).matches()) {
            throw new IllegalArgumentException("非法 userId 格式");
        }
        return id;
    }
}
