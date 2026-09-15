package cn.deepassistant.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolUseBlock;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HITL 待审批跨副本共享。key = {@code as:pending:{userId}:{sessionId}}，TTL 1 小时。
 *
 * <p>写文件工具触发 ASK 后，审批单必须进 Redis：用户点「批准」可能打到另一台副本，
 * 那台机器内存里没有当初的 {@link ToolUseBlock}。TTL 防止用户关掉页面后 key 永久残留。
 *
 * <p>磁盘格式是 {@code {replyId, tools}}：{@code tools} 是 {@link PendingToolCall} 列表，
 * 不是框架的 {@link ToolUseBlock} 本体。旧版纯数组 JSON 仍能读。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPendingApprovalStore {

    private static final String PREFIX = "as:pending:";
    /** 一小时没人点批准/拒绝就作废，避免幽灵审批。 */
    private static final long TTL_SECONDS = 86400;

    private final RedisCommands<String, String> redis;
    private final ObjectMapper mapper;

    private static String key(String userId, String sessionId) {
        return PREFIX + userId + ":" + sessionId;
    }

    /**
     * 覆盖写入待审批工具列表，并刷新 TTL。序列化失败直接抛，宁可本轮失败也不能丢审批单。
     *
     * <p><b>何时调用：</b>本项目 {@code AssistantChatService} 一收到框架
     * {@code RequireUserConfirmEvent} 就写（推给前端 interrupt 之前）。AgentScope 自己不写这个 Redis key。
     */
    public void put(String userId, String sessionId, List<ToolUseBlock> toolCalls) {
        put(userId, sessionId, "", toolCalls);
    }

    public void put(String userId, String sessionId, String replyId, List<ToolUseBlock> toolCalls) {
        List<PendingToolCall> snapshot = encode(toolCalls);
        if (snapshot.isEmpty()) {
            throw new IllegalStateException("待审批工具列表为空，拒绝写入: " + userId + "/" + sessionId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("replyId", replyId == null ? "" : replyId);
        body.put("tools", snapshot);
        try {
            redis.setex(key(userId, sessionId), TTL_SECONDS, mapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException("写入待审批失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读待审批列表并还原成 {@link ToolUseBlock}。没有 key、JSON 坏了或列表空，返回 null。
     *
     * <p><b>何时调用：</b>{@code POST /api/assistant/resume} 组 {@code ConfirmResult} 之前；
     * {@code hasPendingApproval} 也走这里，避免 EXISTS 为真但反序列化失败。
     */
    public List<ToolUseBlock> get(String userId, String sessionId) {
        Snapshot snapshot = getSnapshot(userId, sessionId);
        return snapshot == null ? null : snapshot.toolCalls();
    }

    /**
     * 带 {@code replyId} 的完整审批单。续跑时要把 replyId 写进 Msg metadata，
     * 和上次 {@code RequireUserConfirmEvent} 对上。
     */
    public Snapshot getSnapshot(String userId, String sessionId) {
        String json = redis.get(key(userId, sessionId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Snapshot snapshot = decodeSnapshot(mapper, json);
            if (snapshot == null || snapshot.toolCalls() == null || snapshot.toolCalls().isEmpty()) {
                return null;
            }
            return snapshot;
        } catch (Exception e) {
            log.warn("读取待审批失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 有没有可读的审批单。和 {@link #get} 同一套解码，不要只用 Redis EXISTS。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions/{id}}、resume 入口校验。
     */
    public boolean exists(String userId, String sessionId) {
        List<ToolUseBlock> calls = get(userId, sessionId);
        return calls != null && !calls.isEmpty();
    }

    /** 对话正常结束、拒绝跑完、或删除会话时清掉审批单。
     *
     * <p><b>何时调用：</b>{@code AssistantChatService} 正常收尾；{@code clearSessionMemory}。
     */

    /**
     * 原子领取审批单（Lua GET+DEL），防止双击/双副本重复 resume。
     * 没有可读审批单时返回 null。
     */
    public Snapshot claim(String userId, String sessionId) {
        String json = redis.eval(
                "local v = redis.call('GET', KEYS[1]); if v then redis.call('DEL', KEYS[1]); end; return v",
                ScriptOutputType.VALUE,
                key(userId, sessionId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Snapshot snapshot = decodeSnapshot(mapper, json);
            if (snapshot == null || snapshot.toolCalls() == null || snapshot.toolCalls().isEmpty()) {
                return null;
            }
            return snapshot;
        } catch (Exception e) {
            log.warn("claim 待审批失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    public void remove(String userId, String sessionId) {
        redis.del(key(userId, sessionId));
    }

    static List<PendingToolCall> encode(List<ToolUseBlock> toolCalls) {
        List<PendingToolCall> snapshot = new ArrayList<>();
        if (toolCalls == null) {
            return snapshot;
        }
        for (ToolUseBlock block : toolCalls) {
            PendingToolCall item = PendingToolCall.from(block);
            if (item != null && item.name() != null && !item.name().isBlank()) {
                snapshot.add(item);
            }
        }
        return snapshot;
    }

    static List<ToolUseBlock> decode(ObjectMapper mapper, String json) throws Exception {
        Snapshot snapshot = decodeSnapshot(mapper, json);
        return snapshot == null ? List.of() : snapshot.toolCalls();
    }

    static Snapshot decodeSnapshot(ObjectMapper mapper, String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (root == null || root.isNull()) {
            return new Snapshot("", List.of());
        }
        String replyId = "";
        JsonNode toolsNode = root;
        if (root.isObject()) {
            replyId = root.path("replyId").asText("");
            toolsNode = root.get("tools");
        }
        List<PendingToolCall> snapshot = toolsNode == null || toolsNode.isNull()
                ? List.of()
                : mapper.convertValue(toolsNode, new TypeReference<>() {
                });
        List<ToolUseBlock> blocks = new ArrayList<>();
        if (snapshot == null) {
            return new Snapshot(replyId, blocks);
        }
        for (PendingToolCall item : snapshot) {
            if (item == null || item.name() == null || item.name().isBlank()) {
                continue;
            }
            blocks.add(item.toBlock());
        }
        return new Snapshot(replyId, blocks);
    }

    /** Redis 里一份审批单：框架 ASK 的 replyId + 待执行工具。 */
    public record Snapshot(String replyId, List<ToolUseBlock> toolCalls) {
        public Snapshot {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            replyId = replyId == null ? "" : replyId;
        }
    }
}
