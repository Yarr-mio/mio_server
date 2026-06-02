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
        List<RetrievedItem> filtered = items.stream()
                .filter(item -> isWithinCap(item.sensitivity(), sensitivityCap))
                .collect(Collectors.toList());

        // 토큰 budget 초과 시 상위 항목 우선 유지
        int totalChars = 0;
        List<RetrievedItem> result = new java.util.ArrayList<>();
        for (RetrievedItem item : filtered) {
            totalChars += item.content() != null ? item.content().length() : 0;
            if (totalChars > MAX_CONTEXT_CHARS) break;
            result.add(item);
        }
        return result;
    }

    private boolean isWithinCap(String sensitivity, String cap) {
        return switch (cap) {
            case "restricted" -> true;
            case "sensitive"  -> !sensitivity.equals("restricted");
            default           -> sensitivity.equals("normal");
        };
    }
}
