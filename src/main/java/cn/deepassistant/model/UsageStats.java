package cn.deepassistant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 「这个用户历史上怎么用过助手」的统计，全部从 {@code SessionStore} 现算，不调大模型。
 *
 * <p>自动抽取解决的是「记住偏好」；这份统计解决的是「知道用过多少、最近聊了啥」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageStats {

    private int sessionCount;
    private int messageCount;
    private int userMessageCount;
    private Instant firstSeenAt;
    private Instant lastSeenAt;

    /** 最近几条会话标题，用来在档案页展示「最近在聊什么」。 */
    @Builder.Default
    private List<SessionSummary> recentSessions = new ArrayList<>();
}
