package cn.deepassistant.memory;

import cn.deepassistant.model.ChatMessageRecord;
import cn.deepassistant.model.HistorySearchHit;
import cn.deepassistant.model.SessionDetail;
import cn.deepassistant.model.SessionSummary;
import cn.deepassistant.model.UsageStats;
import cn.deepassistant.util.SessionIds;
import cn.deepassistant.util.UserIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 网页会话库：聊天全文按用户隔离，存 Redis，多副本共享。
 *
 * <pre>
 *   as:web:{userId}:{sessionId}   → SessionDetail JSON
 *   as:web-index:{userId}         → List&lt;SessionSummary&gt; JSON
 * </pre>
 *
 * <p>这和官方 {@code session_search} 搜的 {@code sessions/*.log.jsonl} 不是同一份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStore {

    private static final String SESSION_PREFIX = "as:web:";
    private static final String INDEX_PREFIX = "as:web-index:";

    private final ObjectMapper mapper;
    private final RedisCommands<String, String> redis;

    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> indexLocks = new ConcurrentHashMap<>();

    /** 进程内互斥用的复合键，不是 Redis key。 */
    private static String key(String userId, String sessionId) {
        return userId + "/" + sessionId;
    }

    /** 网页会话全文：{@code as:web:{userId}:{sessionId}}。 */
    private static String sessionKey(String userId, String sessionId) {
        return SESSION_PREFIX + userId + ":" + sessionId;
    }

    /** 该用户侧栏列表：{@code as:web-index:{userId}}。 */
    private static String indexKey(String userId) {
        return INDEX_PREFIX + userId;
    }

    /**
     * 同一 (user, session) 的读写串行化。多副本之间仍靠 Redis，这把锁只防本 JVM 并发写同一条。
     */
    private Object lockFor(String userId, String sessionId) {
        return fileLocks.computeIfAbsent(key(userId, sessionId), k -> new Object());
    }

    /** 同一用户的索引读写串行化，避免 list/upsert 互相覆盖。 */
    private Object indexLockFor(String userId) {
        return indexLocks.computeIfAbsent(userId, k -> new Object());
    }

    /**
     * 侧栏列表：读索引后按 {@code updatedAt} 倒序。索引坏了会得到空列表而不是抛错。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions}；{@link #search}/{@link #usageStats} 内部也会列一遍。
     * AgentScope 不调这个类。
     */
    public List<SessionSummary> listSessions(String userId) {
        String uid = UserIds.normalize(userId);
        synchronized (indexLockFor(uid)) {
            return readIndex(uid).stream()
                    .sorted(Comparator.comparing(SessionSummary::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 按 id 取会话，没有就新建。{@code sessionId} 空则生成 UUID。
     * 标题取首条用户消息前 24 字；消息列表先空着，真正的 user 消息由 {@link #appendMessage} 再写。
     *
     * <p><b>何时调用：</b>{@code POST /api/sessions} 新对话；{@code POST /api/assistant/chat} 确保会话存在。
     */
    public SessionDetail getOrCreate(String userId, String sessionId, String firstUserMessage) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.normalizeOrCreate(sessionId);
        synchronized (lockFor(uid, id)) {
            SessionDetail detail = load(uid, id);
            if (detail == null) {
                Instant now = Instant.now();
                String title = buildTitle(firstUserMessage);
                detail = SessionDetail.builder()
                        .id(id)
                        .title(title)
                        .createdAt(now)
                        .updatedAt(now)
                        .messages(new ArrayList<>())
                        .build();
                persist(uid, detail);
                upsertIndex(uid, SessionSummary.builder()
                        .id(id)
                        .title(title)
                        .preview(previewOf(firstUserMessage))
                        .updatedAt(now)
                        .messageCount(0)
                        .build());
            }
            return detail;
        }
    }

    /** 只读一条会话全文。不存在返回 null，由 Controller 转成「会话不存在」。
     *
     * <p><b>何时调用：</b>{@code GET /api/sessions/{id}}；检索/统计内部按摘要再 load。
     */
    public SessionDetail get(String userId, String sessionId) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        synchronized (lockFor(uid, id)) {
            return load(uid, id);
        }
    }

    /**
     * 追加一条消息并刷新索引预览。
     * 该会话的第一条 user 消息会改写标题（侧栏「新对话」点进去后标题跟着首句走）。
     *
     * <p><b>何时调用：</b>{@code AssistantChatService} 在用户消息入库、助手回复入库、审批等待说明入库时。
     * AgentScope 的对话上下文不走这里（那份在 AgentStateStore）。
     */
    public void appendMessage(String userId, String sessionId, ChatMessageRecord record) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        synchronized (lockFor(uid, id)) {
            SessionDetail detail = load(uid, id);
            if (detail == null) {
                detail = getOrCreate(uid, id, record.getContent());
            }
            detail.getMessages().add(record);
            detail.setUpdatedAt(Instant.now());
            // 仅当这是整段历史里的第一条 user 消息时改标题，避免后续提问把标题冲掉
            if ("user".equals(record.getRole())
                    && detail.getMessages().stream().filter(m -> "user".equals(m.getRole())).count() == 1) {
                detail.setTitle(buildTitle(record.getContent()));
            }
            persist(uid, detail);
            upsertIndex(uid, SessionSummary.builder()
                    .id(detail.getId())
                    .title(detail.getTitle())
                    .preview(previewOf(record.getContent()))
                    .updatedAt(detail.getUpdatedAt())
                    .messageCount(detail.getMessages().size())
                    .build());
        }
    }

    /**
     * 在该用户全部网页会话里做大小写不敏感的子串检索。
     * 空白 query 直接空列表；limit 夹在 1～50。命中按匹配次数、再按时间倒序。
     *
     * <p><b>何时调用：</b>{@code GET /api/history/search}；模型工具 {@code search_conversation_history}。
     */
    public List<HistorySearchHit> search(String userId, String query, int limit) {
        String uid = UserIds.normalize(userId);
        String needle = query == null ? "" : query.trim();
        if (needle.isBlank()) {
            return List.of();
        }
        String lower = needle.toLowerCase();
        int cap = Math.min(Math.max(limit, 1), 50);
        List<HistorySearchHit> hits = new ArrayList<>();
        for (SessionSummary summary : listSessions(uid)) {
            SessionDetail detail = get(uid, summary.getId());
            if (detail == null || detail.getMessages() == null) {
                continue;
            }
            for (ChatMessageRecord message : detail.getMessages()) {
                String content = message.getContent();
                if (content == null) {
                    continue;
                }
                int matches = countMatches(content.toLowerCase(), lower);
                if (matches == 0) {
                    continue;
                }
                hits.add(HistorySearchHit.builder()
                        .sessionId(detail.getId())
                        .sessionTitle(detail.getTitle())
                        .role(message.getRole())
                        .timestamp(message.getTimestamp())
                        .snippet(snippetAround(content, needle))
                        .matchCount(matches)
                        .build());
            }
        }
        hits.sort(Comparator
                .comparingInt(HistorySearchHit::getMatchCount).reversed()
                .thenComparing(HistorySearchHit::getTimestamp, Comparator.nullsLast(Comparator.reverseOrder())));
        if (hits.size() > cap) {
            return new ArrayList<>(hits.subList(0, cap));
        }
        return hits;
    }

    /**
     * 档案页用量：扫该用户全部会话现算，不调模型。
     * {@code recentSessions} 取列表前 8 条（列表本身已按更新时间倒序）。
     *
     * <p><b>何时调用：</b>{@code GET /api/memory}；工具 {@code get_user_usage}。
     */
    public UsageStats usageStats(String userId) {
        String uid = UserIds.normalize(userId);
        List<SessionSummary> sessions = listSessions(uid);
        int messages = 0;
        int userMessages = 0;
        Instant first = null;
        Instant last = null;
        for (SessionSummary summary : sessions) {
            SessionDetail detail = get(uid, summary.getId());
            if (detail == null) {
                continue;
            }
            if (detail.getCreatedAt() != null && (first == null || detail.getCreatedAt().isBefore(first))) {
                first = detail.getCreatedAt();
            }
            if (detail.getUpdatedAt() != null && (last == null || detail.getUpdatedAt().isAfter(last))) {
                last = detail.getUpdatedAt();
            }
            if (detail.getMessages() == null) {
                continue;
            }
            messages += detail.getMessages().size();
            for (ChatMessageRecord message : detail.getMessages()) {
                if ("user".equals(message.getRole())) {
                    userMessages++;
                }
            }
        }
        List<SessionSummary> recent = sessions.stream().limit(8).collect(Collectors.toList());
        return UsageStats.builder()
                .sessionCount(sessions.size())
                .messageCount(messages)
                .userMessageCount(userMessages)
                .firstSeenAt(first)
                .lastSeenAt(last)
                .recentSessions(recent)
                .build();
    }

    /**
     * 删会话 JSON 并从索引摘掉。返回值表示索引里是否真有这条（重复删返回 false）。
     *
     * <p><b>何时调用：</b>{@code DELETE /api/sessions/{id}} → {@code AssistantChatService.clearSessionMemory}。
     */
    public boolean delete(String userId, String sessionId) {
        String uid = UserIds.normalize(userId);
        String id = SessionIds.requireValid(sessionId);
        synchronized (lockFor(uid, id)) {
            redis.del(sessionKey(uid, id));
            boolean removed;
            synchronized (indexLockFor(uid)) {
                List<SessionSummary> index = readIndex(uid);
                removed = index.removeIf(s -> Objects.equals(s.getId(), id));
                writeIndex(uid, index);
            }
            fileLocks.remove(key(uid, id));
            return removed;
        }
    }

    /** Redis GET + 反序列化。key 不存在或 JSON 坏了都当没有这条会话。 */
    private SessionDetail load(String userId, String sessionId) {
        String json = redis.get(sessionKey(userId, sessionId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, SessionDetail.class);
        } catch (Exception e) {
            log.warn("读取会话失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 整份 SessionDetail 覆盖写回 Redis。序列化失败只打 warn，避免一次坏消息拖死整轮对话。 */
    private void persist(String userId, SessionDetail detail) {
        try {
            redis.set(sessionKey(userId, detail.getId()), mapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("写入会话失败: {}", e.getMessage());
        }
    }

    /** 读侧栏索引；缺失或坏 JSON 当空列表，下次 upsert 会重建。 */
    private List<SessionSummary> readIndex(String userId) {
        String json = redis.get(indexKey(userId));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 覆盖写侧栏索引 JSON。失败只打 warn，会话全文已经 persist 过，索引可下次 upsert 修复。 */
    private void writeIndex(String userId, List<SessionSummary> data) {
        try {
            redis.set(indexKey(userId), mapper.writeValueAsString(data));
        } catch (Exception e) {
            log.warn("写入会话索引失败: {}", e.getMessage());
        }
    }

    /**
     * 按 id 替换或追加一条摘要。先 removeIf 再 add，保证同一会话在索引里只有一行。
     */
    private void upsertIndex(String userId, SessionSummary summary) {
        synchronized (indexLockFor(userId)) {
            List<SessionSummary> data = readIndex(userId);
            data.removeIf(s -> Objects.equals(s.getId(), summary.getId()));
            data.add(summary);
            writeIndex(userId, data);
        }
    }

    /** 侧栏标题：空白 →「新对话」；否则取首行前 24 字。 */
    private static String buildTitle(String message) {
        if (message == null || message.isBlank()) {
            return "新对话";
        }
        String t = message.replace('\n', ' ').trim();
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    /** 索引预览：压成一行，最多 60 字。 */
    private static String previewOf(String content) {
        if (content == null) {
            return "";
        }
        String t = content.replace('\n', ' ').trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    /**
     * 在已转小写的 haystack 里数 needle 出现次数（不重叠）。
     * 包可见方便单测，不走 Redis。
     */
    static int countMatches(String haystackLower, String needleLower) {
        if (haystackLower == null || needleLower == null || needleLower.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystackLower.indexOf(needleLower, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needleLower.length();
        }
    }

    /**
     * 命中处前后各留一点上下文，做成侧栏能扫的摘录。找不到 needle 则退回全文前 120 字。
     */
    static String snippetAround(String content, String needle) {
        String flat = content.replace('\n', ' ').trim();
        int at = flat.toLowerCase().indexOf(needle.toLowerCase());
        if (at < 0) {
            return flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
        }
        int start = Math.max(0, at - 40);
        int end = Math.min(flat.length(), at + needle.length() + 80);
        String snippet = flat.substring(start, end);
        if (start > 0) {
            snippet = "…" + snippet;
        }
        if (end < flat.length()) {
            snippet = snippet + "…";
        }
        return snippet;
    }
}
