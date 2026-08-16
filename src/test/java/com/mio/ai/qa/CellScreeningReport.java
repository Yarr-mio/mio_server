package com.mio.ai.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 상위 모델 후보 여럿을 <b>한 실행 안에서</b> 나란히 놓는 스크리닝 표.
 *
 * <h2>이것은 판정이 아니다</h2>
 *
 * <p>이 표의 목적은 "후보를 좁히는 것" 이다. 채택 판정은 {@link CellGoNoGo} 가 사전 등록한
 * 문턱으로만 내리고, 그 규칙은 이 표 때문에 하나도 달라지지 않는다. 특히 표본 실행에서는
 * Go/No-Go 가 여전히 {@link CellGoNoGo.Verdict#NOT_EVALUABLE} 이고, 이 표가 그 자리를 대신
 * 하지 않는다 — 표에 숫자가 있다는 것과 판정이 났다는 것은 다르다. 그래서 헤더에
 * {@link #NOT_A_VERDICT} 를 항상 찍는다.
 *
 * <h2>하나의 점수를 만들지 않는다</h2>
 *
 * <p>안전·품질·지연·비용을 가중합으로 접으면 가중치를 정한 사람의 취향이 데이터처럼 보인다.
 * 그래서 축을 전부 펼쳐 찍고({@link #appendDetail}), 탈락은 기준별로 계산해 보여주며
 * ({@link CandidateElimination}), 비용과 품질의 맞바꿈은 파레토 프론티어로 낸다.
 *
 * <h2>단가를 모르는 후보</h2>
 *
 * <p>후보의 단가를 핀하지 않았으면 원가 칸은 <b>미상</b>이다. 그렇다고 그 후보의 실행이 쓸모
 * 없어지지는 않는다 — 수용률·CBT 적합률·안전 등급·p50/p95 는 단가와 무관하게 그대로 나온다.
 * 그래서 이 표는 품질·지연 열을 항상 채우고, 원가 열만 미상으로 남긴 뒤 "비용 기준 결론을
 * 내려면 어느 후보의 단가를 먼저 핀해야 하는지" 를 마지막에 이름으로 적는다.
 */
final class CellScreeningReport {

    private static final String LINE =
            "══════════════════════════════════════════════════════════════";

    static final String NOT_A_VERDICT =
            "이 표는 후보를 좁히기 위한 스크리닝 비교다. 채택 판정이 아니다 — "
                    + "판정은 사전 등록 문턱으로 CellGoNoGo 만 내리고, 그 규칙은 그대로다.";

    /**
     * 셀 B 가 입력 안전 지표로 후보를 변별할 수 없다는 사실.
     *
     * <p>표에 미탐·HARD 하향·위기 오탐이 모든 후보에서 같은 값으로 찍히면, 읽는 사람은 그것을
     * "모델들이 똑같이 안전하다" 로 읽는다. 그게 아니라 <b>이 셀이 그 질문을 묻지 않는다</b>는
     * 뜻이다 — 셀 B 는 생성 모델만 바꾸고 탐지(SafetyL1 + InputJudge)는 고정이다.
     */
    static final String CELL_B_CANNOT_DISCRIMINATE =
            "셀 B 는 입력 안전 지표로 후보를 변별하지 않는다. 생성 모델만 바꾸고 탐지"
                    + "(SafetyL1 + InputJudge)는 전 변형 고정이라, 미탐·HARD 하향·위기 오탐은 "
                    + "구조적으로 기준선과 같은 값이 나온다. 같은 숫자를 '모든 모델이 똑같이 "
                    + "안전하다' 로 읽으면 안 된다 — 셀 B 가 대답할 수 있는 것은 생성 품질·계약 "
                    + "준수·금기 위반·지연·원가다.";

    private CellScreeningReport() {
    }

    /** 한 변형의 표 한 줄. */
    record Row(CellVariant variant, CellMetrics metrics, boolean sampled,
               boolean latencyMeasured) {

        Row(CellVariant variant, CellMetrics metrics, boolean sampled) {
            this(variant, metrics, sampled, true);
        }

        CellMetrics.Population population() {
            return metrics.modelDiscriminating();
        }

        boolean isBaseline() {
            return variant.cell() == BenchmarkCell.A;
        }
    }

    /**
     * 표를 그린다.
     *
     * @param rows  실행 순서대로. 기준선 A 를 포함해 넘기면 첫 줄로 나온다
     * @param stage 단계. {@code null} 이면 단계 개념 없이 돌린 실행이라 탈락 계산을 하지 않는다
     */
    static String render(List<Row> rows, RunIdentity identity, List<String> unpricedCandidates,
                         BenchmarkStage stage) {
        StringBuilder out = new StringBuilder();
        out.append('\n').append(LINE).append('\n');
        out.append("  상위 모델 후보 스크리닝 — 같은 실행·같은 기준선·같은 케이스\n");
        out.append(LINE).append('\n');
        out.append("  ** %s **\n".formatted(NOT_A_VERDICT));
        if (stage != null) {
            out.append("  단계: %s\n".formatted(stage.describe()));
        }
        out.append("  실행 도장 run_id %s · 세트 %s · 단가 기준 %s\n".formatted(
                identity.runId(), identity.datasetVersion(), identity.pricingAsOf()));
        if (rows.stream().anyMatch(row -> !row.latencyMeasured())) {
            out.append("  ** %s **\n".formatted(BatchQualityMode.NOT_MEASURED));
            out.append("  ** %s **\n".formatted(BatchQualityMode.PARTIAL_RANKING));
        }
        if (rows.stream().anyMatch(Row::sampled)) {
            out.append("  ** 표본 실행 — 후보를 싸게 좁히는 용도다. 안전 판정은 나오지 않는다 "
                    + "(전수 실행에서만 판정한다). **\n");
        }

        appendSummaryTable(out, rows);
        appendDetail(out, rows);
        appendElimination(out, rows, stage);
        appendPareto(out, rows);

        out.append("\n  [읽는 법]\n");
        out.append("    · 같은 셀의 서로 다른 후보끼리, 그리고 각 후보와 기준선 A 를 비교한다. "
                + "전부 같은 실행이라 비교의 전제가 성립한다.\n");
        out.append("    · 미탐·위기 오탐은 건수다. 하위 그룹 비율은 여기에도 나오지 않는다 "
                + "(minSubgroupN 미달).\n");
        out.append("    · 원가가 미상인 후보는 품질·지연만 비교한다. 미상을 0 으로 읽으면 "
                + "가장 비싼 후보가 가장 싸 보인다.\n");
        out.append("    · 지연은 품질·비용과 같은 자격의 탈락 기준이다 — Mio 는 스트리밍 제품이라 "
                + "생각이 긴 모델은 품질과 무관하게 성립하지 않는다.\n");
        appendUnpriced(out, unpricedCandidates);
        out.append(LINE).append('\n');
        return out.toString();
    }

    private static void appendSummaryTable(StringBuilder out, List<Row> rows) {
        out.append("\n  %-20s %6s %9s %9s %5s %5s %5s %8s %8s %14s%n".formatted(
                "변형", "케이스", "수용률", "CBT적합", "미탐", "위기FP", "계약", "p95", "첫토큰p95",
                "수용응답당 원가"));
        rows.forEach(row -> {
            CellMetrics.Population p = row.population();
            out.append("  %-20s %6d %9s %9s %5d %5d %5d %8d %8d %14s%n".formatted(
                    row.variant().label(), p.size(),
                    percentOf(p.acceptanceRate()), percentOf(p.cbtFitRate()),
                    p.falseNegatives(), p.crisisFalsePositives(), p.contractViolated(),
                    row.latencyMeasured() ? p.p95LatencyMs() : -1,
                    row.latencyMeasured() ? p.p95FirstSubstantiveMs() : -1,
                    CellPricingBook.format(p.costPerAcceptedResponse())));
        });
    }

    /** 축을 전부 펼친다. 표에서 잘린 값 때문에 사람이 다시 원본 리포트를 뒤지지 않게 한다. */
    private static void appendDetail(StringBuilder out, List<Row> rows) {
        out.append("\n  [후보별 상세 — 순위를 접지 않고 축을 전부 편다]\n");
        rows.forEach(row -> {
            CellMetrics.Population p = row.population();
            out.append("    %s%n".formatted(row.variant().label()));
            out.append("      안전     미탐 %d · 위기오탐 %d · 가드오탐 %d · HARD 확정 %d/%d · HARD 하향 %d%n"
                    .formatted(p.falseNegatives(), p.crisisFalsePositives(),
                            p.guardFalsePositives(), p.hardCrisisConfirmed(), p.hardCrisisTruths(),
                            p.hardCrisisDowngraded()));
            out.append("      품질     수용률 %s · CBT 적합 %s (채점가능 %d) · 계약 위반 %d/%d · 금기 위반 %d%n"
                    .formatted(percentOf(p.acceptanceRate()), percentOf(p.cbtFitRate()),
                            p.cbtScoreable(), p.contractViolated(), p.contractApplicable(),
                            p.contraindicationViolations()));
            out.append(row.latencyMeasured()
                    ? "      지연     p50 %d / p95 %d ms · 첫 실질 토큰 p50 %d / p95 %d ms%n"
                    .formatted(p.p50LatencyMs(), p.p95LatencyMs(),
                            p.p50FirstSubstantiveMs(), p.p95FirstSubstantiveMs())
                    : "      지연     %s%n".formatted(BatchQualityMode.NOT_MEASURED));
            out.append("      호출     %d건 (턴당 %.2f) · prompt %d / completion %d%n"
                    .formatted(p.llmCalls(), p.size() == 0 ? 0.0 : p.llmCalls() / (double) p.size(),
                            p.promptTokens(), p.completionTokens()));
            out.append("      비용     총 %s · 수용 응답당 %s%s%n".formatted(
                    CellPricingBook.format(p.totalCostUsd()),
                    CellPricingBook.format(p.costPerAcceptedResponse()),
                    p.costPerAcceptedResponse().isEmpty() ? "  ← 단가 미상, 0 이 아니다" : ""));
            out.append("      실패     타임아웃 %d건%n".formatted(p.timedOutCases()));
        });
    }

    /** 사전 등록한 탈락 규칙을 계산해 보여준다. 입력값을 같이 찍어 근거를 남긴다. */
    private static void appendElimination(StringBuilder out, List<Row> rows, BenchmarkStage stage) {
        if (stage == null || !CandidateElimination.hasRules(stage)) {
            return;
        }
        Optional<Row> baseline = rows.stream().filter(Row::isBaseline).findFirst();
        if (baseline.isEmpty()) {
            out.append("\n  [탈락 계산] 기준선 A 가 이번 실행에 없어 계산하지 않는다 — "
                    + "비용·수용률 기준이 기준선 대비이기 때문이다.\n");
            return;
        }
        CandidateElimination.Thresholds thresholds = CandidateElimination.thresholds(stage);
        out.append("\n  [탈락 계산 — 사전 등록 %s (%s)]\n"
                .formatted(thresholds.version(), thresholds.registeredOn()));
        out.append("  ** 이것은 '좁히는' 규칙이다. 채택 문턱(go-no-go-v1.json)은 따로이고 더 엄격하다. **\n");
        out.append("  ** 통과가 '안전하다' 는 뜻이 아니다 — 표본 실행은 어떤 안전 주장도 지지하지 않는다. **\n");
        out.append("  ** %s **\n".formatted(CELL_B_CANNOT_DISCRIMINATE));
        rows.stream().filter(row -> !row.isBaseline()).forEach(row -> {
            CandidateElimination.Verdict verdict = CandidateElimination.evaluate(thresholds,
                    row.variant(), baseline.get().population(), row.population(),
                    row.latencyMeasured());
            out.append("    %s → %s (%s)%n".formatted(row.variant().label(), verdict.outcome(),
                    verdict.reason()));
            verdict.checks().forEach(check -> out.append("      ")
                    .append(check.display()).append('\n'));
        });
        out.append("    다음 단계로 넘길 최대 후보 수: %d — 통과 후보가 더 많으면 사람이 고른다%n"
                .formatted(thresholds.keepTop()));
    }

    /** 비용/품질 맞바꿈을 가중치 없이 보여준다. */
    private static void appendPareto(StringBuilder out, List<Row> rows) {
        List<CandidateElimination.Point> points = rows.stream()
                .map(row -> new CandidateElimination.Point(row.variant().label(),
                        row.population().costPerAcceptedResponse(),
                        row.population().acceptanceRate(),
                        row.latencyMeasured() ? row.population().p95LatencyMs() : -1L))
                .toList();
        CandidateElimination.Frontier frontier = CandidateElimination.pareto(points);

        out.append("\n  [파레토 프론티어 — 원가·수용률·p95 세 축, 가중치 없음]\n");
        out.append("    프론티어: %s%n".formatted(String.join(", ", frontier.onFrontier())));
        if (frontier.dominatedBy().isEmpty()) {
            out.append("    지배된 후보 없음 — 세 축 전부에서 지는 후보가 없다\n");
        } else {
            frontier.dominatedBy().forEach((dominated, dominator) -> out.append(
                    "    %s 는 %s 에게 세 축 모두에서 진다 — 어떤 가중치로도 고를 이유가 없다%n"
                            .formatted(dominated, dominator)));
        }
        out.append("    ↑ 단가 미상 후보는 비용 축을 비교할 수 없어 지배 판정에서 빼고 프론티어에 남긴다. "
                + "모르는 것을 나쁜 것으로 접지 않는다.\n");
    }

    /**
     * 비율 칸.
     *
     * <p>{@link ReportableRate.Suppressed} 는 퍼센트를 만들 수 없으므로 그대로 "미보고" 다.
     * 표를 채우려고 여기서 분자를 꺼내 나누면 보고 하한이 관례로 되돌아간다.
     */
    private static String percentOf(ReportableRate rate) {
        return rate instanceof ReportableRate.Reported reported
                ? "%.1f%%".formatted(reported.percent())
                : "미보고";
    }

    private static void appendUnpriced(StringBuilder out, List<String> unpricedCandidates) {
        if (unpricedCandidates.isEmpty()) {
            return;
        }
        out.append("\n  [비용 기준 결론 전에 단가를 핀해야 하는 후보]\n");
        unpricedCandidates.forEach(candidate -> out.append("    · %s%n".formatted(candidate)));
        out.append("    -PcellPrices=\"<후보 ID>=<input>/<cachedInput>/<output>\" -PpricingAsOf=<YYYY-MM-DD>\n");
        out.append("    ↑ 단가를 핀하기 전까지 이 후보들의 원가 칸은 0 이 아니라 미상이며, "
                + "비용 비교에 인용할 수 없다.\n");
    }

    /** 실행 결과에서 단가 미등록 후보 이름을 모은다. 중복 없이, 선언 순서로. */
    static List<String> unpricedCandidates(List<CellRunner.Result> results) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<String> unpriced = new ArrayList<>();
        for (CellRunner.Result result : results) {
            String candidate = result.variant().frontierCandidate();
            if (candidate == null || seen.putIfAbsent(candidate, true) != null) {
                continue;
            }
            if (!result.registry().pricing().isPriced(candidate)) {
                unpriced.add(candidate);
            }
        }
        return List.copyOf(unpriced);
    }
}
