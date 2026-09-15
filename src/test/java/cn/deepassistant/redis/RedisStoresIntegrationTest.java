package cn.deepassistant.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直连本地 Redis（127.0.0.1:6379 <b>db=15</b>）验证 {@link RedisBaseStore}。
 *
 * <p>需要本地 Redis 在跑。只 FLUSHDB <b>测试库 15</b>，绝不碰应用默认的 db=0
 * （网页会话 {@code as:web:} 也在 db=0，以前测例会把真实对话清掉）。
 * AgentState 已迁到官方 {@link io.agentscope.extensions.mysql.state.MysqlAgentStateStore}，
 * 见 {@link cn.deepassistant.mysql.MysqlAgentStateStoreTest}。
 * 如果本地没 Redis，测试方法直接 return。
 */
class RedisStoresIntegrationTest {

    /** 和应用 {@code redis.database=0} 错开，避免 mvn test 删掉开发对话。 */
    private static final int TEST_DATABASE = 15;

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;
    private static RedisCommands<String, String> redis;
    private static boolean available;

    @BeforeAll
    static void connect() {
        try {
            client = RedisClient.create(RedisURI.builder()
                    .withHost("127.0.0.1")
                    .withPort(6379)
                    .withDatabase(TEST_DATABASE)
                    .build());
            conn = client.connect();
            redis = conn.sync();
            redis.ping();
            available = true;
        } catch (Exception e) {
            available = false;
        }
    }

    @AfterAll
    static void close() {
        if (conn != null) {
            conn.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @BeforeEach
    void flush() {
        if (available) {
            redis.flushdb();
        }
    }

    @Test
    void baseStorePutGetSearchDelete() {
        if (!available) return;
        RedisBaseStore store = new RedisBaseStore(redis);

        List<String> ns = List.of("alice");
        store.put(ns, "MEMORY.md", Map.of("content", "# 长期记忆\n- 喜欢中文"));
        store.put(ns, "memory/2024-01-01.md", Map.of("content", "日流水1"));
        store.put(ns, "memory/2024-01-02.md", Map.of("content", "日流水2"));

        // get
        var item = store.get(ns, "MEMORY.md");
        assertTrue(item != null);
        assertEquals("MEMORY.md", item.key());
        assertEquals("# 长期记忆\n- 喜欢中文", ((Map<?, ?>) item.value()).get("content"));
        assertEquals(1L, item.version());

        // put 再写一次，version 递增
        store.put(ns, "MEMORY.md", Map.of("content", "更新"));
        assertEquals(2L, store.get(ns, "MEMORY.md").version());

        // search：列 memory/ 下的文件
        var hits = store.search(ns, 100, 0);
        assertEquals(3, hits.size());

        // 不同用户隔离
        store.put(List.of("bob"), "MEMORY.md", Map.of("content", "bob的"));
        var bobHits = store.search(List.of("bob"), 100, 0);
        assertEquals(1, bobHits.size());
        var aliceHits = store.search(ns, 100, 0);
        assertEquals(3, aliceHits.size());

        // putIfVersion 乐观锁
        assertTrue(store.putIfVersion(ns, "MEMORY.md", Map.of("content", "v3"), 2L));
        assertFalse(store.putIfVersion(ns, "MEMORY.md", Map.of("content", "stale"), 1L));
        assertEquals(3L, store.get(ns, "MEMORY.md").version());

        // delete
        store.delete(ns, "MEMORY.md");
        assertEquals(null, store.get(ns, "MEMORY.md"));
    }
}
