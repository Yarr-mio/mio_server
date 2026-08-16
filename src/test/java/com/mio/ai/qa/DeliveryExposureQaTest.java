package com.mio.ai.qa;

import com.mio.ai.delivery.ApprovedUnitBuffer;
import com.mio.ai.delivery.HoldbackDelivery;
import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.moderation.ModerationStatus;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.plan.ResponsePlanner;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 전 노출과 첫 화면 지연 측정 (이슈 #306 · P0-4, 로드맵 §12).
 *
 * <p>P0-4의 완료 조건은 "위해 노출 증가 없이 지연 개선"이다. 그 판정에 생성 LLM 은 필요
 * 없다 — 전달 컨트롤러에 합성 스트림을 흘리면 <b>검사를 통과하지 않은 채 몇 글자가
 * 전달되는지</b>와 <b>사용자가 첫 글자를 보기까지 얼마나 걸리는지</b>를 결정론적으로 잴 수
 * 있다. 과금 없이 회귀 게이트로 쓸 수 있어야 한다.
 *
 * <p>비교 대상인 기존 방식(200자 간격 사후 검사)의 노출량은 코드를 남기지 않고 정의로
 * 계산한다 — 첫 검사가 200자 시점이므로, 그 전에 도착한 문자는 전부 검사 없이 전달됐다.
 *
 * <p>지연은 <b>가상 시계</b>로 잰다. 실제 시계로 재면 CI 부하에 따라 값이 흔들려 회귀와
 * 잡음을 구분할 수 없다. 시계는 가정이지만 측정 대상인 {@link HoldbackDelivery} 는 실제
 * 구현이다 — 하네스가 흉내 내는 것은 시간뿐이다.
 */
@DisplayName("[QA] 전달 노출·첫 화면 지연 — 승인 단위 holdback")
class DeliveryExposureQaTest {

    /** 이전 구현의 사후 검사 간격. 비교 기준으로만 쓴다. */
    private static final int LEGACY_CHECK_INTERVAL = 200;

    /**
     * 지연 추정을 위한 스트리밍 속도 가정.
     *
     * <p>gpt-4o 계열의 한국어 출력은 초당 대략 20~40 토큰이고 한 토큰이 한글 1~2자 수준이다.
     * 여기서는 보수적으로 초당 30자로 잡는다. 실측이 아니라 **가정**이며, 실제 값은 운영
     * trace 의 {@code first_substantive_token_ms} 로 대체돼야 한다.
     */
    private static final double ASSUMED_CHARS_PER_SECOND = 30.0;

    /**
     * 첫 생성 토큰까지의 가정 지연.
     *
     * <p>safe prefix 가 메우는 구간은 "첫 생성 토큰까지" 와 "첫 승인 단위까지" 를 합한 시간이다.
     * 앞쪽은 모델·프롬프트·cold start 에 달려 있어 이 하네스가 잴 수 없으므로 값을 가정한다.
     * 이 값이 크든 작든 <b>prefix 가 만드는 차이의 방향</b>은 바뀌지 않는다 — prefix 는 두
     * 구간 모두의 앞에 나가기 때문이다.
     */
    private static final long ASSUMED_TTFT_MS = 600;


    /** 서버 문구 전송에 드는 시간. 메모리에 있는 문자열 하나를 SSE 로 쓰는 비용이다. */
    /** safe prefix 대상 응답 행위 — 로드맵 §5.6 이 예로 든 자유도가 낮은 행위. */
    private static final java.util.Set<ResponseAct> PREFIX_ELIGIBLE_ACTS =
            java.util.Set.of(ResponseAct.EMOTION_CHECK, ResponseAct.CLARIFY_CONTEXT);

    private final OutputPreFilter outputPreFilter = new OutputPreFilter();
    private final ResponsePlanner responsePlanner = new ResponsePlanner();

    /**
     * @param mode  이 턴의 생성 모드·위험도. 실제 {@code ResponsePlanner} 를 태워 응답 행위를
     *              얻는다 — 행위를 하네스가 직접 지정하면 계획 규칙이 바뀌어도 조용히 통과한다
     */
    private record Scenario(String name, List<String> chunks, GenerationMode mode, RiskLevel risk,
                            DeliveryMode delivery) {}

    /**
     * 합성 스트림.
     *
     * <p>위반 케이스는 {@code OutputPreFilter} 가 실제로 잡는 표현을 쓴다. 검사기를 흉내 낸
     * 가짜 조건으로 재면 이 하네스는 아무것도 보장하지 못한다.
     */
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("정상 공감 응답", List.of(
                    "많이 지치셨겠어요. ", "그런 날은 누구에게나 있어요. ",
                    "지금 어떤 기분이 가장 크게 느껴지나요?"),
                    GenerationMode.SUPPORTIVE, RiskLevel.MEDIUM, DeliveryMode.CAUTIOUS_SPECULATIVE),
            new Scenario("긴 정상 응답", List.of(
                    "오늘 하루가 정말 길게 느껴지셨겠어요. ",
                    "아침부터 계속 신경 쓸 일이 많았다고 하셨죠. ",
                    "그렇게 하루 종일 긴장이 이어지면 몸도 마음도 지치기 마련이에요. ",
                    "잠깐이라도 숨을 고를 틈이 있었는지 궁금해요. ",
                    "지금은 조금 나아지셨나요?"),
                    GenerationMode.SUPPORTIVE, RiskLevel.MEDIUM, DeliveryMode.CAUTIOUS_SPECULATIVE),
            new Scenario("첫 문장이 위반", List.of(
                    "당신은 우울증이에요. ", "하지만 곧 좋아질 거예요. ", "힘내세요."),
                    GenerationMode.SUPPORTIVE, RiskLevel.MEDIUM, DeliveryMode.CAUTIOUS_SPECULATIVE),
            new Scenario("중간 문장이 위반", List.of(
                    "그랬군요. ", "많이 힘드셨겠어요. ",
                    "당신은 불안장애 증상이 있어요. ", "그래도 괜찮아요."),
                    GenerationMode.SUPPORTIVE, RiskLevel.LOW, DeliveryMode.CAUTIOUS_SPECULATIVE),
            new Scenario("종결 부호 없는 긴 출력", List.of(
                    "아주 긴 문장이 종결 부호 없이 계속 이어지는 경우도 있어요 ".repeat(6)),
                    GenerationMode.SUPPORTIVE, RiskLevel.MEDIUM, DeliveryMode.CAUTIOUS_SPECULATIVE),
            // prefix 가 붙지 않는 대조군. 붙는 턴만 재면 "두 값이 갈라진다"가 배선 때문인지
            // 하네스가 항상 그렇게 계산하기 때문인지 구분할 수 없다.
            new Scenario("계획 밖 일반 대화", List.of(
                    "그랬군요. ", "오늘은 어떤 하루였는지 더 들려주실래요?"),
                    GenerationMode.NORMAL, RiskLevel.CLEAR_LOW, DeliveryMode.CAUTIOUS_SPECULATIVE),
            new Scenario("HIGH 위험 응답", List.of(
                    "많이 힘드셨겠어요. ", "지금 안전한 곳에 계신가요?"),
                    GenerationMode.GUARDED, RiskLevel.HIGH, DeliveryMode.BUFFER)
    );

    /** 한 시나리오의 측정 결과. */
    private record Measurement(boolean prefixEligible, long renderedMs, long substantiveMs,
                               String deliveredContent, int exposedChars, int firstUnitChars) {

        /** 첫 화면과 첫 실질 콘텐츠의 차이. 아무것도 전달되지 않았으면 잴 수 없다. */
        long deltaMs() {
            return substantiveMs < 0 ? -1 : substantiveMs - renderedMs;
        }
    }

    @Test
    @DisplayName("검사를 통과하지 않은 문자는 사용자에게 전달되지 않는다")
    void noContentIsDeliveredBeforeItPassesTheCheck() throws Exception {
        Map<String, Measurement> measured = measureAll();
        List<String> leaked = new ArrayList<>();

        measured.forEach((name, m) -> {
            OutputPreFilterResult deliveredCheck = outputPreFilter.check(m.deliveredContent());
            if (!m.deliveredContent().isEmpty() && !deliveredCheck.passed()) {
                leaked.add("%s → %s".formatted(name, deliveredCheck.failReasons()));
            }
        });

        printExposureReport(measured);

        assertThat(leaked)
                .as("검사를 통과하지 못한 내용이 전달됐다")
                .isEmpty();
        assertThat(measured.values().stream().map(Measurement::exposedChars).toList())
                .as("승인 단위 전달에서 검증 전 노출은 0이어야 한다")
                .allMatch(chars -> chars == 0);
    }

    @Test
    @DisplayName("safe prefix 가 붙은 턴은 첫 화면 지연이 첫 실질 토큰보다 앞선다")
    void safePrefixMakesRenderedAndSubstantiveLatencyDiverge() throws Exception {
        Map<String, Measurement> measured = measureAll();

        printLatencyReport(measured);

        List<String> prefixed = measured.entrySet().stream()
                .filter(entry -> entry.getValue().prefixEligible())
                .map(Map.Entry::getKey)
                .toList();
        List<String> plain = measured.entrySet().stream()
                .filter(entry -> !entry.getValue().prefixEligible())
                .map(Map.Entry::getKey)
                .toList();

        assertThat(prefixed).as("prefix 대상 시나리오가 하나도 없다 — 계획 규칙이 바뀐 것이다").isNotEmpty();
        assertThat(plain).as("대조군이 없으면 갈라짐이 배선 때문인지 알 수 없다").isNotEmpty();

        for (String name : prefixed) {
            Measurement m = measured.get(name);
            assertThat(m.renderedMs())
                    .as("%s — 서버 문구는 생성보다 먼저 나간다", name)
                    .isLessThan(ASSUMED_TTFT_MS);
            if (m.substantiveMs() >= 0) {
                assertThat(m.deltaMs())
                        .as("%s — 두 지연이 같으면 prefix 가 사용자에게 도달하지 않았다는 뜻이다", name)
                        .isPositive();
            }
        }
        for (String name : plain) {
            Measurement m = measured.get(name);
            assertThat(m.renderedMs())
                    .as("%s — prefix 가 없는 턴에서 두 값이 갈라지면 배선이 틀린 것이다", name)
                    .isEqualTo(m.substantiveMs());
        }
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

    private Map<String, Measurement> measureAll() throws Exception {
        Map<String, Measurement> measured = new LinkedHashMap<>();
        for (Scenario scenario : SCENARIOS) {
            measured.put(scenario.name(), measure(scenario));
        }
        return measured;
    }

    /**
     * 한 시나리오를 가상 시계로 재생한다.
     *
     * <p>청크가 아니라 <b>글자 단위</b>로 흘린다. 단위 경계는 청크 경계와 무관하게 결정되므로
     * (종결 부호가 없으면 길이 상한에서 끊긴다) 청크 단위로 시간을 진행시키면 그 시나리오의
     * 지연이 실제보다 작게 나온다.
     */
    private Measurement measure(Scenario scenario) throws Exception {
        PolicyDecision decision = decisionFor(scenario);
        boolean prefixEligible = PREFIX_ELIGIBLE_ACTS.contains(decision.responsePlan().responseAct())
                && decision.deliveryMode() == DeliveryMode.CAUTIOUS_SPECULATIVE;

        AtomicLong clockMs = new AtomicLong(0);
        List<String> sent = new ArrayList<>();
        HoldbackDelivery holdback = new HoldbackDelivery(
                new ApprovedUnitBuffer(),
                candidate -> outputPreFilter.check(candidate).passed(),
                sent::add,
                clockMs::get);

        // 아직 서버가 먼저 보내는 문장이 없다. 사용자가 처음 보는 것은 첫 승인 단위이므로
        // 첫 화면 지연과 첫 실질 토큰 지연은 같은 값이다 — 그것이 이 커밋이 재현하는 상태다.
        // 생성은 prefix 와 무관하게 진행된다 — 서버가 먼저 보내는 것은 전달이지 생성이 아니다.
        clockMs.set(ASSUMED_TTFT_MS);
        String generated = String.join("", scenario.chunks());
        for (int i = 0; i < generated.length(); i++) {
            clockMs.set(ASSUMED_TTFT_MS + Math.round(i / ASSUMED_CHARS_PER_SECOND * 1000));
            holdback.onChunk(String.valueOf(generated.charAt(i)));
        }
        holdback.finish();

        String delivered = String.join("", sent);
        long substantiveMs = holdback.firstSubstantiveTokenMs();
        // 노출은 상수가 아니라 결과로 잰다 — 전달된 텍스트를 다시 검사해서 통과하지 않는
        // 내용이 섞였는지 본다. 값이 아니라 결과로 확인해야 게이트를 우회하는 변경을 잡는다.
        int exposed = outputPreFilter.check(delivered).passed() ? 0 : delivered.length();

        return new Measurement(
                prefixEligible,
                substantiveMs,
                substantiveMs,
                delivered,
                exposed,
                firstUnitLengthOf(scenario));
    }

    /** 실제 계획 규칙을 태운 정책 결정. 응답 행위를 하네스가 지정하지 않는다. */
    private PolicyDecision decisionFor(Scenario scenario) {
        PolicyDecision base = new PolicyDecision(
                "pd_qa_" + scenario.name().hashCode(), DecisionAction.GENERATE, scenario.mode(),
                scenario.delivery(), SecurityLevel.CLEAN, true, true, true,
                InterventionHints.empty(), "qa", scenario.risk(), null,
                JudgeStatus.SUCCEEDED, ModerationStatus.RESOLVED, ResponsePlan.unplanned());
        return base.withResponsePlan(responsePlanner.plan(base));
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

    /**
     * 첫 승인 단위의 길이 — 이 구조가 만드는 추가 지연의 크기다.
     *
     * <p>이전에는 첫 청크가 도착하는 즉시 화면에 떴다. 이제는 첫 문장이 완성될 때까지
     * 기다린다. 그 차이를 문자 수로 재고, 가정한 속도로 밀리초 추정을 붙인다.
     */
    private int firstUnitLengthOf(Scenario scenario) {
        ApprovedUnitBuffer buffer = new ApprovedUnitBuffer();
        for (String chunk : scenario.chunks()) {
            List<String> units = buffer.offer(chunk);
            if (!units.isEmpty()) {
                return units.get(0).length();
            }
        }
        return buffer.drain().length();
    }

    private void printExposureReport(Map<String, Measurement> measured) {
        StringBuilder out = new StringBuilder();
        out.append("\n══════════════════════════════════════════════════════════════\n");
        out.append("  검증 전 노출 문자 수 (이슈 #306)\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        out.append("  %-28s %10s %10s%n".formatted("시나리오", "이전(200자)", "승인 단위"));
        for (Scenario scenario : SCENARIOS) {
            out.append("  %-28s %10d %10d%n".formatted(
                    scenario.name(), legacyExposureOf(scenario),
                    measured.get(scenario.name()).exposedChars()));
        }
        out.append("\n  이전 값은 첫 검사(누적 200자) 전에 전달됐을 문자 수를 정의로 계산한 것이다.\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        System.out.print(out);
    }

    private void printLatencyReport(Map<String, Measurement> measured) {
        StringBuilder out = new StringBuilder();
        out.append("\n══════════════════════════════════════════════════════════════\n");
        out.append("  첫 화면 지연 vs 첫 실질 토큰 지연 (P0-4)\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        out.append("  %-24s %7s %9s %11s %9s%n"
                .formatted("시나리오", "prefix", "첫 화면", "첫 실질 토큰", "개선"));
        measured.forEach((name, m) -> out.append("  %-24s %7s %7dms %9dms %7s%n".formatted(
                name,
                m.prefixEligible() ? "O" : "-",
                m.renderedMs(),
                m.substantiveMs(),
                m.deltaMs() < 0 ? "미전달" : m.deltaMs() + "ms")));
        out.append("\n  가상 시계 기준. 첫 생성 토큰 %dms + 초당 %.0f자를 가정했고, 승인 단위 판정은\n"
                .formatted(ASSUMED_TTFT_MS, ASSUMED_CHARS_PER_SECOND));
        out.append("  실제 HoldbackDelivery·OutputPreFilter 가 수행한다.\n");
        out.append("  \"미전달\"은 승인된 모델 콘텐츠가 하나도 없었던 턴이다 — 그 턴에서 사용자가\n");
        out.append("  본 것은 서버 문구뿐이고, 나머지는 교체 경로가 처리한다.\n");
        out.append("  실제 값은 운영 trace 의 first_rendered_token_ms / first_substantive_token_ms\n");
        out.append("  로 대체돼야 한다.\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        System.out.print(out);
    }
}
