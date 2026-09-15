package cn.deepassistant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 「我的档案」页一次返回的数据：使用次数（本项目会话库）+ 官方两层记忆文件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMemoryProfile {

    private UsageStats usage;

    /** 第二层：策划后的长期记忆 MEMORY.md，每轮会进 system prompt。 */
    private String memoryMarkdown;

    /** 第一层：按日期倒序的日流水账。 */
    @Builder.Default
    private List<DailyMemoryFile> dailyLedgers = new ArrayList<>();

    /**
     * 从 MEMORY.md 里拆出来的列表项，方便前端分组展示。
     * 不是另一套存储，只是 Markdown 的解析结果。
     */
    @Builder.Default
    private List<MemoryFact> facts = new ArrayList<>();
}
