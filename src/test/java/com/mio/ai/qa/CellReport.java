package com.mio.ai.qa;

import com.mio.ai.qa.CellCaseOutcome.Acceptance;
import com.mio.ai.qa.CellCaseOutcome.SafetyGrade;
import com.mio.ai.qa.CellMetrics.Population;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 셀 실행 결과의 사람이 읽는 표현과 실행 아카이브.
 *
 * <p>리포트는 두 가지를 절대 하지 않는다.
 *
 * <ul>
 *   <li><b>모델 변별 모집단과 결정론 계층을 합친 헤드라인 숫자를 만들지 않는다.</b> 두 표를
 *       나란히 찍고, 합계 행을 두지 않는다.</li>
 *   <li><b>잠금 케이스 본문을 싣지 않는다.</b> 실패는 ID 로만 남는다. 본문을 실으면 이
 *       아카이브 파일이 저장소 안의 잠금 세트 사본이 되고, 오염 스캐너가 그것을 유출로
 *       잡는다 — 설계대로 잡히는 것이지 오탐이 아니다.</li>
 * </ul>
 */
final class CellReport {

    private static final String LINE =
            "══════════════════════════════════════════════════════════════";

    private CellReport() {
    }

    static String render(CellRunner.Result result, CellMetrics metrics) {
        StringBuilder out = new StringBuilder();
        out.append('\n').append(LINE).append('\n');
        out.append("  셀 %s — %s\n".formatted(result.variant().label(), result.cell().label()));
        out.append("  가설: %s\n".formatted(result.cell().hypothesis()));
        if (result.variant().isScreeningVariant()) {
            out.append("  상위 모델 후보: %s (후보 스크리닝 변형)\n"
                    .formatted(result.variant().frontierCandidate()));
        }
        out.append("  실행 도장: run_id %s · 세트 %s\n".formatted(
                result.identity().runId(), result.identity().datasetVersion()));
        out.append("  ↑ 이 수치는 같은 run_id 도장을 가진 결과와만 비교할 수 있다\n");
        out.append(LINE).append('\n');

        if (result.stubMode()) {
            out.append("  ** 스텁 실행 — 판정 값이 고정이다. 안전·품질 지표를 주장할 수 없고 "
                    + "아카이브·Go/No-Go 도 막힌다. **\n");
        }
        if (result.sampled()) {
            out.append("  ** 표본 실행 %d/%d 건 — 잠금 gold 전수가 아니므로 릴리스 판정에 쓸 수 없다. **\n"
                    .formatted(result.population(), LockedEvalSet.CASES.size()));
        }
        out.append("  소요 %d분 %d초  ·  단가 기준 %s\n".formatted(
                metrics.elapsed().toMinutes(), metrics.elapsed().toSecondsPart(),
                result.registry().pricing().pricingAsOf()));

        appendModels(out, result);
        appendPopulation(out, metrics.modelDiscriminating(), result);
        appendPopulation(out, metrics.deterministicLayer(), result);
        appendRates(out, "축별 미탐률 (모델 변별 모집단)", metrics.axisSafetyRates());
        appendRates(out, "하위 그룹 미탐률 (모델 변별 모집단)", metrics.subgroupSafetyRates());
        appendReliability(out, metrics);
        appendFailures(out, result, metrics);
        result.referenceReview().ifPresent(review -> out.append(review.render()));
        out.append(LINE).append('\n');
        return out.toString();
    }

    private static void appendModels(StringBuilder out, CellRunner.Result result) {
        out.append("\n  [핀한 모델]\n");
        result.registry().manifestModels().forEach((role, model) -> out.append(
                "    %-16s %-28s %s%s%n".formatted(role, model,
                        result.registry().pinSources().get(role),
                        result.registry().pricing().isPriced(model) ? "" : "  ← 단가 미등록")));
    }

    private static void appendPopulation(StringBuilder out, Population population,
                                         CellRunner.Result result) {
        out.append("\n  [%s — %d건]\n".formatted(population.name(), population.size()));
        if (population.size() == 0) {
            out.append("    (해당 없음)\n");
            return;
        }
        out.append("    등급 분포        ");
        for (SafetyGrade grade : SafetyGrade.values()) {
            out.append("%s=%d ".formatted(grade, population.grades().get(grade)));
        }
        out.append("\n");
        out.append("    미탐률           %s%n".formatted(population.falseNegativeRate().display()));
        out.append("    HARD 위기 확정률 %s%n"
                .formatted(population.hardCrisisConfirmationRate().display()));
        out.append("    HARD → 가드 하향 %d건  ← §11.3 채택 조건 2%n"
                .formatted(population.hardCrisisDowngraded()));
        out.append("    위기 오탐        %d건 / 가드 오탐 %d건%n"
                .formatted(population.crisisFalsePositives(), population.guardFalsePositives()));
        out.append("    CBT 개입 적합률  %s  (채점 가능 %d건)%n".formatted(
                population.cbtFitRate().display(), population.cbtScoreable()));
        out.append("    계약 위반율      %s  (금기 위반 %d건)%n".formatted(
                population.contractViolationRate().display(),
                population.contraindicationViolations()));
        out.append("    공감·도움도      %s%n".formatted(CellMetrics.EMPATHY_NOT_MEASURED));
        out.append("    수용률           %s%n".formatted(population.acceptanceRate().display()));
        appendAcceptance(out, population);
        if (result.latencyMeasured()) {
            out.append("    지연 p50/p95     %d ms / %d ms%n"
                    .formatted(population.p50LatencyMs(), population.p95LatencyMs()));
            out.append("    첫 실질 토큰     p50 %d ms / p95 %d ms%n".formatted(
                    population.p50FirstSubstantiveMs(), population.p95FirstSubstantiveMs()));
        } else {
            out.append("    지연 p50/p95     %s%n".formatted(BatchQualityMode.NOT_MEASURED));
            out.append("    첫 실질 토큰     %s%n".formatted(BatchQualityMode.NOT_MEASURED));
        }
        out.append("    LLM 호출         %d건 (턴당 %.2f)%n".formatted(
                population.llmCalls(), population.llmCalls() / (double) population.size()));
        // 두 문자열을 + 로 잇고 .formatted 를 붙이면 뒤쪽 리터럴에만 적용된다. 그래서 앞
        // 절반이 %d 그대로 인쇄됐다 — 1단계 실행 리포트 전체가 그 상태로 아카이브에 남았다.
        out.append(("      역할별        InputJudge %d · 생성 %d · escalation %d · OutputJudge %d"
                + " · CBT 분류 %d%n")
                .formatted(population.inputJudgeCalls(), population.generationCalls(),
                        population.escalations(), population.outputJudgeCalls(),
                        population.cbtClassifierCalls()));
        // append(String) 에 %n 을 넣으면 줄바꿈이 아니라 '%n' 두 글자가 인쇄된다.
        out.append("      ↑ InputJudge 를 부르지 않은 턴은 룰 레이어가 결정한 것이라 "
                + "판정 모델이 셀을 변별하지 않는다\n");
        out.append("      ↑ CBT 분류는 프로덕션이 매 턴 부르는 실호출이다 — 전 셀 공통이지만 "
                + "빼면 턴당 원가가 프로덕션보다 낮게 나온다\n");
        out.append("    케이스 타임아웃  %d건  ← 셀을 중단시키지 않고 실패로 기록한 건수%n"
                .formatted(population.timedOutCases()));
        out.append("    토큰             prompt %d / completion %d%n".formatted(
                population.promptTokens(), population.completionTokens()));
        out.append("    총 원가          %s%n"
                .formatted(CellPricingBook.format(population.totalCostUsd())));
        out.append("    수용 응답당 원가 %s%n"
                .formatted(CellPricingBook.format(population.costPerAcceptedResponse())));
        if (result.stubMode()) {
            out.append("      ↑ 스텁 토큰은 실측이 아니라 문자 기반 추정이다 (오차 %.0f~%.0f%%)%n"
                    .formatted(CellTokenEstimator.LOWER_MULTIPLIER * 100,
                            CellTokenEstimator.UPPER_MULTIPLIER * 100));
        }
    }

    private static void appendAcceptance(StringBuilder out, Population population) {
        out.append("      거부 사유      ");
        population.acceptance().forEach((reason, count) -> {
            if (reason != Acceptance.ACCEPTED) {
                out.append("%s=%d ".formatted(reason, count));
            }
        });
        out.append("\n");
    }

    private static void appendRates(StringBuilder out, String title,
                                    Map<String, ReportableRate> rates) {
        out.append("\n  [%s]\n".formatted(title));
        rates.forEach((group, rate) -> out.append("    %-24s %s%n".formatted(group, rate.display())));
    }

    private static void appendReliability(StringBuilder out, CellMetrics metrics) {
        out.append("\n  [외부 의존성·집계 신뢰도]\n");
        out.append("    외부 호출 실패   %d건%n".formatted(metrics.externalFailureCalls()));
        out.append("    사용량 미수신    %d건  ← 0 토큰이 아니라 '모름'%n"
                .formatted(metrics.usageMissingCalls()));
        out.append("    단가 미등록 호출 %d건%s%n".formatted(metrics.unpricedCalls(),
                metrics.unpricedModels().isEmpty() ? ""
                        : " (모델: " + String.join(", ", metrics.unpricedModels()) + ")"));
    }

    private static void appendFailures(StringBuilder out, CellRunner.Result result,
                                       CellMetrics metrics) {
        List<String> ids = metrics.failureCaseIds(result.outcomes());
        out.append("\n  [실패 케이스 ID — %d건]\n".formatted(ids.size()));
        out.append("    (본문은 남기지 않는다. 잠금 세트 오염 방지 — LockedEvalContaminationGuardTest)\n");
        for (int i = 0; i < ids.size(); i += 4) {
            out.append("    ").append(String.join("  ",
                    ids.subList(i, Math.min(i + 4, ids.size())))).append('\n');
        }
    }

    // ── 아카이브 ──────────────────────────────────────────────────

    /**
     * 실행 아카이브를 남긴다.
     *
     * <p>스텁 실행은 남기지 않는다. 판정 값이 고정인 실행 기록이 저장소에 남으면, 나중에
     * 파일 이름과 셀 이름만 보고 "A~E 를 돌린 기록이 있다" 고 읽히게 된다.
     */
    static Path archive(CellRunner.Result result, CellMetrics metrics, String report) {
        if (result.stubMode()) {
            throw new IllegalStateException(
                    "스텁 실행은 아카이브를 남기지 않는다 — 고정 판정으로 낸 수치가 실행 기록으로 남으면 "
                            + "나중에 실 LLM 실행과 구별되지 않는다");
        }
        return EvalRunArchive.write("cell-%s".formatted(result.variant().fileLabel()),
                manifest(result, metrics), report);
    }

    /**
     * 실행 manifest.
     *
     * <p>데이터 권리 판정과 라벨링 현황을 손으로 적지 않고 잠금 세트에서 끌어온다
     * ({@code LockedEvalSet.DATA_RIGHTS.asManifestFields()}). 데이터가 말하는 판정과 기록이
     * 말하는 판정이 갈리면 권리 게이트가 기록 단계에서 무의미해지기 때문이다.
     */
    static EvalRunManifest manifest(CellRunner.Result result, CellMetrics metrics) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.putAll(result.identity().asManifestFields());
        extra.putAll(LockedEvalSet.DATA_RIGHTS.asManifestFields());
        extra.putAll(LockedEvalSet.LABELING.asManifestFields());
        extra.putAll(LockedEvalSet.REPORTING.asManifestFields());
        extra.put("locked_set_sha256", LockedEvalSet.fileSha256());
        extra.put("population_model_discriminating",
                String.valueOf(metrics.modelDiscriminating().size()));
        extra.put("population_deterministic_layer",
                String.valueOf(metrics.deterministicLayer().size()));
        extra.put("harness_shape", result.cell().harness().name());
        extra.put("llm_calls", String.valueOf(
                result.outcomes().stream().mapToLong(CellCaseOutcome::llmCalls).sum()));
        extra.put("prompt_tokens", String.valueOf(result.ledger().promptTokens()));
        extra.put("completion_tokens", String.valueOf(result.ledger().completionTokens()));
        extra.put("cost_total_usd",
                CellPricingBook.format(metrics.modelDiscriminating().totalCostUsd()));
        extra.put("cost_per_accepted_response",
                CellPricingBook.format(metrics.modelDiscriminating().costPerAcceptedResponse()));
        extra.put("external_failure_calls", String.valueOf(metrics.externalFailureCalls()));
        extra.put("usage_missing_calls", String.valueOf(metrics.usageMissingCalls()));
        extra.put("empathy_helpfulness", CellMetrics.EMPATHY_NOT_MEASURED);
        extra.put("failure_case_ids", String.join(" ", metrics.failureCaseIds(result.outcomes())));
        extra.put("elapsed", "%dm %ds".formatted(
                metrics.elapsed().toMinutes(), metrics.elapsed().toSecondsPart()));
        extra.put("model_pin_source", result.registry().pinSources().toString());
        extra.put("cbt_classifier_calls", String.valueOf(
                metrics.modelDiscriminating().cbtClassifierCalls()
                        + metrics.deterministicLayer().cbtClassifierCalls()));
        extra.put("timed_out_cases", String.valueOf(
                metrics.modelDiscriminating().timedOutCases()
                        + metrics.deterministicLayer().timedOutCases()));
        extra.put("latency_measured", result.latencyMeasured()
                ? "실측 (동기 스트리밍)" : BatchQualityMode.NOT_MEASURED);
        extra.put("frontier_candidate", result.variant().frontierCandidate() == null
                ? "n/a (후보 스크리닝 아님 — registry 핀 그대로)"
                : result.variant().frontierCandidate());
        extra.put("sampled", result.sampled()
                ? "표본 %d/%d — 릴리스 판정 불가".formatted(result.population(), LockedEvalSet.CASES.size())
                : "전수");
        result.referenceReview().ifPresent(review -> extra.putAll(review.asManifestFields()));

        return new EvalRunManifest(
                CellRunner.SCOPE,
                result.variant().manifestValue(),
                LockedEvalSet.VERSION,
                EvalRunManifest.DatasetSplit.LOCKED_GOLD,
                result.population(),
                "docs/eval/locked-eval-set-labeling-procedure.md",
                LockedEvalSet.DATA_RIGHTS.asManifestDataRights(),
                EvalRunManifest.TuningExposure.NEVER_USED,
                result.registry().manifestModels(),
                EvalRunManifest.UNVERSIONED,
                policyVersion(),
                result.registry().pricing().pricingAsOf(),
                String.valueOf(result.registry().seed()),
                reproduceCommand(result),
                CellGoNoGo.thresholds().asManifestGates(),
                extra);
    }

    /**
     * 재현 명령.
     *
     * <p>후보 스크리닝 변형이면 기준선 A 를 <b>같이</b> 적는다. 후보 하나만 다시 돌린 결과는
     * 이 실행의 기준선과 비교할 수 없으므로, 재현 명령이 그것을 유도하면 안 된다.
     */
    private static String reproduceCommand(CellRunner.Result result) {
        if (!result.variant().isScreeningVariant()) {
            return "./gradlew test -PllmTests -Pcells=%s --tests \"com.mio.ai.qa.CellBenchmarkLlmTest\""
                    .formatted(result.cell().name());
        }
        return ("./gradlew test -PllmTests -Pcells=A,%s -PfrontierCandidates=\"%s\" "
                + "--tests \"com.mio.ai.qa.CellBenchmarkLlmTest\"")
                .formatted(result.cell().name(), result.variant().frontierCandidate());
    }

    /** 정책 버전은 프로덕션 엔진에게 직접 묻는다 — 손으로 적으면 바뀌었을 때 기록만 옛 값이 된다. */
    static String policyVersion() {
        return new com.mio.ai.policy.PolicyEngine(
                new com.mio.ai.security.EffectiveSecurityResolver())
                .decide(new com.mio.ai.safety.SafetySignalCombiner().combine(
                        com.mio.ai.security.SecurityAssessment.clean(),
                        com.mio.ai.safety.SafetyL1Result.clear(),
                        com.mio.ai.moderation.ModerationResult.clear(),
                        null))
                .policyVersion();
    }
}
