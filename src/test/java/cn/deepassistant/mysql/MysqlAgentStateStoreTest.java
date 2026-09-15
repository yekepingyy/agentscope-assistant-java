package cn.deepassistant.mysql;

import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直连本地 MySQL 验证官方 {@link MysqlAgentStateStore}。
 *
 * <p>只用独立库 {@code agentscope_assistant_test}，绝不碰应用默认的
 * {@code agentscope_assistant}。连不上或建库失败时测试方法直接 return。
 */
class MysqlAgentStateStoreTest {

    private static final String TEST_DATABASE = "agentscope_assistant_test";

    private static MysqlAgentStateStore store;
    private static boolean available;

    @BeforeAll
    static void connect() {
        String host = env("MYSQL_HOST", "127.0.0.1");
        String port = env("MYSQL_PORT", "3306");
        String user = env("MYSQL_USER", "root");
        String password = env("MYSQL_PASSWORD", "");
        String adminUrl = "jdbc:mysql://" + host + ":" + port
                + "/?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection conn = DriverManager.getConnection(adminUrl, user, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS " + TEST_DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (Exception e) {
            available = false;
            return;
        }
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + host + ":" + port + "/" + TEST_DATABASE
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        ds.setUsername(user);
        ds.setPassword(password);
        try {
            store = new MysqlAgentStateStore(ds, TEST_DATABASE, "agentscope_sessions", true);
            available = true;
        } catch (Exception e) {
            available = false;
        }
    }

    @BeforeEach
    void truncate() {
        if (available) {
            store.truncateAllSessions();
        }
    }

    @Test
    void agentStateStoreSaveGetDeleteRoundTrip() {
        if (!available) return;

        MapState state = wrap(Map.of("summary", "测试对话", "curIter", 3));
        store.save("alice", "sess1", "agent", state);

        Optional<MapState> got = store.get("alice", "sess1", "agent", MapState.class);
        assertTrue(got.isPresent());
        assertEquals("测试对话", got.get().getSummary());
        assertEquals(3, got.get().getCurIter());

        assertTrue(store.exists("alice", "sess1"));
        assertFalse(store.exists("alice", "sess2"));
        assertFalse(store.exists("bob", "sess1"));

        assertEquals(Set.of("sess1"), store.listSessionIds("alice"));

        store.delete("alice", "sess1");
        assertFalse(store.exists("alice", "sess1"));
        assertTrue(store.listSessionIds("alice").isEmpty());
    }

    @Test
    void agentStateStoreIsolatesUsers() {
        if (!available) return;

        store.save("alice", "s1", "agent", wrap(Map.of("summary", "alice的对话")));
        store.save("bob", "s1", "agent", wrap(Map.of("summary", "bob的对话")));

        assertEquals("alice的对话",
                store.get("alice", "s1", "agent", MapState.class).orElseThrow().getSummary());
        assertEquals("bob的对话",
                store.get("bob", "s1", "agent", MapState.class).orElseThrow().getSummary());

        assertEquals(Set.of("s1"), store.listSessionIds("alice"));
        assertEquals(Set.of("s1"), store.listSessionIds("bob"));

        store.delete("alice", "s1");
        assertFalse(store.exists("alice", "s1"));
        assertTrue(store.exists("bob", "s1"));
    }

    @Test
    void listSlotRewritesWhenContentChanges() {
        if (!available) return;

        store.save("alice", "sess1", "tasks", List.of(
                wrap(Map.of("summary", "t1")),
                wrap(Map.of("summary", "t2"))));
        List<MapState> first = store.getList("alice", "sess1", "tasks", MapState.class);
        assertEquals(2, first.size());
        assertEquals("t1", first.get(0).getSummary());
        assertEquals("t2", first.get(1).getSummary());

        store.save("alice", "sess1", "tasks", List.of(wrap(Map.of("summary", "only"))));
        List<MapState> replaced = store.getList("alice", "sess1", "tasks", MapState.class);
        assertEquals(1, replaced.size());
        assertEquals("only", replaced.get(0).getSummary());
    }

    private static MapState wrap(Map<String, Object> m) {
        MapState s = new MapState();
        s.setSummary((String) m.get("summary"));
        Object c = m.get("curIter");
        s.setCurIter(c == null ? 0 : ((Number) c).intValue());
        return s;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    public static class MapState implements io.agentscope.core.state.State {
        private String summary;
        private int curIter;

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public int getCurIter() { return curIter; }
        public void setCurIter(int curIter) { this.curIter = curIter; }
    }
}
