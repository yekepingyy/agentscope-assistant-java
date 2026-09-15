package cn.deepassistant.redis;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis 版 {@link BaseStore}：给官方 {@code RemoteFilesystem} 当 KV 后端。
 *
 * <p>多副本时工作区文件（MEMORY.md、memory/日流水、sessions/*.log.jsonl）不再落本地磁盘，
 * 而是存进 Redis，所有副本共享同一份。
 *
 * <h3>Key 设计</h3>
 * <pre>
 *   as:base:{namespaceJoined}/{fileKey}   →  Redis Hash { value: &lt;JSON&gt;, version: &lt;long&gt; }
 * </pre>
 * namespace 是 {@link io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory#getNamespace} 的返回，
 * {@code IsolationScope.USER} 下就是 {@code ["alice"]}，join 成 {@code alice}。
 * fileKey 是工作区相对路径，如 {@code MEMORY.md}、{@code memory/2024-01-01.md}。
 * 所以最终 key 形如 {@code as:base:alice/MEMORY.md}。
 *
 * <h3>version / 乐观锁</h3>
 * 用 Hash 的 {@code version} 字段做 {@link #putIfVersion} 乐观锁，靠 Lua 脚本保证原子。
 * {@link #put} 每次写都 {@code version+1}。
 *
 * <p>value 是 {@link StoreItem#value()}（{@code Map<String, Object>}）的 JSON，
 * 官方 {@code RemoteFilesystem.fileDataToStoreValue} 负责在 FileData ↔ Map 之间转换，
 * 本类只管忠实存取 Map，不关心里面是什么。
 *
 * <h3>谁在调（几乎全是 AgentScope {@code RemoteFilesystem}）</h3>
 * Harness 配了 {@code RemoteFilesystemSpec} 后，工作区读写不再碰本地磁盘，全部变成对本接口的 KV。
 * 本项目从不直接 {@code redisBaseStore.get/put}，而是框架在这些时机回调：
 * <ul>
 *   <li>每轮推理开头 {@code WorkspaceContextMiddleware} 读 {@code MEMORY.md} / {@code AGENTS.md} 注入 system prompt</li>
 *   <li>模型调 {@code read_file}/{@code write_file}/{@code edit_file}/{@code ls}/{@code grep}/{@code glob}</li>
 *   <li>流结束后 {@code MemoryFlushMiddleware} 追加 {@code memory/YYYY-MM-DD.md}</li>
 *   <li>周期性 {@code MemoryConsolidator} 重写 {@code MEMORY.md}</li>
 *   <li>上下文压缩 {@code CompactionMiddleware} 把卸下来的原文写成 {@code sessions/*.log.jsonl}</li>
 *   <li>本项目档案页 / {@code get_user_usage} 经 {@code WorkspaceManager} 间接触达（仍是框架文件系统）</li>
 * </ul>
 */
@Slf4j
public class RedisBaseStore implements BaseStore {

    private static final String PREFIX = "as:base:";

    private final RedisCommands<String, String> redis;

    public RedisBaseStore(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    /** namespace 段用 / 连接，再拼上 fileKey，加全局前缀。 */
    private static String redisKey(List<String> namespace, String key) {
        String ns = String.join("/", namespace);
        return PREFIX + ns + "/" + key;
    }

    /** search 用的前缀：as:base:{namespace}/。 */
    private static String scanPrefix(List<String> namespace) {
        return PREFIX + String.join("/", namespace) + "/";
    }

    /**
     * 读一个工作区文件对应的 Hash。没有 key 返回 null，官方 RemoteFilesystem 会当成文件不存在。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.read}/{@code exists}，以及
     * {@code WorkspaceManager.readMemoryMd} / {@code readManagedWorkspaceFileUtf8}。
     * 典型时机：每轮 chat 开头注入 MEMORY.md；模型 {@code read_file}；Flush/Consolidation 先读再写。
     */
    @Override
    public StoreItem get(List<String> namespace, String key) {
        String rk = redisKey(namespace, key);
        Map<String, String> fields = redis.hgetall(rk);
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        String valueJson = fields.getOrDefault("value", "");
        long version = parseLong(fields.get("version"), 0L);
        Map<String, Object> value = JsonCodecHolder.fromJsonToMap(valueJson);
        return new StoreItem(key, value, version);
    }

    /**
     * 无条件写入：Lua 里 HINCRBY version + HSET value，一次 eval 保证不会出现「只加了版本没写内容」。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.write} 在「文件尚不存在」时走 put
     * （已存在则官方要求先 read 再 edit）。新建日流水、新建计划、压缩卸载 jsonl 第一次落盘都会进这里。
     */
    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        String rk = redisKey(namespace, key);
        String valueJson = JsonCodecHolder.toJson(value);
        // Lua：version 不存在则置 1，存在则 +1；然后写 value。保证原子。
        String script =
                "local v = redis.call('HINCRBY', KEYS[1], 'version', 1) " +
                "redis.call('HSET', KEYS[1], 'value', ARGV[1]) " +
                "return v";
        redis.eval(script, ScriptOutputType.INTEGER, new String[]{rk}, valueJson);
    }

    /**
     * 乐观锁写入。当前 version 必须等于 {@code expectedVersion} 才提交，否则返回 false 让框架重读再试。
     * 多副本同时 flush MEMORY.md 时靠这个避免互相覆盖。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.write}/{@code edit} 改已有文件时。
     * 官方最多重试 5 次；失败会报 {@code Another writer is concurrently modifying this file}。
     * Consolidation 重写 MEMORY.md、模型 {@code edit_file}、Flush 追加日流水都走这条。
     */
    @Override
    public boolean putIfVersion(List<String> namespace, String key,
                               Map<String, Object> value, long expectedVersion) {
        String rk = redisKey(namespace, key);
        String valueJson = JsonCodecHolder.toJson(value);
        // Lua：读当前 version，匹配 expectedVersion 才 version+1 并写 value，返回 1；否则返回 0。
        String script =
                "local cur = tonumber(redis.call('HGET', KEYS[1], 'version')) or 0 " +
                "if cur == tonumber(ARGV[1]) then " +
                "  redis.call('HINCRBY', KEYS[1], 'version', 1) " +
                "  redis.call('HSET', KEYS[1], 'value', ARGV[2]) " +
                "  return 1 " +
                "else " +
                "  return 0 " +
                "end";
        Long r = redis.eval(script, ScriptOutputType.INTEGER, new String[]{rk},
                String.valueOf(expectedVersion), valueJson);
        return r != null && r == 1L;
    }

    /**
     * 列出某用户命名空间下的文件：SCAN 前缀 → 排序 → 按 offset/limit 切片再 HGETALL。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.ls}/{@code glob}/{@code grep} 的
     * {@code searchAllItems}，以及 {@code WorkspaceManager.listMemoryFilePaths} /
     * {@code listSessionLogFiles}。模型列目录、官方 {@code memory_search}/{@code session_search}、
     * 档案页列日流水都会间接触达。
     */
    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String prefix = scanPrefix(namespace);
        List<String> keys = new ArrayList<>(scanKeys(prefix + "*"));
        keys.sort(Comparator.naturalOrder());
        int from = Math.min(offset, keys.size());
        int to = Math.min(from + limit, keys.size());
        List<String> page = keys.subList(from, to);
        List<StoreItem> items = new ArrayList<>();
        for (String rk : page) {
            Map<String, String> fields = redis.hgetall(rk);
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            String valueJson = fields.getOrDefault("value", "");
            long version = parseLong(fields.get("version"), 0L);
            Map<String, Object> value = JsonCodecHolder.fromJsonToMap(valueJson);
            // itemKey = 去掉前缀 + namespace/，剩下的是 fileKey
            String itemKey = stripPrefix(rk, prefix);
            items.add(new StoreItem(itemKey, value, version));
        }
        return items;
    }

    /**
     * 删单个工作区文件对应的 Hash。
     *
     * <p><b>何时调用（框架）：</b>{@code RemoteFilesystem.delete}/{@code move}（move = 读新位置 put + 删旧 key）。
     * 模型调删文件工具、技能迁移 {@code moveSkill} 时会进这里。本项目 HTTP 删会话<b>不会</b>清工作区文件。
     */
    @Override
    public void delete(List<String> namespace, String key) {
        redis.del(redisKey(namespace, key));
    }

    // ---- 工具 ----

    /** {@code as:base:alice/MEMORY.md} 去掉前缀后得到官方要的 fileKey {@code MEMORY.md}。 */
    private static String stripPrefix(String rk, String prefix) {
        return rk.startsWith(prefix) ? rk.substring(prefix.length()) : rk;
    }

    /** Hash 里的 version 转 long；缺字段或非数字用默认值，避免一次脏数据让 search 整页失败。 */
    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** SCAN 匹配，不用 KEYS。 */
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

    /** 延迟拿官方 JsonCodec，避免类加载早期 init 问题。 */
    private static final class JsonCodecHolder {
        static String toJson(Object o) {
            return io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(o);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> fromJsonToMap(String json) {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            try {
                return io.agentscope.core.util.JsonUtils.getJsonCodec()
                        .fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception e) {
                log.warn("[RedisBaseStore] value 反序列化失败: {}", e.getMessage());
                return Map.of();
            }
        }
    }
}
