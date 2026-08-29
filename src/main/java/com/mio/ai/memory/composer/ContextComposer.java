package com.mio.ai.memory.composer;

import com.mio.ai.memory.retrieval.RetrievedItem;
import com.mio.ai.memory.retrieval.RetrievalSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    /**
     * 걸려서 버린 항목 수. {@link #ITEMS_RETAINED_METRIC} 과 <b>따로</b> 센다 —
     * 하나로 세면 "탐지가 늘었다" 와 "개인화가 깎였다" 를 구분할 수 없다. 탐지 확대를
     * 진행할 때 보존율을 대가로 지불하고 있는지 판정하려면 두 값이 분리돼 있어야 한다.
     */
    private static final String INJECTION_DROPPED_METRIC = "mio.rag.injection.dropped";

    /** 살아남아 프롬프트에 주입된 항목 수. */
    private static final String ITEMS_RETAINED_METRIC = "mio.rag.items.retained";

    private final ContextSanitizer sanitizer;
    private final InjectionScanner injectionScanner;
    private final MeterRegistry meterRegistry;

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

        // 인젝션 검사를 항목 단위로 한다 (이슈 #524). 조립된 문자열 전체를 검사하고 전체를
        // 버리면 오탐 1건이 그 턴의 기억을 전량 폐기한다 — 실측 4/4. 항목 단위로 내리면
        // 최악의 손실이 100% 에서 항목 1건으로 줄고, 그 다음에야 탐지를 늘려도 제품이 상하지
        // 않는다 (RobustRAG isolate-then-aggregate).
        //
        // highRisk 필터 **뒤에** 둔다. 앞에 두면 위기 턴에 어차피 버려질 항목까지 탐지로
        // 세어 계측이 부풀려진다.
        List<RetrievedItem> retained = sanitized.stream()
                .filter(i -> !injectionScanner.containsInjection(i.content()))
                .collect(Collectors.toList());
        int dropped = sanitized.size() - retained.size();
        if (dropped > 0) {
            Counter.builder(INJECTION_DROPPED_METRIC)
                    .description("인젝션 패턴이 걸려 프롬프트에서 제외된 검색 항목 수")
                    .register(meterRegistry)
                    .increment(dropped);
        }
        if (!retained.isEmpty()) {
            Counter.builder(ITEMS_RETAINED_METRIC)
                    .description("검사를 통과해 프롬프트에 주입된 검색 항목 수")
                    .register(meterRegistry)
                    .increment(retained.size());
        }
        if (retained.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        Map<RetrievalSource, List<RetrievedItem>> grouped = retained.stream()
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

        // 걸린 항목은 이미 빠졌다. 남은 내용에는 격리 헤더만 씌운다 — 헤더는 살아남은 내용이
        // 지시문으로 읽히지 않게 하는 장치이므로 항목을 버렸는지와 무관하게 계속 필요하다.
        return injectionScanner.wrapWithIsolation(raw);
    }

    private void appendSection(StringBuilder sb, String title, List<RetrievedItem> items) {
        if (items == null || items.isEmpty()) return;
        sb.append("[").append(title).append("]\n");
        items.forEach(i -> sb.append("- ").append(i.content()).append("\n"));
        sb.append("\n");
    }
}
