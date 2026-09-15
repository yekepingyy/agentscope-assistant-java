package cn.deepassistant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistorySearchResponse {

    private String query;
    private int hitCount;

    @Builder.Default
    private List<HistorySearchHit> hits = new ArrayList<>();
}
