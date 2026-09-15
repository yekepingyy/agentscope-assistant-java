package cn.deepassistant.redis;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Redis 版 {@link AgentStateStore}：把 AgentState 存进 Redis，多副本共享。
 *
 * <h3>Key 设计</h3>
 * <pre>
 *   单条状态：  as:state:{userId}:{sessionId}:{slotName}   →  JSON 字符串
 *   列表状态：  as:list:{userId}:{sessionId}:{slotName}    →  Redis LIST（每行一个 JSON）
 * </pre>
 * userId / sessionId 只允许 {@code [a-zA-Z0-9_-]}（见 UserIds / SessionIds），
 * 所以用 {@code :} 当分隔符不会撞。
 *
 * <p>序列化用 {@link JsonUtils#getJsonCodec()}，和官方默认的 AgentState JSON 格式一致。
 *
 * <p>列表状态用 Redis LIST（RPUSH 一行一个 JSON）。{@link #save} 列表时做全量重写（DEL + RPUSH）。
 *
 * <h3>谁在调（几乎全是 AgentScope 框架，不是 HTTP）</h3>
 * 本类通过 {@code DistributedStore.agentStateStore} 交给 {@code HarnessAgent}。
 * 用户点「发送」后，本项目只调 {@code harnessAgent.streamEvents(...)}；下面这些方法都是
 * {@code ReActAgent} / 子 Agent 在那次流式推理里自己回调的：
 * <ul>
 *   <li>开场 {@code loadOrCreateAgentStateForSlot} → {@link #get}，槽位 {@code agent_state}</li>
 *   <li>回合中/结束后 {@code saveAgentState} → {@link #save}，把 messages、权限、计划写回</li>
 *   <li>子 Agent（{@code agent_spawn}）也会为自己的 slot 再 load/save 一次</li>
 *   <li>{@code HarnessAgent.clearContext} → {@link #delete(String, String)}</li>
 * </ul>
 * 本项目自己会调的只有删会话：{@code AssistantChatService.deleteAgentState}。
 */
@Slf4j
public class RedisAgentStateStore implements AgentStateStore {

    private static final String STATE_PREFIX = "as:state:";
    private static final String LIST_PREFIX = "as:list:";

    private final RedisCommands<String, String> redis;

    public RedisAgentStateStore(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    /** 单条槽位 key：{@code as:state:{userId}:{sessionId}:{slotName}}。 */
    private static String stateKey(String userId, String sessionId, String slotName) {
        return STATE_PREFIX + userId + ":" + sessionId + ":" + slotName;
    }

    /** 列表槽位 key：{@code as:list:{userId}:{sessionId}:{slotName}}。 */
    private static String listKey(String userId, String sessionId, String slotName) {
        return LIST_PREFIX + userId + ":" + sessionId + ":" + slotName;
    }

    /**
     * 单条状态：序列化成 JSON 存一个 String key。
     *
     * <p><b>何时调用（框架）：</b>{@code ReActAgent.saveAgentState}。每次
     * {@code streamEvents} 推理推进时（工具跑完、即将中断审批、正常结束）都会把当前
     * {@code AgentState} 写回，槽位名几乎总是 {@code agent_state}。多副本下一轮
     * {@link #get} 才能续上同一段对话。{@code SubAgentTool} 给子 Agent 存状态时也会进这里。
     *
     * <p><b>本项目：</b>不直接调。
     */
    @Override
    public void save(String userId, String sessionId, String slotName, State state) {
        String json = JsonUtils.getJsonCodec().toPrettyJson(state);
        redis.set(stateKey(userId, sessionId, slotName), json);
    }

    /**
     * 列表状态：全量重写（DEL + RPUSH 每行一个 JSON）。
     *
     * <p><b>何时调用（框架）：</b>{@code AgentStateStore} 的列表槽 API。当前 2.0 主路径
     * 对话上下文是单条 {@code AgentState}，这条给历史/扩展槽（例如一批 Task 记录）预留。
     * 接口必须实现，否则 Harness 绑 store 时缺方法。
     *
     * <p><b>本项目：</b>不直接调。
     */
    @Override
    public void save(String userId, String sessionId, String slotName,
                     List<? extends State> states) {
        String key = listKey(userId, sessionId, slotName);
        redis.del(key);
        if (states == null || states.isEmpty()) {
            return;
        }
        String[] jsons = new String[states.size()];
        for (int i = 0; i < states.size(); i++) {
            jsons[i] = JsonUtils.getJsonCodec().toPrettyJson(states.get(i));
        }
        redis.rpush(key, jsons);
    }

    /**
     * 读单条状态。JSON 坏了当不存在，避免一次损坏槽位让整轮对话起不来。
     *
     * <p><b>何时调用（框架）：</b>{@code ReActAgent.loadOrCreateAgentStateForSlot}，
     * 就在 {@code streamEvents}/{@code call} 开头。同一 session 第二次聊天、HITL 续跑、
     * 请求打到另一台副本时，都靠这一次 get 把上次的 messages / 权限 / 计划拉回来。
     * 没有 key 时框架会 new 一个空 {@code AgentState}。
     *
     * <p><b>本项目：</b>不直接调。
     */
    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String slotName, Class<T> type) {
        String json = redis.get(stateKey(userId, sessionId, slotName));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
        } catch (Exception e) {
            log.warn("[RedisState] 反序列化失败 session={}/{}: {}", sessionId, slotName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 读列表状态。单行 JSON 坏了跳过该行，尽量把其余项救回来。
     *
     * <p><b>何时调用（框架）：</b>与列表版 {@link #save} 成对，读某个 list 槽。
     * 主聊天路径走单条 {@link #get}，正常一轮对话里通常不会进这里。
     *
     * <p><b>本项目：</b>不直接调。
     */
    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String slotName, Class<T> type) {
        List<String> jsons = redis.lrange(listKey(userId, sessionId, slotName), 0, -1);
        List<T> result = new ArrayList<>();
        for (String json : jsons) {
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                result.add(JsonUtils.getJsonCodec().fromJson(json, type));
            } catch (Exception e) {
                log.warn("[RedisState] 列表项反序列化失败 session={}/{}: {}", sessionId, slotName, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 该 (userId, sessionId) 是否存过任何状态。
     *
     * <p><b>何时调用（框架）：</b>{@code AgentStateStore} 接口方法，给「会话是否已有 state」探测用。
     * 本项目打开历史会话看的是网页 JSON + pending key，不走这个方法。
     *
     * <p><b>本项目：</b>不直接调。
     */
    @Override
    public boolean exists(String userId, String sessionId) {
        String stateMatch = STATE_PREFIX + userId + ":" + sessionId + ":*";
        String listMatch = LIST_PREFIX + userId + ":" + sessionId + ":*";
        return !scanKeys(stateMatch).isEmpty() || !scanKeys(listMatch).isEmpty();
    }

    /**
     * 删整个会话：扫两种前缀全删。
     *
     * <p><b>何时调用（框架）：</b>{@code HarnessAgent.clearContext(userId, sessionId)} /
     * {@code ReActAgent.clearContext}，清空该会话推理上下文时。
     *
     * <p><b>本项目：</b>{@code DELETE /api/sessions/{id}} →
     * {@code AssistantChatService.clearSessionMemory} → {@code agentStateStore.delete}。
     * 网页侧栏删会话时必须调，否则下次用同一 sessionId 还会读到旧 AgentState。
     */
    @Override
    public void delete(String userId, String sessionId) {
        String stateMatch = STATE_PREFIX + userId + ":" + sessionId + ":*";
        String listMatch = LIST_PREFIX + userId + ":" + sessionId + ":*";
        delAll(scanKeys(stateMatch));
        delAll(scanKeys(listMatch));
    }

    /**
     * 删单个槽位：删单条 key + 列表 key。
     *
     * <p><b>何时调用（框架）：</b>{@code AgentStateStore.delete(user, session, slot)} 默认方法的实现点，
     * 只清某一个 slot（例如只要 {@code agent_state}）而不动其它 key。
     *
     * <p><b>本项目：</b>不直接调；删会话走三参数版的上一方法。
     */
    @Override
    public void delete(String userId, String sessionId, String slotName) {
        redis.del(stateKey(userId, sessionId, slotName));
        redis.del(listKey(userId, sessionId, slotName));
    }

    /**
     * 列出某用户的所有 sessionId：扫 as:state:{userId}:* 和 as:list:{userId}:* 取第三段。
     *
     * <p><b>何时调用（框架）：</b>接口要求实现，给「该用户在 AgentState 里有哪些会话」枚举用。
     * 官方 {@code session_search} 搜的是工作区 {@code sessions/*.log.jsonl}（走 {@link RedisBaseStore}），
     * 不是这个方法。本项目侧栏列表走 {@code SessionStore.listSessions}，正常请求不会进这里。
     *
     * <p><b>本项目：</b>不直接调。
     */
    @Override
    public Set<String> listSessionIds(String userId) {
        Set<String> ids = new TreeSet<>();
        String stateMatch = STATE_PREFIX + userId + ":*";
        String listMatch = LIST_PREFIX + userId + ":*";
        for (String key : scanKeys(stateMatch)) {
            ids.add(extractSessionId(key, STATE_PREFIX));
        }
        for (String key : scanKeys(listMatch)) {
            ids.add(extractSessionId(key, LIST_PREFIX));
        }
        return ids;
    }

    /** as:state:{userId}:{sessionId}:{slotName} → 取 sessionId 段。 */
    private static String extractSessionId(String key, String prefix) {
        String rest = key.substring(prefix.length());
        int first = rest.indexOf(':');
        int second = rest.indexOf(':', first + 1);
        return second < 0 ? rest.substring(first + 1) : rest.substring(first + 1, second);
    }

    /**
     * SCAN 匹配 pattern，游标翻完为止。不用 KEYS，避免生产 Redis 被一次扫挂。
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        do {
            var scan = redis.scan(cursor, ScanArgs.Builder.matches(pattern).limit(200));
            keys.addAll(scan.getKeys());
            cursor = scan;
        } while (!cursor.isFinished());
        return keys;
    }

    /** 批量 DEL；空集合直接返回，避免 Redis 收到空 DEL。 */
    private void delAll(Set<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        redis.del(keys.toArray(new String[0]));
    }
}
