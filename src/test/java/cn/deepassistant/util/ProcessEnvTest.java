package cn.deepassistant.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessEnvTest {

    @Test
    void blankValueIsNotWritten() {
        assertFalse(ProcessEnv.ensure("MCP_API_KEY_TEST_BLANK", "  "));
        assertFalse(ProcessEnv.ensure(" ", "x"));
    }

    @Test
    void writesWhenJvmAllowsOrReportsFailure() {
        String key = "AS_TEST_ENV_" + UUID.randomUUID().toString().replace("-", "");
        boolean ok = ProcessEnv.ensure(key, "v1");
        if (ok) {
            assertEquals("v1", System.getenv(key));
            assertTrue(ProcessEnv.ensure(key, "v2"));
            assertEquals("v1", System.getenv(key), "已有非空 env 不覆盖");
        } else {
            String now = System.getenv(key);
            assertTrue(now == null || now.isBlank());
        }
    }
}
