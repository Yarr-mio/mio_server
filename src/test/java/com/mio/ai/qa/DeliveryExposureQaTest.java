package com.mio.ai.qa;

import com.mio.ai.delivery.ApprovedUnitBuffer;
import com.mio.ai.delivery.HoldbackDelivery;
import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.judge.OutputPreFilterResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 전 노출 측정 (이슈 #306, 로드맵 §12 P0-4).
 *
 * <p>P0-4의 완료 조건은 "위해 노출 증가 없이 지연 개선"이다. 그 판정에 생성 LLM 은 필요
 * 없다 — 전달 컨트롤러에 합성 스트림을 흘리면 <b>검사를 통과하지 않은 채 몇 글자가
 * 전달되는지</b>를 결정론적으로 잴 수 있다. 과금 없이 회귀 게이트로 쓸 수 있어야 한다.
 *
 * <p>비교 대상인 기존 방식(200자 간격 사후 검사)의 노출량은 코드를 남기지 않고 정의로
 * 계산한다 — 첫 검사가 200자 시점이므로, 그 전에 도착한 문자는 전부 검사 없이 전달됐다.
 */
@DisplayName("[QA] 전달 노출 — 승인 단위 holdback")
class DeliveryExposureQaTest {

    /** 이전 구현의 사후 검사 간격. 비교 기준으로만 쓴다. */
    private static final int LEGACY_CHECK_INTERVAL = 200;

    private final OutputPreFilter outputPreFilter = new OutputPreFilter();

    private record Scenario(String name, List<String> chunks, boolean violating) {}

    /**
     * 합성 스트림.
     *
     * <p>위반 케이스는 {@code OutputPreFilter} 가 실제로 잡는 표현을 쓴다. 검사기를 흉내 낸
     * 가짜 조건으로 재면 이 하네스는 아무것도 보장하지 못한다.
     */
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("정상 공감 응답", List.of(
                    "많이 지치셨겠어요. ", "그런 날은 누구에게나 있어요. ",
                    "지금 어떤 기분이 가장 크게 느껴지나요?"), false),
            new Scenario("긴 정상 응답", List.of(
                    "오늘 하루가 정말 길게 느껴지셨겠어요. ",
                    "아침부터 계속 신경 쓸 일이 많았다고 하셨죠. ",
                    "그렇게 하루 종일 긴장이 이어지면 몸도 마음도 지치기 마련이에요. ",
                    "잠깐이라도 숨을 고를 틈이 있었는지 궁금해요. ",
                    "지금은 조금 나아지셨나요?"), false),
            new Scenario("첫 문장이 위반", List.of(
                    "당신은 우울증이에요. ", "하지만 곧 좋아질 거예요. ", "힘내세요."), true),
            new Scenario("중간 문장이 위반", List.of(
                    "그랬군요. ", "많이 힘드셨겠어요. ",
                    "당신은 불안장애 증상이 있어요. ", "그래도 괜찮아요."), true),
            new Scenario("종결 부호 없는 긴 출력", List.of(
                    "아주 긴 문장이 종결 부호 없이 계속 이어지는 경우도 있어요 ".repeat(6)), false)
    );

    @Test
    @DisplayName("검사를 통과하지 않은 문자는 사용자에게 전달되지 않는다")
    void noContentIsDeliveredBeforeItPassesTheCheck() throws Exception {
        Map<String, Integer> holdbackExposure = new LinkedHashMap<>();
        Map<String, Integer> legacyExposure = new LinkedHashMap<>();
        List<String> leaked = new ArrayList<>();

        for (Scenario scenario : SCENARIOS) {
            List<String> sent = new ArrayList<>();
            HoldbackDelivery holdback = new HoldbackDelivery(
                    new ApprovedUnitBuffer(),
                    candidate -> outputPreFilter.check(candidate).passed(),
                    sent::add);

            holdback.consume(scenario.chunks());

            String delivered = String.join("", sent);
            OutputPreFilterResult deliveredCheck = outputPreFilter.check(delivered);
            if (!delivered.isEmpty() && !deliveredCheck.passed()) {
                leaked.add("%s → %s".formatted(scenario.name(), deliveredCheck.failReasons()));
            }

            holdbackExposure.put(scenario.name(), holdback.unverifiedExposedChars());
            legacyExposure.put(scenario.name(), legacyExposureOf(scenario));
        }

        printReport(holdbackExposure, legacyExposure);

        assertThat(leaked)
                .as("검사를 통과하지 못한 내용이 전달됐다")
                .isEmpty();
        assertThat(holdbackExposure.values())
                .as("승인 단위 전달에서 검증 전 노출은 0이어야 한다")
                .allMatch(chars -> chars == 0);
    }

    @Test
    @DisplayName("위반 문장은 전달되지 않고 앞선 안전한 문장만 남는다")
    void violatingSentenceIsWithheldWhileEarlierOnesRemain() throws Exception {
        Scenario scenario = SCENARIOS.get(3);
        List<String> sent = new ArrayList<>();
        HoldbackDelivery holdback = new HoldbackDelivery(
                new ApprovedUnitBuffer(),
                candidate -> outputPreFilter.check(candidate).passed(),
                sent::add);

        holdback.consume(scenario.chunks());

        String delivered = String.join("", sent);
        assertThat(delivered).isEqualTo("그랬군요. 많이 힘드셨겠어요. ");
        assertThat(delivered).doesNotContain("불안장애");
        assertThat(holdback.blocked()).isTrue();
    }

    /**
     * 이전 구현이었다면 검사 없이 전달됐을 문자 수.
     *
     * <p>첫 검사는 누적 200자 시점에 실행됐다. 그 전에 도착한 문자는 전부 검사 없이 나갔고,
     * 응답이 200자에 못 미치면 스트림 중에는 한 번도 검사되지 않았다.
     */
    private int legacyExposureOf(Scenario scenario) {
        int total = String.join("", scenario.chunks()).length();
        return Math.min(total, LEGACY_CHECK_INTERVAL);
    }

    private void printReport(Map<String, Integer> holdback, Map<String, Integer> legacy) {
        StringBuilder out = new StringBuilder();
        out.append("\n══════════════════════════════════════════════════════════════\n");
        out.append("  검증 전 노출 문자 수 (이슈 #306)\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        out.append("  %-28s %10s %10s%n".formatted("시나리오", "이전(200자)", "승인 단위"));
        legacy.forEach((name, chars) ->
                out.append("  %-28s %10d %10d%n".formatted(name, chars, holdback.get(name))));
        out.append("\n  이전 값은 첫 검사(누적 200자) 전에 전달됐을 문자 수를 정의로 계산한 것이다.\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        System.out.print(out);
    }
}
