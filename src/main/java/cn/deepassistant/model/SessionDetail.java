package cn.deepassistant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionDetail {
    private String id;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    @Builder.Default
    private List<ChatMessageRecord> messages = new ArrayList<>();
    private boolean pendingApproval;
}
