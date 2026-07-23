package com.mio.ai.memory.composer;

import com.mio.ai.memory.retrieval.RetrievedItem;
import com.mio.ai.memory.retrieval.RetrievalSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * 검색 결과를 구조화 요약으로 조합 (§12.5).
 * 원본 belief text는 들어가지 않음 — 구조화 필드만.
 */
@Component
@RequiredArgsConstructor
public class ContextComposer {

    private final ContextSanitizer sanitizer;
    private final InjectionScanner injectionScanner;

    /**
     * @param items          FusionRanker 결과
     * @param sensitivityCap 민감도 cap ("normal" | "sensitive" | "restricted")
     * @return 프롬프트에 주입할 컨텍스트 문자열
     */
    public String compose(List<RetrievedItem> items, String sensitivityCap, boolean highRisk) {
        if (items == null || items.isEmpty()) return "";

        List<RetrievedItem> sanitized = sanitizer.sanitize(items, sensitivityCap);
        if (sanitized.isEmpty()) return "";

        // 고위험 시 안전 우선: 과거 부정 기억 주입 최소화
        if (highRisk) {
            sanitized = sanitized.stream()
                    .filter(i -> i.source() == RetrievalSource.SQL_RHYTHM
                            || i.source() == RetrievalSource.SQL_RECENT_RISK
                            || i.source() == RetrievalSource.GRAPH_TRIGGER)
                    .collect(Collectors.toList());
        }

        StringBuilder sb = new StringBuilder();

        Map<RetrievalSource, List<RetrievedItem>> grouped = sanitized.stream()
                .collect(Collectors.groupingBy(RetrievedItem::source));

        appendSection(sb, "Recent Emotion Pattern",
                grouped.get(RetrievalSource.SQL_RHYTHM));
        appendSection(sb, "Recent Risk Context",
                grouped.get(RetrievalSource.SQL_RECENT_RISK));
        appendSection(sb, "Past Similar Situations",
                Stream.concat(
                                grouped.getOrDefault(RetrievalSource.GRAPH_TRIGGER, List.of()).stream(),
                                grouped.getOrDefault(RetrievalSource.GRAPH_DISTORTION, List.of()).stream())
                        .toList());
        appendSection(sb, "Active Patterns",
                grouped.get(RetrievalSource.SQL_PROFILE));
        appendSection(sb, "Helpful Approaches",
                grouped.get(RetrievalSource.GRAPH_INTERVENTION_FIT));
        appendSection(sb, "Recent Episodes",
                Stream.concat(
                                grouped.getOrDefault(RetrievalSource.VECTOR_EPISODE, List.of()).stream(),
                                grouped.getOrDefault(RetrievalSource.LEXICAL_EPISODE, List.of()).stream())
                        .toList());
        appendSection(sb, "Belief Context",
                Stream.concat(
                                grouped.getOrDefault(RetrievalSource.VECTOR_BELIEF, List.of()).stream(),
                                grouped.getOrDefault(RetrievalSource.GRAPH_BELIEF_NEIGH, List.of()).stream())
                        .toList());
        appendSection(sb, "Recent Activities",
                grouped.get(RetrievalSource.SQL_TODO_HISTORY));

        String raw = sb.toString().trim();
        if (raw.isEmpty()) return "";

        return injectionScanner.sanitize(raw);
    }

    private void appendSection(StringBuilder sb, String title, List<RetrievedItem> items) {
        if (items == null || items.isEmpty()) return;
        sb.append("[").append(title).append("]\n");
        items.forEach(i -> sb.append("- ").append(i.content()).append("\n"));
        sb.append("\n");
    }
}
