package cn.deepassistant.memory;

import cn.deepassistant.model.DailyMemoryFile;
import cn.deepassistant.model.MemoryFact;
import cn.deepassistant.util.UserIds;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 只读目录：把官方 Harness 已经写到 Redis 工作区的记忆文件读出来给前端 / 工具用。
 *
 * <h3>多用户隔离</h3>
 * HarnessAgent 配了 {@code IsolationScope.USER}，框架的 {@link WorkspaceManager} 会按
 * {@code RuntimeContext.userId} 把 {@code memory/} 和 {@code MEMORY.md} 路由到
 * 该用户的命名空间下。本类不自己拼路径，而是调
 * {@link WorkspaceManager#readMemoryMd(RuntimeContext)} /
 * {@link WorkspaceManager#listMemoryFilePaths(RuntimeContext)} /
 * {@link WorkspaceManager#readManagedWorkspaceFileUtf8(RuntimeContext, String)}，
 * 保证和框架写入的路径完全一致（RemoteFilesystem / Redis）。
 *
 * <h3>为什么用 {@link ObjectProvider} 而不是直接注入 {@link HarnessAgent}</h3>
 * 存在循环依赖：{@code harnessAgent (bean) → historyMemoryTools → harnessMemoryCatalog → harnessAgent}。
 * 直接构造注入会在 Spring 启动时死锁。{@code ObjectProvider<HarnessAgent>} 注入的是「延迟解析器」，
 * 真正调 {@code getObject()} 时（HTTP 请求进来时）harnessAgent 早已建好，环就断了。
 * 不能用 {@code @Lazy}，因为 {@link HarnessAgent} 没有可见构造器，CGLIB 代理不了。
 *
 * <p>写文件的是框架，不是这个类：
 * <ul>
 *   <li>{@code MemoryFlushManager} → {@code memory/YYYY-MM-DD.md}</li>
 *   <li>{@code MemoryConsolidator} → {@code MEMORY.md}</li>
 * </ul>
 */
@Slf4j
@Component
public class HarnessMemoryCatalog {

    private final ObjectProvider<HarnessAgent> harnessAgentProvider;
    private volatile WorkspaceManager workspaceManager;

    public HarnessMemoryCatalog(ObjectProvider<HarnessAgent> harnessAgentProvider) {
        this.harnessAgentProvider = harnessAgentProvider;
    }

    /** 首次调用时从 harnessAgent 拿 WorkspaceManager，之后缓存。
     *
     * <p><b>何时调用：</b>本类第一次读记忆文件时（档案页或 {@code get_user_usage} 工具）。
     * 不能在构造期调，否则和 HarnessAgent Bean 循环依赖。
     */
    private WorkspaceManager workspaceManager() {
        WorkspaceManager wm = workspaceManager;
        if (wm == null) {
            synchronized (this) {
                wm = workspaceManager;
                if (wm == null) {
                    wm = harnessAgentProvider.getObject().getWorkspaceManager();
                    workspaceManager = wm;
                    log.info("[HarnessMemory] 拿到官方 WorkspaceManager workspace={}", wm.getWorkspace());
                }
            }
        }
        return wm;
    }

    /**
     * 读指定用户的 MEMORY.md。
     *
     * <p><b>何时调用：</b>{@code GET /api/memory}、工具 {@code get_user_usage}。
     * 内部是官方 {@code WorkspaceManager.readMemoryMd}，会再进 {@code RemoteFilesystem} → RedisBaseStore.get。
     * 写 MEMORY.md 的是框架 Consolidator，不是这个方法。
     */
    public String readMemoryMarkdown(String userId) {
        String uid = UserIds.normalize(userId);
        return workspaceManager().readMemoryMd(runtimeContext(uid));
    }

    /**
     * 列指定用户的日流水文件。用官方 WorkspaceManager 列路径，再逐个读内容。
     *
     * <p><b>何时调用：</b>{@code GET /api/memory}、工具 {@code get_user_usage}。
     * {@code listMemoryFilePaths} 在框架里会 {@code RemoteFilesystem} search。
     */
    public List<DailyMemoryFile> listDailyLedgers(String userId) {
        String uid = UserIds.normalize(userId);
        RuntimeContext ctx = runtimeContext(uid);
        List<String> relPaths = workspaceManager().listMemoryFilePaths(ctx);
        List<DailyMemoryFile> files = new ArrayList<>();
        for (String relPath : relPaths) {
            // listMemoryFilePaths 会把 MEMORY.md 也列进来，只要 memory/ 下的日流水
            if (relPath == null || relPath.isBlank()) {
                continue;
            }
            String p = relPath.startsWith("/") ? relPath.substring(1) : relPath;
            if (!p.startsWith("memory/") || !p.endsWith(".md")) {
                continue;
            }
            String name = p.substring("memory/".length());
            if (name.startsWith(".")) {
                continue;
            }
            String content = workspaceManager().readManagedWorkspaceFileUtf8(ctx, p);
            files.add(DailyMemoryFile.builder()
                    .path("memory/" + name)
                    .lastModified(Instant.now())
                    .content(content == null ? "" : content)
                    .build());
        }
        files.sort(Comparator.comparing(DailyMemoryFile::getPath, Comparator.reverseOrder()));
        return files;
    }

    /**
     * 把指定用户 MEMORY.md 里的 {@code - 条目} 拆成列表，给档案页用。
     * 分类只能从当前小节标题猜，猜不到就叫 {@code fact}。
     *
     * <p><b>何时调用：</b>仅 {@code GET /api/memory}。AgentScope 不调。
     */
    public List<MemoryFact> parseFactsFromMemoryMd(String userId) {
        return parseFacts(readMemoryMarkdown(userId));
    }

    /** 纯函数，方便单测：不碰磁盘。
     *
     * <p><b>何时调用：</b>{@link #parseFactsFromMemoryMd} 以及单测。
     */
    static List<MemoryFact> parseFacts(String md) {
        List<MemoryFact> facts = new ArrayList<>();
        if (md == null || md.isBlank()) {
            return facts;
        }
        String category = "fact";
        for (String rawLine : md.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                category = guessCategory(line.substring(3));
                continue;
            }
            if (!line.startsWith("- ")) {
                continue;
            }
            String content = line.substring(2).trim();
            if (content.isBlank() || content.startsWith("（")) {
                continue;
            }
            facts.add(MemoryFact.builder()
                    .id(UUID.randomUUID().toString())
                    .category(category)
                    .content(content)
                    .confidence(1.0)
                    .build());
        }
        return facts;
    }

    private static String guessCategory(String heading) {
        String h = heading.toLowerCase(Locale.ROOT);
        if (h.contains("偏好") || h.contains("preference")) {
            return "preference";
        }
        if (h.contains("习惯") || h.contains("style")) {
            return "working_style";
        }
        if (h.contains("用户") || h.contains("identity") || h.contains("关于")) {
            return "identity";
        }
        if (h.contains("项目") || h.contains("project") || h.contains("技术")) {
            return "project";
        }
        if (h.contains("约束") || h.contains("constraint")) {
            return "constraint";
        }
        return "fact";
    }

    /**
     * 只带 userId 的 RuntimeContext，给 WorkspaceManager 做 IsolationScope.USER 路由。
     *
     * <p><b>何时调用：</b>本类读记忆时。不需要 sessionId：MEMORY.md 是用户级，不是会话级。
     */
    private static RuntimeContext runtimeContext(String userId) {
        return RuntimeContext.builder()
                .userId(userId)
                .build();
    }
}
