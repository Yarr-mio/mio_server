package com.mio.ai.memory.composer;

import com.mio.ai.memory.retrieval.RetrievedItem;
import com.mio.ai.memory.retrieval.SensitivityCap;
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
        // cap 이 없으면 가장 좁은 등급으로 본다. SensitivityCap 은 null 을 fail-closed
        // 로 처리하지만, 여기서는 "지정 안 함 = 기본 공개 범위"가 기존 동작이라 유지한다.
        String effectiveCap = sensitivityCap != null ? sensitivityCap : SensitivityCap.NORMAL;

        List<RetrievedItem> filtered = items.stream()
                .filter(item -> item.content() != null && !item.content().isBlank())
                .filter(item -> SensitivityCap.allows(effectiveCap, item.sensitivity()))
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

}
