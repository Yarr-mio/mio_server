package com.mio.ai.memory.composer;

import com.mio.ai.memory.retrieval.RetrievedItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 토큰 budget 준수 + 민감도 cap 적용 (§12.5).
 */
@Component
public class ContextSanitizer {

    private static final int MAX_CONTEXT_CHARS = 2000; // ~500 tokens

    /**
     * 민감도 cap을 초과하는 항목 제거 후 토큰 budget 내로 자름.
     */
    public List<RetrievedItem> sanitize(List<RetrievedItem> items, String sensitivityCap) {
        String effectiveCap = sensitivityCap != null ? sensitivityCap : "normal";

        List<RetrievedItem> filtered = items.stream()
                .filter(item -> item.content() != null && !item.content().isBlank())
                .filter(item -> isWithinCap(item.sensitivity(), effectiveCap))
                .collect(Collectors.toList());

        // 토큰 budget 초과 시 상위 항목 우선 유지
        int totalChars = 0;
        List<RetrievedItem> result = new java.util.ArrayList<>();
        for (RetrievedItem item : filtered) {
            totalChars += item.content().length();
            if (totalChars > MAX_CONTEXT_CHARS) break;
            result.add(item);
        }
        return result;
    }

    private boolean isWithinCap(String sensitivity, String cap) {
        if (cap == null) return false;
        // null sensitivity → "normal" 처리 (FusionRanker와 동일 정책)
        String s = sensitivity != null ? sensitivity : "normal";
        return switch (cap) {
            case "restricted" -> true;
            case "sensitive"  -> !"restricted".equals(s);
            default           -> "normal".equals(s);
        };
    }
}
