package cn.deepassistant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageRecord {
    private String role;
    private String content;
    private Instant timestamp;
    @Builder.Default
    private List<Map<String, Object>> events = new ArrayList<>();
}
