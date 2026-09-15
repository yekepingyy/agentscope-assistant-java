package cn.deepassistant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 官方第一层记忆：某天的日流水账 {@code workspace/memory/YYYY-MM-DD.md}。
 * 内容由框架追加，本项目只读、不改。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyMemoryFile {

    /** 相对工作区的路径，例如 {@code memory/2026-09-08.md}。 */
    private String path;
    private Instant lastModified;
    private String content;
}
