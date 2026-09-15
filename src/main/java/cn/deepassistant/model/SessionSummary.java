package cn.deepassistant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummary {
    private String id;
    private String title;
    private String preview;
    private Instant updatedAt;
    private int messageCount;
}
