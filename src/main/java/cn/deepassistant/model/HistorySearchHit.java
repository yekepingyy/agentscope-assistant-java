package cn.deepassistant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 历史检索的一条命中。用户在侧栏搜「AgentScope」时，每条结果对应某次会话里的一句话。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistorySearchHit {

    private String sessionId;
    private String sessionTitle;
    private String role;
    private Instant timestamp;

    /** 带一点前后文的摘录，方便人眼扫，不必打开整段对话。 */
    private String snippet;

    /** 这一句里关键词出现了几次，用来排序。 */
    private int matchCount;
}
