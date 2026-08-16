package com.mio.ai.qa;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 셀 C 의 운영 경로가 셀 A 와 같은지 검사한다.
 *
 * <h2>단언하는 것 — 구성의 동일성</h2>
 *
 * <p>셀 C 의 가설("운영비 증가 없이")이 성립하려면 온라인 경로가 A 와 같아야 한다. 그
 * "같음" 을 확인할 수 있는 형태로 좁히면 다음 둘이다.
 *
 * <ol>
 *   <li>온라인 역할별 모델 매핑이 A 와 같다 — 하나라도 다르면 그건 셀 C 가 아니라 셀 B 다.</li>
 *   <li>온라인 원장에 {@link CellModelRole#OFFLINE_COMPONENT} 태그 호출이 0건이다 —
 *       reference judge 가 운영 턴에 끼어들면 여기서 잡힌다.</li>
 * </ol>
 *
 * <p>둘 다 실행 결과와 무관하게 결정되는 값이라 단언해도 흔들리지 않는다.
 *
 * <h2>단언하지 않는 것 — 수치의 동일성</h2>
 *
 * <p>이전 주석은 "턴당 원가·p95 가 A 와 같아야 하고 다르면 오염" 이라고 적었지만, 그것은
 * 단언할 수 없는 명제다. 같은 모델·같은 프롬프트라도 샘플링 때문에 completion 토큰이 달라지고,
 * 생성 결과가 달라지면 pre-filter·계약 검사 통과 여부가 달라져 OutputJudge 호출 <b>횟수</b>
 * 자체가 달라진다. 실 LLM 실행에서 두 셀의 원가·p95 가 정확히 같은 일은 일어나지 않는다.
 * 그것을 단언으로 걸면 검사는 오염이 아니라 샘플링을 잡아 매번 깨진다.
 *
 * <p>그래서 수치 차이는 <b>신호</b>로만 낸다 — 구성이 같은데 차이가 크면 사람이 본다.
 * 사람이 보라는 기준선으로 {@link #SIGNAL_THRESHOLD_PERCENT} 를 같이 찍는다.
 */
final class CellParity {

    /** 구성이 같은데 이 폭을 넘게 갈리면 사람이 들여다보라는 표시. 단언 문턱이 아니다. */
    static final double SIGNAL_THRESHOLD_PERCENT = 25.0;

    private CellParity() {
    }

    /**
     * @param violations 비면 통과. 하나라도 있으면 셀 C 의 운영 경로가 A 와 다르다
     * @param signals    단언 대상이 아닌 관측 — 사람이 읽는 용도
     */
    record Result(List<String> violations, List<String> signals) {

        Result {
            violations = List.copyOf(violations);
            signals = List.copyOf(signals);
        }

        boolean held() {
            return violations.isEmpty();
        }

        String render() {
            StringBuilder out = new StringBuilder();
            out.append("\n  [A==C 운영 경로 동일성]\n");
            out.append("    구성 동일성: %s\n".formatted(held() ? "PASS" : "FAIL"));
            violations.forEach(v -> out.append("      FAIL ").append(v).append('\n'));
            signals.forEach(s -> out.append("      신호 ").append(s).append('\n'));
            out.append("    ↑ 수치(원가·p95) 동일성은 단언하지 않는다 — 같은 모델이라도 샘플링으로 "
                    + "completion 토큰과 OutputJudge 발화 횟수가 달라진다\n");
            return out.toString();
        }
    }

    static Result check(CellRunner.Result baseline, CellMetrics baselineMetrics,
                        CellRunner.Result cellC, CellMetrics cellCMetrics) {
        List<String> violations = new ArrayList<>();
        List<String> signals = new ArrayList<>();

        Map<String, String> baseOnline = baseline.registry().componentToModel();
        Map<String, String> cOnline = cellC.registry().componentToModel();
        if (!baseOnline.equals(cOnline)) {
            violations.add("온라인 역할별 모델이 A 와 다르다: A=%s / C=%s — 이러면 셀 C 가 아니라 셀 B 다"
                    .formatted(baseOnline, cOnline));
        }

        long offlineInOnlineLedger = cellC.ledger().calls().stream()
                .filter(call -> CellModelRole.OFFLINE_COMPONENT.equals(call.component()))
                .count();
        if (offlineInOnlineLedger > 0) {
            violations.add("온라인 원장에 offline reference 호출이 %d건 섞였다 — 셀 C 의 전제가 깨졌다"
                    .formatted(offlineInOnlineLedger));
        }
        if (cellC.registry().has(CellModelRole.REFERENCE_JUDGE)
                && cOnline.containsValue(cellC.registry().modelFor(CellModelRole.REFERENCE_JUDGE))) {
            violations.add("reference judge 모델이 온라인 역할에도 배정됐다 — 운영비 증가 없음이 성립하지 않는다");
        }

        signals.add(latencySignal(baselineMetrics, cellCMetrics));
        signals.add(costSignal(baselineMetrics, cellCMetrics));
        return new Result(violations, signals);
    }

    private static String latencySignal(CellMetrics baseline, CellMetrics candidate) {
        long base = baseline.modelDiscriminating().p95LatencyMs();
        long cand = candidate.modelDiscriminating().p95LatencyMs();
        if (base <= 0) {
            return "p95 기준선이 0 이라 차이를 낼 수 없다";
        }
        double delta = (cand - base) * 100.0 / base;
        return "p95 %d → %d ms (%+.1f%%, 주목 기준 ±%.0f%%)"
                .formatted(base, cand, delta, SIGNAL_THRESHOLD_PERCENT);
    }

    private static String costSignal(CellMetrics baseline, CellMetrics candidate) {
        Optional<BigDecimal> base = baseline.modelDiscriminating().costPerAcceptedResponse();
        Optional<BigDecimal> cand = candidate.modelDiscriminating().costPerAcceptedResponse();
        if (base.isEmpty() || cand.isEmpty() || base.get().signum() <= 0) {
            return "수용 응답당 원가 %s → %s — 단가 미상이라 차이를 낼 수 없다 (0 이 아니다)"
                    .formatted(CellPricingBook.format(base), CellPricingBook.format(cand));
        }
        BigDecimal delta = cand.get().subtract(base.get())
                .multiply(BigDecimal.valueOf(100))
                .divide(base.get(), MathContext.DECIMAL64);
        return "수용 응답당 원가 %s → %s (%+.1f%%, 주목 기준 ±%.0f%%)".formatted(
                CellPricingBook.format(base), CellPricingBook.format(cand),
                delta.doubleValue(), SIGNAL_THRESHOLD_PERCENT);
    }
}
