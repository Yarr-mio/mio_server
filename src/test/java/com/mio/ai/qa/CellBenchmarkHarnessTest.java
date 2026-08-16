package com.mio.ai.qa;

import com.mio.ai.qa.LockedEvalSet.LockedCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 셀 벤치마크 하네스의 <b>정직성 계약</b> (이슈 #454, 로드맵 §11.3).
 *
 * <p>실 LLM 없이 스텁으로 전 구간을 태워 다음을 검사한다. 값이 얼마인지가 아니라, 하네스가
 * 낼 수 <b>없어야 하는</b> 주장을 실제로 못 내는지가 대상이다.
 *
 * <ol>
 *   <li>하위 그룹 비율은 산출 자체가 불가능하다 ({@code minSubgroupN=30}).</li>
 *   <li>결정론 계층 22건은 모델 변별 301건과 절대 합쳐지지 않는다.</li>
 *   <li>실패 케이스는 ID 로만 남는다 — 본문이 나가면 오염 스캐너가 잡는다.</li>
 *   <li>스텁 실행은 아카이브도 Go/No-Go 도 낼 수 없다.</li>
 *   <li>manifest 는 {@code LOCKED_GOLD} + {@code NEVER_USED} + 데이터 권리를 반드시 싣는다.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] A~E 셀 하네스 계약")
class CellBenchmarkHarnessTest {

    /** 스텁 실행이라 값 자체는 의미가 없다. 구조만 본다 — 그래서 표본을 작게 잡는다. */
    private static final int SAMPLE = 60;

    private final CellRunner.Result result = runStub(BenchmarkCell.A, sample());
    private final CellMetrics metrics = CellMetrics.of(result);

    private static List<LockedCase> sample() {
        return StratifiedSampler.sample(LockedEvalSet.CASES, LockedCase::subgroup, SAMPLE,
                CellModelRegistry.DEFAULT_SEED);
    }

    private static CellRunner.Result runStub(BenchmarkCell cell, List<LockedCase> cases) {
        try {
            return CellRunner.stubbed(cell, CellModelRegistry.resolveForEstimate(cell, Map.of()))
                    .run(cases, cases.size() < LockedEvalSet.CASES.size());
        } catch (Exception e) {
            throw new IllegalStateException("스텁 실행 실패", e);
        }
    }

    // ── 보고 하한 ───────────────────────────────────────────────────

    @Test
    @DisplayName("하위 그룹 비율은 하나도 산출되지 않는다 — 관례가 아니라 타입이 막는다")
    void everySubgroupRateIsSuppressed() {
        assertThat(metrics.subgroupSafetyRates()).isNotEmpty();
        assertThat(metrics.subgroupSafetyRates().values())
                .as("어떤 하위 그룹도 minSubgroupN=%d 를 넘지 않는다 (잠금 세트 reporting.reportableUnit)",
                        LockedEvalSet.REPORTING.minSubgroupN())
                .allMatch(rate -> rate instanceof ReportableRate.Suppressed);
    }

    @Test
    @DisplayName("Suppressed 는 분자를 담지 않는다 — 비율을 되살릴 방법이 없다")
    void suppressedRateCarriesNoNumerator() {
        ReportableRate suppressed = ReportableRate.of("작은 그룹", 3, 12);

        assertThat(suppressed).isInstanceOf(ReportableRate.Suppressed.class);
        assertThat(suppressed.display()).contains("미보고").doesNotContain("%");
        assertThat(ReportableRate.Suppressed.class.getRecordComponents())
                .as("분자를 담으면 호출부가 나눠 쓸 수 있고, 그 순간 하한은 관례로 되돌아간다")
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("unit", "denominator", "floor", "reason");
    }

    @Test
    @DisplayName("하한을 넘긴 단위만 비율과 신뢰구간을 낸다")
    void reportableRateIncludesConfidenceInterval() {
        ReportableRate reported = ReportableRate.of("총계", 10, 200);

        assertThat(reported).isInstanceOf(ReportableRate.Reported.class);
        assertThat(reported.display()).contains("95% CI");
    }

    @Test
    @DisplayName("리포트 본문에 하위 그룹 이름과 비율이 함께 나타나지 않는다")
    void renderedReportNeverPairsSubgroupWithPercent() {
        String report = CellReport.render(result, metrics);

        for (String subgroup : LockedEvalSet.INTENDED_DISTRIBUTION.keySet()) {
            report.lines()
                    .filter(line -> line.contains(subgroup))
                    .forEach(line -> assertThat(line)
                            .as("하위 그룹 '%s' 행에 비율이 찍혔다: %s", subgroup, line)
                            .doesNotContain("%"));
        }
    }

    // ── 모집단 분리 ─────────────────────────────────────────────────

    @Test
    @DisplayName("결정론 계층과 모델 변별 모집단이 분리되고, 합계 지표는 만들어지지 않는다")
    void populationsAreReportedSeparately() {
        CellRunner.Result full = runStub(BenchmarkCell.A, LockedEvalSet.CASES);
        CellMetrics fullMetrics = CellMetrics.of(full);

        assertThat(fullMetrics.modelDiscriminating().size())
                .isEqualTo(LockedEvalSet.modelDiscriminatingCases().size());
        assertThat(fullMetrics.deterministicLayer().size())
                .isEqualTo(LockedEvalSet.deterministicLayerCases().size());
        assertThat(CellMetrics.class.getDeclaredMethods())
                .as("두 모집단을 합치는 접근자가 있으면 그 값이 헤드라인이 된다")
                .noneMatch(method -> method.getName().toLowerCase().contains("combined")
                        || method.getName().toLowerCase().contains("overall"));

        String report = CellReport.render(full, fullMetrics);
        assertThat(report).contains("모델 변별").contains("결정론 계층");
    }

    @Test
    @DisplayName("축·하위 그룹 비율은 모델 변별 모집단에서만 계산된다")
    void axisRatesExcludeDeterministicLayer() {
        CellRunner.Result full = runStub(BenchmarkCell.A, LockedEvalSet.CASES);
        CellMetrics fullMetrics = CellMetrics.of(full);

        long axisTotal = fullMetrics.subgroupSafetyRates().values().stream()
                .mapToLong(ReportableRate::denominator).sum();
        long expected = LockedEvalSet.modelDiscriminatingCases().stream()
                .filter(c -> !"CLEAR".equals(c.expected().safetyTruth())).count();
        assertThat(axisTotal).isEqualTo(expected);
    }

    // ── 아카이브 정직성 ─────────────────────────────────────────────

    @Test
    @DisplayName("스텁 실행은 아카이브를 남기지 못한다")
    void stubRunCannotBeArchived() {
        assertThatThrownBy(() -> CellReport.archive(result, metrics, "report"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("스텁 실행은 아카이브를 남기지 않는다");
    }

    @Test
    @DisplayName("manifest 가 LOCKED_GOLD·NEVER_USED·데이터 권리·실제 모델 ID·셀 값을 싣는다")
    void manifestCarriesProvenance() {
        EvalRunManifest manifest = CellReport.manifest(result, metrics);
        Map<String, String> metadata = manifest.toMetadata();

        assertThat(manifest.datasetSplit())
                .isEqualTo(EvalRunManifest.DatasetSplit.LOCKED_GOLD);
        assertThat(manifest.tuningExposure())
                .isEqualTo(EvalRunManifest.TuningExposure.NEVER_USED);
        assertThat(manifest.dataRights())
                .isEqualTo(LockedEvalSet.DATA_RIGHTS.asManifestDataRights());
        assertThat(manifest.datasetVersion()).isEqualTo(LockedEvalSet.VERSION);
        assertThat(manifest.models()).isEqualTo(result.registry().manifestModels());
        assertThat(manifest.cell()).contains(BenchmarkCell.A.label());
        assertThat(metadata).containsEntry("random_seed",
                String.valueOf(CellModelRegistry.DEFAULT_SEED));
        assertThat(metadata).containsKeys("data_rights_gate_decision", "label_status",
                "reporting_min_subgroup_n", "locked_set_sha256", "empathy_helpfulness");
        assertThat(metadata.get("gate_registered"))
                .as("사전 등록한 Go/No-Go 문턱이 기록에 남아야 한다")
                .contains(CellGoNoGo.thresholds().version());
    }

    @Test
    @DisplayName("실패 기록은 케이스 ID 만이다 — 본문은 어디에도 나가지 않는다")
    void failuresAreRecordedAsIdsOnly() {
        Map<String, String> metadata = CellReport.manifest(result, metrics).toMetadata();
        String report = CellReport.render(result, metrics);

        for (String id : metrics.failureCaseIds(result.outcomes())) {
            assertThat(metadata.get("failure_case_ids")).contains(id);
        }
        String normalizedReport = LockedEvalSet.normalize(report);
        for (LockedCase lockedCase : LockedEvalSet.CASES) {
            for (LockedEvalSet.Turn turn : lockedCase.turns()) {
                String normalizedTurn = LockedEvalSet.normalize(turn.text());
                // 아주 짧은 조각은 우연 일치가 나므로 오염 가드와 같은 기준으로 건너뛴다.
                if (normalizedTurn.length() < 16) {
                    continue;
                }
                assertThat(normalizedReport)
                        .as("케이스 본문이 리포트에 실렸다: %s", lockedCase.id())
                        .doesNotContain(normalizedTurn);
            }
        }
    }

    // ── Go/No-Go ────────────────────────────────────────────────────

    @Test
    @DisplayName("스텁·표본 실행으로는 Go/No-Go 를 낼 수 없다")
    void verdictRefusesStubAndSampledRuns() {
        CellGoNoGo.Result verdict = CellGoNoGo.evaluate(result, metrics, result, metrics);

        assertThat(verdict.verdict()).isEqualTo(CellGoNoGo.Verdict.NOT_EVALUABLE);
        assertThat(verdict.reason()).contains("스텁 실행");
    }

    @Test
    @DisplayName("사전 등록한 문턱이 데이터로 존재하고 안전 하한은 증가를 허용하지 않는다")
    void thresholdsArePreRegisteredData() {
        CellGoNoGo.Thresholds thresholds = CellGoNoGo.thresholds();

        assertThat(thresholds.maxHighRiskFalseNegativeIncrease()).isZero();
        assertThat(thresholds.maxContraindicationViolationIncrease()).isZero();
        assertThat(thresholds.maxHardCrisisDowngradeIncrease()).isZero();
        assertThat(thresholds.minCostPerAcceptedImprovementPercent()).isPositive();
        assertThat(thresholds.outstandingGates())
                .as("shadow·canary 는 offline 에서 확인할 수 없으므로 미해결 게이트로 남아야 한다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("이 하네스는 GO 를 낼 수 없다 — 최고 등급이 shadow·canary 대기다")
    void harnessCannotDeclareGo() {
        assertThat(CellGoNoGo.Verdict.values())
                .noneMatch(verdict -> verdict.name().equals("GO"));
        assertThat(CellGoNoGo.Verdict.OFFLINE_CONDITIONS_MET_PENDING_SHADOW_CANARY.name())
                .contains("PENDING_SHADOW_CANARY");
    }

    // ── 재현성 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 시드·같은 표본 수는 같은 케이스 집합을 낸다")
    void samplingIsDeterministic() {
        List<String> first = StratifiedSampler
                .sample(LockedEvalSet.CASES, LockedCase::subgroup, 40, CellModelRegistry.DEFAULT_SEED)
                .stream().map(LockedCase::id).toList();
        List<String> second = StratifiedSampler
                .sample(LockedEvalSet.CASES, LockedCase::subgroup, 40, CellModelRegistry.DEFAULT_SEED)
                .stream().map(LockedCase::id).toList();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("결과 순서가 입력 순서와 같다 — 실행 간 diff 가 순서로 흔들리지 않는다")
    void outcomeOrderMatchesInputOrder() {
        List<String> expected = sample().stream().map(LockedCase::id).toList();

        assertThat(result.outcomes().stream().map(CellCaseOutcome::caseId).toList())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("파일럿 구성(-Pcells=A,D -PsampleSize=20)이 실 LLM 없이 끝까지 돈다")
    void pilotConfigurationRunsEndToEndWithoutNetwork() {
        Map<String, String> pins = Map.of(
                CellModelRegistry.MODEL_PROPERTY_PREFIX + "escalation", "candidate-x",
                CellModelRegistry.PRICE_PROPERTY_PREFIX + "candidate-x", "5.0/2.5/20.0",
                CellModelRegistry.PRICING_AS_OF_PROPERTY, "2026-08-16");
        List<LockedCase> pilotCases = StratifiedSampler.sample(LockedEvalSet.CASES,
                LockedCase::subgroup, 20, CellModelRegistry.DEFAULT_SEED);

        for (BenchmarkCell cell : List.of(BenchmarkCell.A, BenchmarkCell.D)) {
            CellModelRegistry registry = CellModelRegistry.resolve(cell, pins);
            CellRunner.Result pilot;
            try {
                pilot = CellRunner.stubbed(cell, registry).run(pilotCases, true);
            } catch (Exception e) {
                throw new IllegalStateException("파일럿 리허설 실패: " + cell, e);
            }
            CellMetrics pilotMetrics = CellMetrics.of(pilot);

            assertThat(pilot.outcomes()).hasSize(pilotCases.size());
            assertThat(pilot.ledger().calls())
                    .as("셀 %s 파일럿이 모델을 한 번도 부르지 않으면 경로가 죽은 것이다", cell)
                    .isNotEmpty();
            assertThat(registry.unpricedOnlineModels())
                    .as("파일럿에서 핀한 모델의 단가가 등록돼 원가가 산출돼야 한다")
                    .isEmpty();
            assertThat(pilotMetrics.modelDiscriminating().totalCostUsd()).isPresent();
            assertThat(CellReport.render(pilot, pilotMetrics)).contains("표본 실행");
            assertThat(CellReport.manifest(pilot, pilotMetrics).toMetadata().get("sampled"))
                    .contains("릴리스 판정 불가");
        }
    }

    @Test
    @DisplayName("셀별로 핀한 모델이 실제 요청에 반영된다 — 프로덕션 상수를 바꾸지 않고")
    void pinnedModelReachesTheRequest() {
        var registry = CellModelRegistry.resolve(BenchmarkCell.B, Map.of(
                CellModelRegistry.MODEL_PROPERTY_PREFIX + "generation", "candidate-x"));
        var client = new RoleModelRewritingLlmClient(
                new StubLlmClient(new CellTokenLedger(), registry.pricing()),
                registry.componentToModel());

        var rewritten = client.rewrite(com.mio.ai.llm.LlmRequest
                .of("gpt-4o", "system", "user")
                .withAttribution("MAIN_GENERATION", null, null));
        var untouched = client.rewrite(com.mio.ai.llm.LlmRequest
                .of("gpt-4o-mini", "system", "user")
                .withAttribution("SESSION_SUMMARY", null, null));

        assertThat(rewritten.model()).isEqualTo("candidate-x");
        assertThat(untouched.model())
                .as("셀이 선언하지 않은 역할까지 바꾸면 셀 정의와 실제 실행이 달라진다")
                .isEqualTo("gpt-4o-mini");
    }
}
