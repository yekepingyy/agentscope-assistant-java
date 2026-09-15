package cn.deepassistant.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationMdcTest {

    @AfterEach
    void tearDown() {
        ConversationMdc.clear();
    }

    @Test
    void openPutsSessionIdForLogback() {
        ConversationMdc.open("sess-1");
        assertEquals("sess-1", MDC.get(ConversationMdc.SESSION_ID));
    }

    @Test
    void runClearsMdcEvenWhenActionThrows() {
        try {
            ConversationMdc.run("sess-1", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // expected
        }
        assertNull(MDC.get(ConversationMdc.SESSION_ID));
    }
}
