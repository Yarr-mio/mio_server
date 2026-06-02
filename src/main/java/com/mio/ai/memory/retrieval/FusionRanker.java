package com.mio.ai.memory.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (§12.4).
 * RRF(d) = Σ_{r in retrievers} 1 / (k + rank_r(d)),  k ≈ 60
 */
@Component
public class FusionRanker {

    private static final int RRF_K = 60;

    /**
     * 여러 소스의 결과 리스트를 RRF로 합산 후 sensitivityCap을 적용해 반환.
     */
    public List<RetrievedItem> rank(List<List<RetrievedItem>> resultLists, String sensitivityCap, int maxResults) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, RetrievedItem> itemMap = new HashMap<>();

        for (List<RetrievedItem> list : resultLists) {
            for (int i = 0; i < list.size(); i++) {
                RetrievedItem item = list.get(i);
                // 민감도 cap: restricted 이상은 제외
                if (!isWithinCap(item.sensitivity(), sensitivityCap)) continue;

                double rrfScore = 1.0 / (RRF_K + i + 1);
                rrfScores.merge(item.id(), rrfScore, Double::sum);
                itemMap.putIfAbsent(item.id(), item);
            }
        }

        List<RetrievedItem> ranked = new ArrayList<>();
        rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .forEach(e -> {
                    RetrievedItem orig = itemMap.get(e.getKey());
                    ranked.add(new RetrievedItem(
                            orig.id(), orig.source(), orig.content(),
                            orig.sensitivity(), e.getValue(), ranked.size() + 1
                    ));
                });

        return ranked;
    }

    private boolean isWithinCap(String sensitivity, String cap) {
        if (cap == null || sensitivity == null) return false;
        return switch (cap) {
            case "restricted" -> true;
            case "sensitive"  -> !"restricted".equals(sensitivity);
            default           -> "normal".equals(sensitivity);
        };
    }
}
