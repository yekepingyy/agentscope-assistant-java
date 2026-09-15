package cn.deepassistant.memory;

import cn.deepassistant.model.MemoryFact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessMemoryCatalogTest {

    @Test
    void parseFactsReadsBulletsUnderHeadings() {
        String md = """
                # 长期记忆
                ## 偏好
                - 回答用中文
                ## 项目与技术
                - 正在做 AgentScope Java 2.0
                """;
        List<MemoryFact> facts = HarnessMemoryCatalog.parseFacts(md);
        assertEquals(2, facts.size());
        assertEquals("preference", facts.get(0).getCategory());
        assertEquals("回答用中文", facts.get(0).getContent());
        assertEquals("project", facts.get(1).getCategory());
    }

    @Test
    void parseFactsIgnoresPlaceholderLines() {
        assertTrue(HarnessMemoryCatalog.parseFacts("（还没有）\n- （空）").isEmpty());
    }
}
