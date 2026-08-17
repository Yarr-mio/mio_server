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

        appendPopulation(sb, metrics);
        appendExternalFailures(sb, metrics);
        appendCost(sb, result);

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

    /**
     * 모집단과 <b>이탈 셋</b>.
     *
     * <h2>보장의 실제 범위 — 플래너 층까지다 (P0-3)</h2>
     *
     * <p>예전 문구는 "룰 레이어는 전 케이스를 계약 경로로 보낸다 (ContractEvalSetTest). 여기
     * 남는 것은 Judge 판정이 만든 이탈 둘뿐" 이라고 적었다. 그 보장은 <b>참이지만 플래너 층에
     * 한정된다</b> — {@code ContractEvalSetTest} 는 룰·정책·플래너만 돌리고 생성은 돌리지 않는다.
     * 생성 본문이 없어 계약 검사가 {@code notApplicable()} 을 돌려주는 이탈은 그 테스트가
     * 구조적으로 볼 수 없고, 문구는 그 사실을 말하지 않았다.
     *
     * <p>{@code #305} 대조군은 {@code 계약 밖 25건} 을 찍었고 그것을 설명하는 세 줄이 모두 0
     * 이었다. 25건 전부가 이름 없는 세 번째 이탈이었다. 그래서 이제 <b>셋을 모두 이름으로 찍고
     * 합계를 검산한다</b> — 합이 맞지 않으면 리포트가 스스로 경고한다.
     */
    private static void appendPopulation(StringBuilder sb, ContractComplianceMetrics metrics) {
        sb.append("\n  [모집단]\n");
        sb.append("    계약 적용       %4d건 / %d건%n".formatted(metrics.applicable(), metrics.cases()));
        sb.append("    계약 밖         %4d건%n".formatted(metrics.notApplicable()));
        sb.append("""
              ↑ ContractEvalSetTest 의 "전 케이스가 계약 경로로 간다" 보장은 <플래너 층에 한정>된다.
                룰·정책·플래너까지는 무과금으로 닫혀 있고 그 층의 이탈은 Judge 판정이 만든 둘뿐이다.
                이탈③은 생성 층에서 생기므로 그 테스트가 구조적으로 볼 수 없다 — 지불 전에 닫히는
                것은 ①②뿐이고, ③은 실행 후 이 리포트에서만 보인다.
        """);
        sb.append("      이탈① Judge 위기 승격    %4d건  → 고정 플로우%n"
                .formatted(metrics.crisisRouted()));
        sb.append("      이탈② Judge 보안 의심    %4d건  → GUARDED · 계획 범위 밖%n"
                .formatted(metrics.unplanned()));
        sb.append("        ↑ 룰이 CLEAN 이어도 Judge 가 non-CLEAN 이면 EffectiveSecurityResolver 가\n");
        sb.append("          SUSPICIOUS 로 올리고, 등급이 LOW 이하면 planGeneration 이 unplanned 로 떨어진다.\n");
        sb.append("          등급이 HIGH·MEDIUM 이면 앞 분기가 먼저 걸려 계약이 유지된다.\n");
        sb.append("      이탈③ 생성 본문 없음    %4d건  → ResponseContractValidator.notApplicable()%n"
                .formatted(metrics.noBodyEscapes()));
        sb.append("        ↑ 생성 실패 %d건 · 빈 응답 %d건. 본문이 없으면 계약 검사가 볼 것이 없어\n"
                .formatted(metrics.generationFailures() + metrics.abortedCases(),
                        metrics.emptyResponses()));
        sb.append("          notApplicable() 을 돌려주고, 그 턴은 위반도 준수도 아닌 채 분모에서 빠진다.\n");
        sb.append("          플래너 층 게이트(ContractEvalSetTest)는 생성을 돌리지 않아 이 경로를 못 본다.\n");
        sb.append("      보안 거절          %4d건%n".formatted(metrics.securityRefusal()));
        appendEscapeAudit(sb, metrics);
        sb.append("    미검사          %4d건  ← 계약은 있으나 검사 지점이 없는 전달%n"
                .formatted(metrics.unchecked()));
        sb.append("    생성 호출       %4d건%n".formatted(metrics.generationCalled()));
    }

    /** 이탈 합계 검산. {@code 계약 밖 N건} 이 다시 설명 없이 남는 일을 리포트가 스스로 막는다. */
    private static void appendEscapeAudit(StringBuilder sb, ContractComplianceMetrics metrics) {
        sb.append("      ── 이탈 합계 ①+②+③+보안거절 %4d건 / 계약 밖 %d건%n"
                .formatted(metrics.explainedEscapes(), metrics.notApplicable()));
        if (metrics.unexplainedEscapes() != 0) {
            sb.append(("        ⚠ 설명되지 않은 계약 밖 %d건 — 이름 없는 네 번째 이탈이거나 "
                    + "이탈 정의가 겹친다.%n").formatted(metrics.unexplainedEscapes()));
            sb.append("          이 수치를 인용하기 전에 이탈 분류를 먼저 고친다.\n");
        }
    }

    /**
     * 외부 실패 — <b>사실</b>로 센 값 (P0-3).
     *
     * <p>{@code #305} 리포트는 이 값을 {@code acceptance} 라벨에서 읽어 25건을 1건으로 적었다.
     * 지금은 {@link CellCaseOutcome#externalFailureObserved()} 를 세고, 판정·생성·중단을 나눠
     * 찍는다 — 셋의 대응이 다르기 때문이다.
     */
    private static void appendExternalFailures(StringBuilder sb, ContractComplianceMetrics metrics) {
        sb.append("\n  [외부 실패 — 이 실행을 인용할 수 있는가]\n");
        sb.append("    외부 실패 턴    %4d건 / %d건 (%.1f%%)  상한 %.0f%%%n".formatted(
                metrics.externalFailures(), metrics.cases(),
                metrics.externalFailureShare() * 100,
                ContractComplianceMetrics.MAX_EXTERNAL_FAILURE_SHARE * 100));
        sb.append("      생성 호출 실패   %4d건  ← 본문을 빼앗아 분모에서 턴을 없앤다 (이탈③)%n"
                .formatted(metrics.generationFailures()));
        sb.append("      판정 호출 실패   %4d건  ← 분모는 남기지만 PolicyEngine 4번 분기가 MEDIUM 을%n"
                .formatted(metrics.judgeFailures()));
        sb.append("                            세워 행위 분포를 EMOTION_CHECK 쪽으로 민다\n");
        sb.append("      케이스 중단      %4d건  ← 타임아웃·예외. 원인이 모델이 아니라 동시성일 수 있다%n"
                .formatted(metrics.abortedCases()));
        sb.append("      빈 응답          %4d건  ← 외부 실패가 아니다. 호출은 성공하고 모델이 본문을 안 냈다%n"
                .formatted(metrics.emptyResponses()));
        sb.append("""
              ↑ 한 턴이 두 가지로 실패할 수 있으므로 세 줄의 합은 외부 실패 턴 수보다 클 수 있다.
                acceptance 라벨은 턴당 하나지만 이 집계는 라벨이 아니라 사실을 센다 — #305 실행은
                생성 실패 25건 중 24건이 같은 턴의 판정 실패 라벨에 먹혀 1건으로 보고됐다.
        """);
        if (!metrics.externalFailureWithinLimit()) {
            sb.append(("    ⚠ 외부 실패가 상한을 넘었다 — 이 실행의 위반율을 인용하지 않는다. "
                    + "남은 %d건은 무작위 표본이 아니다.%n")
                    .formatted(metrics.applicable()));
        }
    }

    /**
     * 토큰·원가 (P0-3).
     *
     * <p>{@code #305} 실행은 실비를 보고할 수 없었다 — 이 리포트가 {@link CellTokenLedger} 를
     * 렌더링하지 않았기 때문이다({@link CellReport} 는 {@code 총 원가} 를 싣는다). 그래서
     * "견적 대비 실비" 라는 물음에 아카이브·콘솔 어디에서도 답할 수 없었다. 원장은 이미
     * {@code CellRunner.Result} 에 실려 오므로 새로 재는 것이 없고 늘어나는 호출도 없다.
     *
     * <p>단가 미등록 호출이 하나라도 있으면 총액은 {@code 미상} 이다 — 아는 부분만 더해 총액이라
     * 부르면 실제보다 작은 수를 총액이라 부르는 것이다({@link CellTokenLedger#totalCostUsd()}).
     */
    private static void appendCost(StringBuilder sb, CellRunner.Result result) {
        CellTokenLedger ledger = result.ledger();
        List<CellTokenLedger.Call> calls = ledger.calls();
        sb.append("\n  [토큰·원가 — 견적 대비 실비를 답할 수 있게 한다]\n");
        sb.append("    LLM 호출        %4d건%n".formatted(calls.size()));
        sb.append("    토큰            prompt %d / completion %d%n"
                .formatted(ledger.promptTokens(), ledger.completionTokens()));
        sb.append("    총 원가         %s%n"
                .formatted(CellPricingBook.format(ledger.totalCostUsd())));
        sb.append("    단가 미등록 호출 %4d건  ← 0 이 아니라 '모름'. 하나라도 있으면 총액은 미상이다%n"
                .formatted(ledger.unpricedCalls()));
        sb.append("    사용량 미수신    %4d건  ← 0 토큰이 아니라 '모름'%n"
                .formatted(ledger.usageMissingCalls()));
        if (result.stubMode()) {
            sb.append("      ↑ 스텁 토큰은 실측이 아니라 문자 기반 추정이다 — 원가로 인용할 수 없다\n");
        }
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
             (판정을 한 번만 부르고 두 팔이 재사용하면 완전 페어링이 되지만, 그러면 이 실행이
              프로덕션 경로를 재구성한 것이 아니게 된다 — 프로덕션은 매 턴 판정을 부른다.
              페어링을 얻는 대신 측정 대상이 프로덕션이 아니게 되는 교환이라 부르는 쪽을 택했다.)
    답 못 한다 실제 사용자 표현으로의 일반화. 이 세트는 Mio 가 작성한 합성 발화이며 반말·이모지·
             장문·멀티턴을 섞었어도 실제 트래픽 분포를 재현한 것이 아니다. 길이·질문 수 분포는
             특히 문체에 민감하므로, 여기서 잰 변화폭을 그대로 프로덕션 수치로 옮기지 않는다.
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
                manifest(result, metrics, Map.of()), report);
    }

    /** A/B 비교 자체의 실행 기록. 두 팔의 수치가 한 파일에서 맞대어진다. */
    static Path archiveComparison(CellRunner.Result withRun, ContractComplianceMetrics with,
                                  ContractComplianceMetrics without, String report) {
        requireRealRun(withRun);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("ab_arms", "%s / %s".formatted(with.arm().label(), without.arm().label()));
        extra.put("ab_applicable", "있음 %d / 없음 %d".formatted(with.applicable(), without.applicable()));
        extra.put("ab_violated", "있음 %d / 없음 %d".formatted(with.violated(), without.violated()));
        extra.put("ab_rate_with", with.violationRate().display());
        extra.put("ab_rate_without", without.violationRate().display());
        extra.put("ab_external_failure", "있음 %d/%d · 없음 %d/%d — 두 팔의 유실이 다르면 페어링이 "
                + "더 어긋난다".formatted(with.externalFailures(), with.cases(),
                        without.externalFailures(), without.cases()));
        return EvalRunArchive.write("contract-compliance-ab",
                manifest(withRun, with, extra), report);
    }

    private static void requireRealRun(CellRunner.Result result) {
        if (result.stubMode()) {
            throw new IllegalStateException(
                    "스텁 실행은 아카이브를 남기지 않는다 — 모델이 쓰지 않은 문장으로 낸 계약 수치다");
        }
    }

    /**
     * 실행 manifest.
     *
     * <p><b>표준 항목은 호출부가 넘기지 않는다</b> (P0-3). 예전에는 모집단·이탈·외부 실패 항목이
     * 호출부가 {@code extra} 로 넘겨야만 실렸고, 그래서 {@code extra} 를 비워 부르는 경로에서는
     * 정직성 항목이 통째로 사라진 manifest 가 나왔다. 지금은 {@link #standardFields} 가 항상
     * 붙고 {@code extra} 는 그 위에 얹힌다 — A/B 처럼 이 실행에만 있는 항목을 위한 자리다.
     *
     * @param extra 이 호출에만 해당하는 추가 항목. 표준 항목과 키가 겹치면 덮어쓴다
     */
    static EvalRunManifest manifest(CellRunner.Result result, ContractComplianceMetrics metrics,
                                    Map<String, String> extra) {
        Map<String, String> fields = standardFields(result, metrics);
        fields.putAll(extra);
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
                fields);
    }

    /** 어느 호출 경로에서도 빠지지 않는 표준 항목 (P0-3). */
    private static Map<String, String> standardFields(CellRunner.Result result,
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
        // 이탈③ 과 검산을 manifest 에도 싣는다. 리포트 본문만 고치면 아카이브를 기계로 읽는
        // 쪽에서는 여전히 "계약 밖 N건" 이 설명 없이 남는다.
        extra.put("no_body_escapes", String.valueOf(metrics.noBodyEscapes()));
        extra.put("escape_audit", "설명된 이탈 %d / 계약 밖 %d (미설명 %d)".formatted(
                metrics.explainedEscapes(), metrics.notApplicable(), metrics.unexplainedEscapes()));
        // 키 이름이 바뀌었다 (P0-3). 예전 external_failure_calls 는 acceptance 라벨을 센 값이라
        // 단위가 "호출" 도 아니었고 같은 턴의 판정 실패에 덮였다 — #305 는 25건을 1 로 적었다.
        // 옛 키를 그대로 두면 아카이브를 읽는 쪽이 같은 이름에서 다른 정의를 계속 읽는다.
        extra.put("external_failure_turns", String.valueOf(metrics.externalFailures()));
        extra.put("external_failure_share", "%.4f (상한 %.2f)".formatted(
                metrics.externalFailureShare(),
                ContractComplianceMetrics.MAX_EXTERNAL_FAILURE_SHARE));
        extra.put("external_failure_breakdown", "생성 %d · 판정 %d · 케이스 중단 %d".formatted(
                metrics.generationFailures(), metrics.judgeFailures(), metrics.abortedCases()));
        extra.put("external_failure_within_limit", String.valueOf(metrics.externalFailureWithinLimit()));
        extra.putAll(costFields(result));
        extra.put("reporting_min_subgroup_n",
                String.valueOf(LockedEvalSet.REPORTING.minSubgroupN()));
        extra.put("reporting_unit", reportingUnit(metrics));
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

    /**
     * 토큰·원가 manifest 항목 (P0-3).
     *
     * <p>{@link CellReport} 는 {@code prompt_tokens}·{@code completion_tokens}·
     * {@code cost_total_usd} 를 싣는데 이 리포트는 싣지 않았다. 그래서 {@code #305} 유료 실행은
     * 견적($0.72~$1.34)을 낸 뒤 실비를 <b>어디에서도</b> 확인할 수 없었다. 키 이름을
     * {@code CellReport} 와 같게 두어 두 아카이브를 같은 도구로 읽을 수 있게 한다.
     */
    private static Map<String, String> costFields(CellRunner.Result result) {
        CellTokenLedger ledger = result.ledger();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("llm_calls", String.valueOf(ledger.calls().size()));
        fields.put("prompt_tokens", String.valueOf(ledger.promptTokens()));
        fields.put("completion_tokens", String.valueOf(ledger.completionTokens()));
        fields.put("cost_total_usd", CellPricingBook.format(ledger.totalCostUsd()));
        fields.put("unpriced_calls", String.valueOf(ledger.unpricedCalls()));
        fields.put("usage_missing_calls", String.valueOf(ledger.usageMissingCalls()));
        return fields;
    }

    /**
     * 이 세트의 보고 단위 (P0-3).
     *
     * <p>예전에는 {@code LockedEvalSet.REPORTING.asManifestFields()} 를 그대로 실어
     * <b>잠금 세트의</b> 고정 문구("현재 어느 하위 그룹도 minSubgroupN 을 넘지 않는다")가 나갔다.
     * 이 세트에는 맞지 않는다 — {@code #305} 실행의 하위 그룹은 51건씩이었고 하한 30 을 넘었다.
     * 하한 자체는 두 세트가 공유하므로 {@code reporting_min_subgroup_n} 은 그대로 쓰고, 단위
     * 문구만 <b>보고 대상인 세트와 이번 실행의 관측값</b>에서 만든다.
     *
     * <p>선언 분포(정적)와 관측 행위별 n(동적)을 같이 적는다. 행위별 배정은 InputJudge 판정이
     * 정하므로 선언값이 곧 관측값이 아니고, 실제로 비율을 막거나 여는 것은 관측값이다.
     */
    static String reportingUnit(ContractComplianceMetrics metrics) {
        int floor = LockedEvalSet.REPORTING.minSubgroupN();
        List<String> reportable = new java.util.ArrayList<>();
        List<String> suppressed = new java.util.ArrayList<>();
        for (ResponseAct act : ContractEvalSet.CONTRACT_ACTS) {
            int n = metrics.byAct().get(act).applicable();
            (n >= floor ? reportable : suppressed).add("%s=%d".formatted(act.name(), n));
        }
        return ("세트 %s · 선언 하위 그룹 %s (모두 하한 %d 이상). 이번 실행의 보고 단위는 "
                + "관측 행위별 n 과 총계이며, 비율을 낼 수 있는 행위는 [%s] · 하한 미달로 건수만 "
                + "인용하는 행위는 [%s] 이다. 총계 계약 적용 %d건 → %s.")
                .formatted(ContractEvalSet.VERSION,
                        ContractEvalSet.intendedDistribution(), floor,
                        reportable.isEmpty() ? "없음" : String.join(" ", reportable),
                        suppressed.isEmpty() ? "없음" : String.join(" ", suppressed),
                        metrics.applicable(), metrics.violationRate().display());
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
