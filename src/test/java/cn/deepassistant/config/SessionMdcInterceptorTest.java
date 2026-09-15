package cn.deepassistant.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionMdcInterceptorTest {

    @Test
    void extractsSessionIdFromSessionsPath() {
        assertEquals("abc-123", SessionMdcInterceptor.pathSessionId("/api/sessions/abc-123"));
        assertEquals("abc-123", SessionMdcInterceptor.pathSessionId("/api/sessions/abc-123?userId=alice"));
        assertNull(SessionMdcInterceptor.pathSessionId("/api/sessions"));
        assertNull(SessionMdcInterceptor.pathSessionId("/api/sessions/"));
        assertNull(SessionMdcInterceptor.pathSessionId("/api/assistant/chat"));
    }
}
