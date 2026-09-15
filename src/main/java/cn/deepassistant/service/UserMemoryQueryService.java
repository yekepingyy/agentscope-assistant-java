package cn.deepassistant.service;

import cn.deepassistant.memory.HarnessMemoryCatalog;
import cn.deepassistant.memory.SessionStore;
import cn.deepassistant.model.HistorySearchHit;
import cn.deepassistant.model.HistorySearchResponse;
import cn.deepassistant.model.UserMemoryProfile;
import cn.deepassistant.util.UserIds;
import org.springframework.stereotype.Service;

import java.util.List;

    /**
     * HTTP 门面：历史检索走 SessionStore，档案页读官方 MEMORY.md / 日流水。
     * 所有方法都按 userId 隔离，不同用户互不可见。
     *
     * <p><b>何时调用：</b>只被 {@code AssistantController} 的 GET 接口调。AgentScope 不进这个类。
     */
@Service
public class UserMemoryQueryService {

    private final SessionStore sessionStore;
    private final HarnessMemoryCatalog memoryCatalog;

    public UserMemoryQueryService(SessionStore sessionStore, HarnessMemoryCatalog memoryCatalog) {
        this.sessionStore = sessionStore;
        this.memoryCatalog = memoryCatalog;
    }

    /**
     * 网页会话库关键词检索。
     *
     * <p><b>何时调用：</b>{@code GET /api/history/search}。模型搜历史走 {@code HistoryMemoryTools}，不走这里。
     */
    public HistorySearchResponse searchHistory(String userId, String query, int limit) {
        String uid = UserIds.normalize(userId);
        String q = query == null ? "" : query.trim();
        List<HistorySearchHit> hits = sessionStore.search(uid, q, limit);
        return HistorySearchResponse.builder()
                .query(q)
                .hits(hits)
                .hitCount(hits.size())
                .build();
    }

    /**
     * 档案页一次聚合：用量 + MEMORY.md + 日流水 + 解析出的 facts。
     *
     * <p><b>何时调用：</b>{@code GET /api/memory}。读 MEMORY.md 会间接触达框架 WorkspaceManager / RedisBaseStore。
     */
    public UserMemoryProfile profile(String userId) {
        String uid = UserIds.normalize(userId);
        return UserMemoryProfile.builder()
                .usage(sessionStore.usageStats(uid))
                .memoryMarkdown(memoryCatalog.readMemoryMarkdown(uid))
                .dailyLedgers(memoryCatalog.listDailyLedgers(uid))
                .facts(memoryCatalog.parseFactsFromMemoryMd(uid))
                .build();
    }
}
