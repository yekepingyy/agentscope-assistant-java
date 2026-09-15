package cn.deepassistant.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolUseBlock;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HITL 待审批跨副本共享。key = {@code as:pending:{userId}:{sessionId}}，TTL 1 小时。
 *
 * <p>写文件工具触发 ASK 后，审批单必须进 Redis：用户点「批准」可能打到另一台副本，
 * 那台机器内存里没有当初的 {@link ToolUseBlock}。TTL 防止用户关掉页面后 key 永久残留。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPendingApprovalStore {

    private static final String PREFIX = "as:pending:";
    /** 一小时没人点批准/拒绝就作废，避免幽灵审批。 */
    private static final long TTL_SECONDS = 3600;

    private final RedisCommands<String, String> redis;
    private final ObjectMapper mapper;

    private static String key(String userId, String sessionId) {
        return PREFIX + userId + ":" + sessionId;
    }

    /**
     * 覆盖写入待审批工具列表，并刷新 TTL。序列化失败直接抛，宁可本轮失败也不能丢审批单。
     *
     * <p><b>何时调用：</b>本项目 {@code AssistantChatService.run} 收到框架
     * {@code RequireUserConfirmEvent} 之后。AgentScope 自己不写这个 Redis key。
     */
    public void put(String userId, String sessionId, List<ToolUseBlock> toolCalls) {
        try {
            redis.setex(key(userId, sessionId), TTL_SECONDS, mapper.writeValueAsString(toolCalls));
        } catch (Exception e) {
            throw new IllegalStateException("写入待审批失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读待审批列表。没有 key 或 JSON 坏了返回 null，调用方当成「当前没有待审批」。
     *
     * <p><b>何时调用：</b>{@code POST /api/assistant/resume} 组 {@code ConfirmResult} 之前。
     */
    public List<ToolUseBlock> get(String userId, String sessionId) {
        String json = redis.get(key(userId, sessionId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("读取待审批失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** Redis EXISTS：前端打开历史会话时用来决定要不要显示批准按钮。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions/{id}}、resume 入口校验。
     */
    public boolean exists(String userId, String sessionId) {
        Long n = redis.exists(key(userId, sessionId));
        return n != null && n > 0;
    }

    /** 对话正常结束、拒绝跑完、或删除会话时清掉审批单。
     *
     * <p><b>何时调用：</b>{@code AssistantChatService.run} 正常收尾；{@code clearSessionMemory}。
     */
    public void remove(String userId, String sessionId) {
        redis.del(key(userId, sessionId));
    }
}
