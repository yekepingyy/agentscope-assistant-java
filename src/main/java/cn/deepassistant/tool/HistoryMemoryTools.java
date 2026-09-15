package cn.deepassistant.tool;

import cn.deepassistant.memory.HarnessMemoryCatalog;
import cn.deepassistant.memory.SessionStore;
import cn.deepassistant.model.DailyMemoryFile;
import cn.deepassistant.model.HistorySearchHit;
import cn.deepassistant.model.UsageStats;
import cn.deepassistant.util.UserIds;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 给主 Agent 用的「本项目聊天记录」检索。
 *
 * <p>多用户隔离：工具方法第一个参数是 {@link RuntimeContext}，框架自动注入。
 * 从 {@code ctx.getUserId()} 取出当前用户，再传给 {@link SessionStore} / {@link HarnessMemoryCatalog}。
 *
 * <p>官方已经注册了这些工具，不要重复造：
 * <ul>
 *   <li>{@code memory_search} / {@code memory_get} / {@code memory_save} —— 搜/读/写 MEMORY.md 与日流水</li>
 *   <li>{@code session_search} —— 搜工作区 {@code sessions/*.log.jsonl}（压缩前卸下来的原文）</li>
 * </ul>
 *
 * 网页上用户看到的对话存在 Redis {@code as:web:{userId}:{sessionId}}，和 jsonl 不是同一份。
 * 这个工具专门搜那份会话 JSON，回答「我在这个聊天页里上次说过什么」。
 *
 * <p><b>何时调用（框架）：</b>{@code Toolkit.registerTool(historyMemoryTools)} 之后，
 * 模型在对话中点名 {@code search_conversation_history} / {@code get_user_usage}，
 * {@code ToolExecutor} 反射进来。第一个参数 {@link RuntimeContext} 由框架注入，不是模型填的。
 */
@Component
public class HistoryMemoryTools {

    private final SessionStore sessionStore;
    private final HarnessMemoryCatalog memoryCatalog;

    public HistoryMemoryTools(SessionStore sessionStore, HarnessMemoryCatalog memoryCatalog) {
        this.sessionStore = sessionStore;
        this.memoryCatalog = memoryCatalog;
    }

    /**
     * 搜网页会话库原文。
     *
     * <p><b>何时调用（框架）：</b>模型发出 {@code search_conversation_history}。
     * 档案页搜索走 HTTP {@code GET /api/history/search}，不走这个方法（那条走 {@code UserMemoryQueryService}）。
     */
    @Tool(name = "search_conversation_history",
            description = "在网页会话库（用户在界面里看到的那些对话）里按关键词搜原文。"
                    + "搜跨会话沉淀事实请用官方 memory_search；搜压缩前卸载日志请用 session_search。",
            readOnly = true, concurrencySafe = true)
    public String searchConversationHistory(
            RuntimeContext ctx,
            @ToolParam(name = "query", description = "关键词，尽量短")
            String query,
            @ToolParam(name = "limit", description = "最多几条，默认 8")
            Integer limit) {
        if (query == null || query.isBlank()) {
            return "请提供搜索关键词。";
        }
        String userId = UserIds.normalize(ctx.getUserId());
        List<HistorySearchHit> hits = sessionStore.search(userId, query.trim(), limit == null ? 8 : limit);
        if (hits.isEmpty()) {
            return "网页会话库里没有「" + query.trim() + "」。可以再试 memory_search 或 session_search。";
        }
        StringBuilder sb = new StringBuilder("网页会话库命中 ").append(hits.size()).append(" 条：\n");
        for (HistorySearchHit hit : hits) {
            sb.append("- [").append(hit.getSessionTitle()).append("] (")
                    .append(hit.getRole()).append(") ")
                    .append(hit.getSnippet()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 当前用户用量 + MEMORY.md 摘要。
     *
     * <p><b>何时调用（框架）：</b>模型发出 {@code get_user_usage}。
     * 档案页 {@code GET /api/memory} 不走这个方法。
     */
    @Tool(name = "get_user_usage",
            description = "查看当前用户的使用概况（会话数、消息数）以及当前 MEMORY.md / 日流水摘要。",
            readOnly = true, concurrencySafe = true)
    public String getUserUsage(RuntimeContext ctx) {
        String userId = UserIds.normalize(ctx.getUserId());
        UsageStats usage = sessionStore.usageStats(userId);
        StringBuilder sb = new StringBuilder();
        sb.append("使用概况：").append(usage.getSessionCount()).append(" 个网页会话，")
                .append(usage.getUserMessageCount()).append(" 条用户消息");
        if (usage.getLastSeenAt() != null) {
            sb.append("，最近活跃 ").append(usage.getLastSeenAt());
        }
        sb.append("。\n\n长期记忆 MEMORY.md：\n");
        String md = memoryCatalog.readMemoryMarkdown(userId);
        sb.append(md.isBlank() ? "（尚无，等首轮 Flush/Consolidation）\n" : md).append("\n");
        List<DailyMemoryFile> daily = memoryCatalog.listDailyLedgers(userId);
        if (!daily.isEmpty()) {
            sb.append("日流水文件：");
            for (DailyMemoryFile file : daily) {
                sb.append(file.getPath()).append(" ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
