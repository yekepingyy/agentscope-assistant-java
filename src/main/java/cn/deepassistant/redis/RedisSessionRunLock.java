package cn.deepassistant.redis;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 同一 {@code (userId, sessionId)} 同时只允许一轮 {@code streamEvents}。
 * key = {@code as:run:{userId}:{sessionId}}，SET NX + TTL，跨副本有效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSessionRunLock {

    private static final String PREFIX = "as:run:";
    /** 异常退出后最多锁 30 分钟，避免永久卡死。 */
    static final long TTL_SECONDS = 1800;

    private final RedisCommands<String, String> redis;

    private static String key(String userId, String sessionId) {
        return PREFIX + userId + ":" + sessionId;
    }

    /**
     * 抢锁。成功返回 token，失败返回 null（会话已有一轮在跑）。
     */
    public String tryAcquire(String userId, String sessionId) {
        String token = UUID.randomUUID().toString();
        String ok = redis.set(key(userId, sessionId), token, SetArgs.Builder.nx().ex(TTL_SECONDS));
        if (ok != null && "OK".equalsIgnoreCase(ok)) {
            return token;
        }
        return null;
    }

    /** 只释放自己持有的锁，避免误删下一轮的 token。 */
    public void release(String userId, String sessionId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String k = key(userId, sessionId);
        String current = redis.get(k);
        if (token.equals(current)) {
            redis.del(k);
        }
    }

    public boolean isHeld(String userId, String sessionId) {
        String v = redis.get(key(userId, sessionId));
        return v != null && !v.isBlank();
    }
}
