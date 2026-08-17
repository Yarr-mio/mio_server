package com.mio.ai.qa;

import com.mio.ai.plan.ResponseAct;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계약 준수 실측 리포트와 실행 기록 (이슈 #305).
 *
 * <p>{@link CellReport} 를 그대로 쓰지 않는 이유는 하나다 — 저 리포트는 <b>셀 비교</b>를 위한
 * 것이라 안전 미탐률·CBT·원가가 본문의 대부분을 차지하고, 계약은 한 줄이다. 이 실행이 답해야
 * 하는 물음은 그 한 줄을 행위별로 펼친 것이므로 본문 구성이 다르다.
 *
 * <p>대신 <b>수치의 출처는 전부 같다.</b> {@link ContractComplianceMetrics} 는
 * {@link CellCaseOutcome} 의 {@code contract}·{@code contractViolations} 만 읽고, 그 값은
 * {@link CellRunner} 가 프로덕션 {@code ResponseContractValidator} 로 채운 것이다. 계약을
 * 다르게 재는 두 번째 하네스는 없다.
 */
final class ContractComplianceReport {

    private static final String LINE =
            "══════════════════════════════════════════════════════════════";

    private ContractComplianceReport() {
    }

    // ── 한쪽 팔 ────────────────────────────────────────────────────

    static String render(CellRunner.Result result, ContractComplianceMetrics metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(LINE).append('\n');
        sb.append("  계약 준수 실측 — ").append(metrics.arm().label()).append('\n');
        sb.append("  실행 도장: run_id ").append(result.identity().runId())
                .append(" · 세트 ").append(ContractEvalSet.VERSION).append('\n');
        sb.append("  ↑ 이 수치는 같은 run_id 도장을 가진 결과와만 비교할 수 있다\n");
        sb.append(LINE).append('\n');
        sb.append("  소요 %s  ·  세트 %d건%n".formatted(elapsed(result), metrics.cases()));
        if (result.stubMode()) {
            sb.append("  ⚠ 스텁 실행 — 모델이 쓴 문장이 아니다. 판정에 쓸 수 없다\n");
        }

        sb.append("\n  [모집단]\n");
        sb.append("    계약 적용       %4d건 / %d건%n".formatted(metrics.applicable(), metrics.cases()));
        sb.append("    계약 밖         %4d건  (위기 라우팅 %d · 보안 거절 %d · 계획 밖 %d)%n"
                .formatted(metrics.notApplicable(), metrics.crisisRouted(),
                        metrics.securityRefusal(), metrics.unplanned()));
        sb.append("    미검사          %4d건  ← 계약은 있으나 검사 지점이 없는 전달%n"
                .formatted(metrics.unchecked()));
        sb.append("    생성 호출       %4d건  ·  외부 실패 %d건 · 빈 응답 %d건%n"
                .formatted(metrics.generationCalled(), metrics.externalFailures(),
                        metrics.emptyResponses()));

        sb.append("\n  [응답 행위별 계약 위반율]\n");
        for (ResponseAct act : ContractEvalSet.CONTRACT_ACTS) {
            ContractComplianceMetrics.ActStats stats = metrics.byAct().get(act);
            sb.append("    %-22s %s  (위반 %d건)%n".formatted(
                    act.name(), stats.violationRate().display(), stats.violated()));
        }
        sb.append("    %-22s %s  (위반 %d건)%n".formatted(
                "── 총계", metrics.violationRate().display(), metrics.violated()));
        sb.append("      ↑ 하한 미달 행위는 비율이 계산되지 않는다 — 건수만 인용한다\n");

        sb.append("\n  [위반 유형 분포]\n");
        appendTypes(sb, metrics.violationTypes());

        sb.append("\n  [응답 길이·질문 수 분포 — 계약이 적용된 턴]\n");
        sb.append("    %-22s %4s   %-26s %s%n"
                .formatted("행위", "n", "문장 평균/p50/p90/최대", "질문 평균/p50/p90/최대"));
        for (ResponseAct act : ContractEvalSet.CONTRACT_ACTS) {
            appendShape(sb, act.name(), metrics.byAct().get(act).shape());
        }
        appendShape(sb, "── 총계", metrics.shape());
        sb.append("      ↑ 문장·질문 계수기는 계약 검사가 쓰는 것과 같다 (ResponseContractValidator)\n");

        sb.append(LINE).append('\n');
        return sb.toString();
    }

    private static void appendTypes(StringBuilder sb, Map<String, Integer> types) {
        if (types.isEmpty()) {
            sb.append("    위반 없음\n");
            return;
        }
        ContractComplianceMetrics.sortedByCount(types)
                .forEach(e -> sb.append("    %-22s %d건%n".formatted(e.getKey(), e.getValue())));
    }

    private static void appendShape(StringBuilder sb, String label,
                                    ContractComplianceMetrics.Shape shape) {
        sb.append("    %-22s %4d   %5.2f / %d / %d / %-10d %5.2f / %d / %d / %d%n".formatted(
                label, shape.n(),
                shape.meanSentences(), shape.p50Sentences(), shape.p90Sentences(),
                shape.maxSentences(),
                shape.meanQuestions(), shape.p50Questions(), shape.p90Questions(),
                shape.maxQuestions()));
    }

    // ── A/B ───────────────────────────────────────────────────────

    /**
     * 계약 지시 유무 비교.
     *
     * <p><b>비율 차이는 두 팔 모두 하한을 넘었을 때만 낸다.</b> 한쪽이라도 미달이면 차이는
     * 계산하지 않고 건수만 나란히 적는다 — 미달 그룹의 비율을 못 내게 해 놓고 그 비율의 차이를
     * 내면 하한이 아무것도 막지 못한다.
     */
    static String renderComparison(ContractComplianceMetrics with, ContractComplianceMetrics without) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(LINE).append('\n');
        sb.append("  A/B — [응답 계약] 프롬프트 블록의 효과\n");
        sb.append("  같은 입력·같은 계획·같은 채점. 프롬프트의 계약 블록만 다르다\n");
        sb.append(LINE).append('\n');

        sb.append("\n  [계약 위반]\n");
        sb.append("    %-22s %-34s %-34s %s%n"
                .formatted("행위", "지시 있음", "지시 없음", "차이"));
        for (ResponseAct act : ContractEvalSet.CONTRACT_ACTS) {
            appendDelta(sb, act.name(), with.byAct().get(act).violationRate(),
                    with.byAct().get(act).violated(), with.byAct().get(act).applicable(),
                    without.byAct().get(act).violationRate(),
                    without.byAct().get(act).violated(), without.byAct().get(act).applicable());
        }
        appendDelta(sb, "── 총계", with.violationRate(), with.violated(), with.applicable(),
                without.violationRate(), without.violated(), without.applicable());

        sb.append("\n  [위반 유형 — 지시 있음 → 없음]\n");
        Map<String, int[]> merged = new LinkedHashMap<>();
        with.violationTypes().forEach((k, v) -> merged.computeIfAbsent(k, x -> new int[2])[0] = v);
        without.violationTypes().forEach((k, v) -> merged.computeIfAbsent(k, x -> new int[2])[1] = v);
        if (merged.isEmpty()) {
            sb.append("    양쪽 팔 모두 위반 없음\n");
        } else {
            merged.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1],
                            a.getValue()[0] + a.getValue()[1]))
                    .forEach(e -> sb.append("    %-22s %3d건 → %3d건%n"
                            .formatted(e.getKey(), e.getValue()[0], e.getValue()[1])));
        }

        sb.append("\n  [응답 길이·질문 수 — 지시 있음 → 없음]\n");
        sb.append("    %-22s %s%n".formatted("구간", "문장 평균 · p50 · 최대   |   질문 평균 · p50 · 최대"));
        for (ResponseAct act : ContractEvalSet.CONTRACT_ACTS) {
            appendShapeDelta(sb, act.name(), with.byAct().get(act).shape(),
                    without.byAct().get(act).shape());
        }
        appendShapeDelta(sb, "── 총계", with.shape(), without.shape());

        sb.append("""

  [이 A/B 가 답하는 것과 답하지 못하는 것]
    답한다   같은 입력에서 계약 블록을 빼면 결정론 계약 검사에 걸리는 빈도가 어떻게 변하는가.
             그리고 응답의 문장 수·질문 수 분포가 어떻게 변하는가.
    답 못 한다 응답이 더 좋아졌는가·나빠졌는가. 공감·도움도는 사람 라벨과 독립 reference judge
             없이 재지 않는다 (로드맵 §11.3 '단일 LLM judge 점수만으로 고르지 않는다').
             계약 검사는 세는 검사이지 의미 판단이 아니므로, 위반율이 낮다는 것이 응답이
             적절하다는 뜻은 아니다.
    답 못 한다 두 팔의 계약 적용 모집단이 정확히 같지는 않다. 계획은 InputJudge 판정에서 나오고
             그 호출은 팔마다 따로 일어나므로, 같은 케이스가 팔마다 다른 행위로 계획될 수 있다.
             행위별 n 이 팔마다 다르면 그 차이도 함께 읽어야 한다.
""");
        sb.append(LINE).append('\n');
        return sb.toString();
    }

    private static void appendDelta(StringBuilder sb, String label,
                                    ReportableRate withRate, int withViolated, int withN,
                                    ReportableRate withoutRate, int withoutViolated, int withoutN) {
        String delta;
        if (withRate instanceof ReportableRate.Reported a
                && withoutRate instanceof ReportableRate.Reported b) {
            delta = "%+.1f%%p (없음 − 있음)".formatted(b.percent() - a.percent());
        } else {
            delta = "비율 비교 불가 (한쪽 이상 하한 미달)";
        }
        sb.append("    %-22s %-34s %-34s %s%n".formatted(label,
                "위반 %d/%d".formatted(withViolated, withN),
                "위반 %d/%d".formatted(withoutViolated, withoutN), delta));
    }

    private static void appendShapeDelta(StringBuilder sb, String label,
                                         ContractComplianceMetrics.Shape with,
                                         ContractComplianceMetrics.Shape without) {
        sb.append("    %-22s %.2f→%.2f · %d→%d · %d→%d   |   %.2f→%.2f · %d→%d · %d→%d%n".formatted(
                label,
                with.meanSentences(), without.meanSentences(),
                with.p50Sentences(), without.p50Sentences(),
                with.maxSentences(), without.maxSentences(),
                with.meanQuestions(), without.meanQuestions(),
                with.p50Questions(), without.p50Questions(),
                with.maxQuestions(), without.maxQuestions()));
    }

    // ── 아카이브 ───────────────────────────────────────────────────

    /** 실행 기록을 남긴다. 스텁 실행은 기록하지 않는다 — 셀 벤치마크와 같은 규칙이다. */
    static Path archive(CellRunner.Result result, ContractComplianceMetrics metrics, String report) {
        requireRealRun(result);
        return EvalRunArchive.write("contract-compliance-%s".formatted(metrics.arm().fileToken()),
                manifest(result, metrics, extraFor(result, metrics)), report);
    }

    /** A/B 비교 자체의 실행 기록. 두 팔의 수치가 한 파일에서 맞대어진다. */
    static Path archiveComparison(CellRunner.Result withRun, ContractComplianceMetrics with,
                                  ContractComplianceMetrics without, String report) {
        requireRealRun(withRun);
        Map<String, String> extra = extraFor(withRun, with);
        extra.put("ab_arms", "%s / %s".formatted(with.arm().label(), without.arm().label()));
        extra.put("ab_applicable", "있음 %d / 없음 %d".formatted(with.applicable(), without.applicable()));
        extra.put("ab_violated", "있음 %d / 없음 %d".formatted(with.violated(), without.violated()));
        extra.put("ab_rate_with", with.violationRate().display());
        extra.put("ab_rate_without", without.violationRate().display());
        return EvalRunArchive.write("contract-compliance-ab",
                manifest(withRun, with, extra), report);
    }

    private static void requireRealRun(CellRunner.Result result) {
        if (result.stubMode()) {
            throw new IllegalStateException(
                    "스텁 실행은 아카이브를 남기지 않는다 — 모델이 쓰지 않은 문장으로 낸 계약 수치다");
        }
    }

    static EvalRunManifest manifest(CellRunner.Result result, ContractComplianceMetrics metrics,
                                    Map<String, String> extra) {
        return new EvalRunManifest(
                SCOPE,
                "계약 준수 실측 [%s]".formatted(metrics.arm().label()),
                ContractEvalSet.VERSION,
                EvalRunManifest.DatasetSplit.DEV_GOLD,
                result.population(),
                ContractEvalSet.LABEL_GUIDE,
                ContractEvalSet.DATA_RIGHTS.asManifestDataRights(),
                ContractEvalSet.tuningExposure(),
                result.registry().manifestModels(),
                EvalRunManifest.UNVERSIONED,
                CellReport.policyVersion(),
                result.registry().pricing().pricingAsOf(),
                String.valueOf(result.registry().seed()),
                COMMAND,
                Map.of("contract_violation_rate",
                        "행위별·총계 모두 minSubgroupN=%d 미만이면 미보고"
                                .formatted(LockedEvalSet.REPORTING.minSubgroupN())),
                extra);
    }

    private static Map<String, String> extraFor(CellRunner.Result result,
                                                ContractComplianceMetrics metrics) {
        Map<String, String> extra = new LinkedHashMap<>(ContractEvalSet.manifestFields());
        extra.put("run_id", result.identity().runId().toString());
        extra.put("contract_arm", metrics.arm().label());
        extra.put("contract_applicable", String.valueOf(metrics.applicable()));
        extra.put("contract_violated", String.valueOf(metrics.violated()));
        extra.put("contract_not_applicable", String.valueOf(metrics.notApplicable()));
        extra.put("contract_unchecked", String.valueOf(metrics.unchecked()));
        extra.put("crisis_routed", String.valueOf(metrics.crisisRouted()));
        extra.put("unplanned_turns", String.valueOf(metrics.unplanned()));
        extra.put("empty_responses", String.valueOf(metrics.emptyResponses()));
        extra.put("external_failure_calls", String.valueOf(metrics.externalFailures()));
        extra.putAll(LockedEvalSet.REPORTING.asManifestFields());
        for (ResponseAct act : ContractEvalSet.CONTRACT_ACTS) {
            ContractComplianceMetrics.ActStats stats = metrics.byAct().get(act);
            extra.put("act_" + act.name().toLowerCase(java.util.Locale.ROOT),
                    "%s (위반 %d/%d)".formatted(stats.violationRate().display(),
                            stats.violated(), stats.applicable()));
        }
        extra.put("violation_types", metrics.violationTypes().isEmpty()
                ? "없음"
                : ContractComplianceMetrics.sortedByCount(metrics.violationTypes()).stream()
                        .map(e -> "%s=%d".formatted(e.getKey(), e.getValue()))
                        .reduce((a, b) -> a + " " + b).orElse("없음"));
        extra.put("response_shape",
                "문장 평균 %.2f p50 %d 최대 %d · 질문 평균 %.2f p50 %d 최대 %d".formatted(
                        metrics.shape().meanSentences(), metrics.shape().p50Sentences(),
                        metrics.shape().maxSentences(), metrics.shape().meanQuestions(),
                        metrics.shape().p50Questions(), metrics.shape().maxQuestions()));
        extra.put("elapsed", elapsed(result));
        extra.put("dataset_purpose", ContractEvalSet.purpose());
        return extra;
    }

    /** 이 실행이 무엇을 재구성했는지. 셀 벤치마크와 같은 경로를 돌되 세트만 다르다. */
    static final String SCOPE = "contract compliance (dev-gold) — "
            + CellRunner.SCOPE.substring(CellRunner.SCOPE.indexOf('('));

    static final String COMMAND = "./gradlew test -PllmTests "
            + "--tests \"com.mio.ai.qa.ContractComplianceLlmTest\"";

    private static String elapsed(CellRunner.Result result) {
        long seconds = result.elapsed().toSeconds();
        return "%d분 %d초".formatted(seconds / 60, seconds % 60);
    }

    /** 리포트 전체 — 두 팔과 비교를 한 번에 출력한다. */
    static String renderAll(CellRunner.Result withRun, ContractComplianceMetrics with,
                            CellRunner.Result withoutRun, ContractComplianceMetrics without) {
        return String.join("", List.of(
                render(withRun, with), render(withoutRun, without),
                renderComparison(with, without)));
    }
}
