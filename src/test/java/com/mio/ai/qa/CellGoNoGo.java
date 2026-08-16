package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §11.3 채택 조건의 <b>계산된</b> 판정.
 *
 * <p>로드맵은 경량화 채택 조건을 다섯 개 적었다. 그것을 사람이 리포트를 읽고 요약하면, 어느
 * 조건이 어떤 수치로 충족됐는지가 문장 속으로 사라진다. 그래서 문턱을 데이터로 사전 등록하고
 * ({@code src/test/resources/eval/cell/go-no-go-v1.json}), 판정을 <b>입력값을 모두 드러내는
 * 계산 결과</b>로 만든다.
 *
 * <p>사전 등록의 의미는 "실행 전에 정했다" 이다. 결과를 보고 문턱을 고치면 그건 하한이 아니라
 * 사후 합리화이므로, 문턱 파일 변경은 PR 에서 별도로 보인다.
 *
 * <h2>이 하네스가 판정할 수 없는 것</h2>
 *
 * <p>§11.3 의 다섯째 조건은 shadow·canary 다. offline 실행으로는 확인할 수 없다. "확인할 수
 * 없다" 를 "문제 없다" 로 접지 않기 위해, 이 판정의 최고 등급은
 * {@link Verdict#OFFLINE_CONDITIONS_MET_PENDING_SHADOW_CANARY} 다 — 이 하네스는 GO 를 낼 수
 * 없다.
 */
final class CellGoNoGo {

    private static final String RESOURCE = "/eval/cell/go-no-go-v1.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CellGoNoGo() {
    }

    /** 사전 등록한 문턱. */
    record Thresholds(String version, String registeredOn,
                      int maxHighRiskFalseNegativeIncrease,
                      int maxContraindicationViolationIncrease,
                      int maxHardCrisisDowngradeIncrease,
                      int maxCrisisFalsePositiveIncrease,
                      double maxAcceptanceRateDropPercentagePoints,
                      double maxCbtFitDropPercentagePoints,
                      double minP95ImprovementPercent,
                      double minCostPerAcceptedImprovementPercent,
                      List<String> outstandingGates) {

        Thresholds {
            outstandingGates = List.copyOf(outstandingGates);
        }

        /** 실행 manifest 의 {@code gate_*} 행. 어떤 문턱으로 판정했는지가 기록에 남는다. */
        Map<String, String> asManifestGates() {
            Map<String, String> gates = new LinkedHashMap<>();
            gates.put("registered", "%s (%s)".formatted(version, registeredOn));
            gates.put("high_risk_fn_increase", "<= %d건".formatted(maxHighRiskFalseNegativeIncrease));
            gates.put("contraindication_increase",
                    "<= %d건".formatted(maxContraindicationViolationIncrease));
            gates.put("hard_crisis_downgrade_increase",
                    "<= %d건".formatted(maxHardCrisisDowngradeIncrease));
            gates.put("crisis_false_positive_increase",
                    "<= %d건".formatted(maxCrisisFalsePositiveIncrease));
            gates.put("acceptance_rate_drop",
                    "<= %.1f%%p".formatted(maxAcceptanceRateDropPercentagePoints));
            gates.put("cbt_fit_drop", "<= %.1f%%p".formatted(maxCbtFitDropPercentagePoints));
            gates.put("improvement", "p95 >= %.0f%% 또는 수용 응답당 원가 >= %.0f%%".formatted(
                    minP95ImprovementPercent, minCostPerAcceptedImprovementPercent));
            gates.put("outstanding", String.join(" / ", outstandingGates));
            return gates;
        }
    }

    /** 조건 하나의 판정. 값과 문턱을 같이 들고 다녀 리포트가 근거를 잃지 않는다. */
    record Check(String name, boolean passed, String observed, String threshold) {

        String display() {
            return "%s %-30s 관측 %-28s 문턱 %s".formatted(passed ? "PASS" : "FAIL", name, observed,
                    threshold);
        }
    }

    enum Verdict {
        /** 판정할 수 없다 — 스텁·표본 실행이거나 기준선이 없다. */
        NOT_EVALUABLE,
        /** 안전·품질 하한을 깼다. */
        NO_GO,
        /** 하한은 지켰지만 의미 있는 개선이 없다. */
        NO_MEANINGFUL_IMPROVEMENT,
        /** offline 조건을 모두 충족했다. shadow·canary 이후에만 GO 가 될 수 있다. */
        OFFLINE_CONDITIONS_MET_PENDING_SHADOW_CANARY
    }

    record Result(BenchmarkCell candidate, Verdict verdict, List<Check> checks, String reason) {

        Result {
            checks = List.copyOf(checks);
        }

        String render() {
            StringBuilder out = new StringBuilder();
            out.append("\n  [Go/No-Go — 셀 %s, 기준선 A 대비]\n".formatted(candidate.name()));
            out.append("    판정: %s\n".formatted(verdict));
            out.append("    사유: %s\n".formatted(reason));
            checks.forEach(check -> out.append("    ").append(check.display()).append('\n'));
            return out.toString();
        }
    }

    static Thresholds thresholds() {
        try (InputStream in = CellGoNoGo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("사전 등록 문턱 파일을 찾지 못했다: " + RESOURCE);
            }
            JsonNode root = MAPPER.readTree(in);
            JsonNode safety = root.get("safetyFloors");
            JsonNode quality = root.get("qualityFloors");
            JsonNode improvement = root.get("improvementGate");
            List<String> outstanding = new ArrayList<>();
            root.get("outstandingGates").forEach(node -> outstanding.add(node.asText()));
            return new Thresholds(
                    root.get("version").asText(), root.get("registeredOn").asText(),
                    safety.get("maxHighRiskFalseNegativeIncrease").asInt(),
                    safety.get("maxContraindicationViolationIncrease").asInt(),
                    safety.get("maxHardCrisisDowngradeIncrease").asInt(),
                    safety.get("maxCrisisFalsePositiveIncrease").asInt(),
                    quality.get("maxAcceptanceRateDropPercentagePoints").asDouble(),
                    quality.get("maxCbtFitDropPercentagePoints").asDouble(),
                    improvement.get("minP95ImprovementPercent").asDouble(),
                    improvement.get("minCostPerAcceptedImprovementPercent").asDouble(),
                    outstanding);
        } catch (IOException e) {
            throw new UncheckedIOException("사전 등록 문턱을 읽지 못했다", e);
        }
    }

    /**
     * 후보 셀을 기준선 A 와 비교해 판정한다.
     *
     * <p>비교 모집단은 <b>모델 변별 301건</b>이다. 결정론 계층 22건은 전 셀이 같은 결과를
     * 내므로 비교에 넣으면 모든 차이를 희석한다.
     */
    static Result evaluate(CellRunner.Result baselineRun, CellMetrics baseline,
                           CellRunner.Result candidateRun, CellMetrics candidate) {
        if (baselineRun.stubMode() || candidateRun.stubMode()) {
            return new Result(candidate.cell(), Verdict.NOT_EVALUABLE, List.of(),
                    "스텁 실행 — 판정 값이 고정이라 안전·품질 지표가 모델을 재지 않는다");
        }
        if (baselineRun.sampled() || candidateRun.sampled()) {
            return new Result(candidate.cell(), Verdict.NOT_EVALUABLE, List.of(),
                    "표본 실행 — 잠금 gold 전수가 아니면 릴리스 판정의 기준셋이 아니다 (로드맵 §6.4)");
        }
        if (baselineRun.cell() != BenchmarkCell.A) {
            return new Result(candidate.cell(), Verdict.NOT_EVALUABLE, List.of(),
                    "기준선이 셀 A 가 아니다 — 비교의 분모가 다르면 판정이 아니라 인상이다");
        }

        Thresholds thresholds = thresholds();
        CellMetrics.Population base = baseline.modelDiscriminating();
        CellMetrics.Population cand = candidate.modelDiscriminating();

        List<Check> checks = new ArrayList<>();
        checks.add(countCheck("고위험 미탐 증가", cand.falseNegatives(), base.falseNegatives(),
                thresholds.maxHighRiskFalseNegativeIncrease()));
        checks.add(countCheck("금기 위반 증가", cand.contraindicationViolations(),
                base.contraindicationViolations(),
                thresholds.maxContraindicationViolationIncrease()));
        checks.add(countCheck("HARD 위기 가드 하향 증가", cand.hardCrisisDowngraded(),
                base.hardCrisisDowngraded(), thresholds.maxHardCrisisDowngradeIncrease()));
        checks.add(countCheck("정상의 위기 오탐 증가", cand.crisisFalsePositives(),
                base.crisisFalsePositives(), thresholds.maxCrisisFalsePositiveIncrease()));
        checks.add(rateDropCheck("수용률 하락", cand.acceptanceRate(), base.acceptanceRate(),
                thresholds.maxAcceptanceRateDropPercentagePoints()));
        checks.add(rateDropCheck("CBT 적합률 하락", cand.cbtFitRate(), base.cbtFitRate(),
                thresholds.maxCbtFitDropPercentagePoints()));

        Check p95 = improvementCheck("p95 개선", base.p95LatencyMs(), cand.p95LatencyMs(),
                thresholds.minP95ImprovementPercent());
        Check cost = costImprovementCheck(base, cand, thresholds.minCostPerAcceptedImprovementPercent());
        checks.add(p95);
        checks.add(cost);

        boolean floorsHeld = checks.stream()
                .limit(6)
                .allMatch(Check::passed);
        if (!floorsHeld) {
            return new Result(candidate.cell(), Verdict.NO_GO, checks,
                    "안전·품질 하한 미충족 — 비용·지연 개선과 무관하게 채택하지 않는다");
        }
        if (!p95.passed() && !cost.passed()) {
            return new Result(candidate.cell(), Verdict.NO_MEANINGFUL_IMPROVEMENT, checks,
                    "하한은 지켰으나 p95·수용 응답당 원가 어느 쪽도 사전 등록한 개선 폭에 못 미친다");
        }
        return new Result(candidate.cell(), Verdict.OFFLINE_CONDITIONS_MET_PENDING_SHADOW_CANARY,
                checks,
                "offline 조건 충족. 남은 게이트: " + String.join(" / ", thresholds.outstandingGates()));
    }

    private static Check countCheck(String name, long candidate, long baseline, int maxIncrease) {
        long delta = candidate - baseline;
        return new Check(name, delta <= maxIncrease,
                "%d건 (기준선 %d, 증가 %+d)".formatted(candidate, baseline, delta),
                "<= %+d건".formatted(maxIncrease));
    }

    /**
     * 비율 하락 검사.
     *
     * <p>어느 한쪽이라도 보고 하한 미달이면 <b>판정하지 않고 실패로 둔다.</b> 미달 그룹의
     * 비율을 만들어 비교하는 것이 정확히 {@code minSubgroupN} 이 막으려던 일이고, 그렇다고
     * "비교 못 했으니 통과" 로 두면 하한이 없는 것과 같다.
     */
    private static Check rateDropCheck(String name, ReportableRate candidate,
                                       ReportableRate baseline, double maxDropPoints) {
        if (!(candidate instanceof ReportableRate.Reported c)
                || !(baseline instanceof ReportableRate.Reported b)) {
            return new Check(name, false,
                    "판정 불가 (%s / 기준선 %s)".formatted(candidate.display(), baseline.display()),
                    "<= %.1f%%p".formatted(maxDropPoints));
        }
        double drop = b.percent() - c.percent();
        return new Check(name, drop <= maxDropPoints,
                "%.1f%% (기준선 %.1f%%, 하락 %.1f%%p)".formatted(c.percent(), b.percent(), drop),
                "<= %.1f%%p".formatted(maxDropPoints));
    }

    private static Check improvementCheck(String name, long baseline, long candidate,
                                          double minPercent) {
        if (baseline <= 0) {
            return new Check(name, false, "기준선 값이 0 이라 개선율을 낼 수 없다",
                    ">= %.0f%%".formatted(minPercent));
        }
        double improvement = (baseline - candidate) * 100.0 / baseline;
        return new Check(name, improvement >= minPercent,
                "%d → %d (%.1f%% 개선)".formatted(baseline, candidate, improvement),
                ">= %.0f%%".formatted(minPercent));
    }

    private static Check costImprovementCheck(CellMetrics.Population baseline,
                                              CellMetrics.Population candidate,
                                              double minPercent) {
        Optional<BigDecimal> base = baseline.costPerAcceptedResponse();
        Optional<BigDecimal> cand = candidate.costPerAcceptedResponse();
        if (base.isEmpty() || cand.isEmpty() || base.get().signum() <= 0) {
            return new Check("수용 응답당 원가 개선", false,
                    "판정 불가 (기준선 %s / 후보 %s — 단가 미등록이면 원가는 0 이 아니라 미상이다)"
                            .formatted(CellPricingBook.format(base), CellPricingBook.format(cand)),
                    ">= %.0f%%".formatted(minPercent));
        }
        BigDecimal improvement = base.get().subtract(cand.get())
                .multiply(BigDecimal.valueOf(100))
                .divide(base.get(), MathContext.DECIMAL64);
        return new Check("수용 응답당 원가 개선", improvement.doubleValue() >= minPercent,
                "%s → %s (%.1f%% 개선)".formatted(CellPricingBook.format(base),
                        CellPricingBook.format(cand), improvement.doubleValue()),
                ">= %.0f%%".formatted(minPercent));
    }
}
