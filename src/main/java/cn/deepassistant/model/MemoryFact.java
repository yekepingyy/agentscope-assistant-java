package cn.deepassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 档案页展示用的一条记忆。数据来源是官方 {@code MEMORY.md} 里的 Markdown 列表，
 * 不是另一套数据库。分类是根据当前 {@code ## 标题} 猜的。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryFact {

    /** 本条记忆的稳定 id，更新时靠它定位，而不是靠内容字符串。 */
    private String id;

    /** 分类由 MEMORY.md 小节标题推断：preference / identity / project / constraint / working_style / fact。 */
    private String category;

    /** 给人看的一句话。 */
    private String content;

    private String sourceSessionId;

    private double confidence;

    private Instant createdAt;
    private Instant updatedAt;
}
