package cn.deepassistant.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreSearchTest {

    @Test
    void countMatchesFindsOverlappingKeywordHits() {
        assertEquals(2, SessionStore.countMatches("agentscope agentscope", "agentscope"));
        assertEquals(0, SessionStore.countMatches("hello", "world"));
    }

    @Test
    void snippetKeepsKeywordAndAddsEllipsis() {
        String text = "AAAAABBBBB" + "关键词" + "CCCCCDDDDD";
        String snippet = SessionStore.snippetAround(text, "关键词");
        assertTrue(snippet.contains("关键词"));
    }
}
