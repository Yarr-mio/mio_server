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

    /** 이 테스트 클래스 전체가 한 "실행" 인 것처럼 도장을 하나 쓴다. */
    private static final RunIdentity IDENTITY = RunIdentity.stamp("2026-08-16");

    private final CellRunner.Result result = runStub(BenchmarkCell.A, sample());
    private final CellMetrics metrics = CellMetrics.of(result);

    private static List<LockedCase> sample() {
        return StratifiedSampler.sample(LockedEvalSet.CASES, LockedCase::subgroup, SAMPLE,
                CellModelRegistry.DEFAULT_SEED);
    }

    private static CellRunner.Result runStub(BenchmarkCell cell, List<LockedCase> cases) {
        return runStub(CellVariant.of(cell),
                CellModelRegistry.resolveForEstimate(cell, Map.of()), cases, IDENTITY);
    }

    private static CellRunner.Result runStub(CellVariant variant, CellModelRegistry registry,
                                             List<LockedCase> cases, RunIdentity identity) {
        try {
            return CellRunner.stubbed(variant, registry)
                    .run(cases, cases.size() < LockedEvalSet.CASES.size(), identity);
        } catch (Exception e) {
            throw new IllegalStateException("스텁 실행 실패: " + variant.label(), e);
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

    // ── 교차 실행 비교 차단 ─────────────────────────────────────────

    @Test
    @DisplayName("다른 실행의 결과와는 비교하지 않는다 — 관례가 아니라 evaluate() 가 막는다")
    void verdictRefusesResultsFromAnotherRun() {
        CellRunner.Result otherRun = runStub(CellVariant.of(BenchmarkCell.A),
                CellModelRegistry.resolveForEstimate(BenchmarkCell.A, Map.of()), sample(),
                RunIdentity.stamp("2026-08-16"));

        CellGoNoGo.Result verdict = CellGoNoGo.evaluate(result, metrics, otherRun,
                CellMetrics.of(otherRun));

        assertThat(verdict.verdict()).isEqualTo(CellGoNoGo.Verdict.NOT_EVALUABLE);
        assertThat(verdict.reason())
                .as("스텁 사유보다 먼저 걸려야 한다 — 교차 실행은 다른 조건을 만족해도 판정이 될 수 없다")
                .contains("서로 다른 실행");
    }

    @Test
    @DisplayName("실행 도장 없는 결과는 만들어지지 않는다 — 가드를 우회할 값이 존재하지 않는다")
    void resultCannotExistWithoutRunIdentity() {
        assertThatThrownBy(() -> new CellRunner.Result(CellVariant.of(BenchmarkCell.A),
                result.registry(), result.outcomes(), result.ledger(), result.elapsed(),
                true, 1, true, null, java.util.Optional.empty(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("실행 도장이 없다");
    }

    @Test
    @DisplayName("도장은 세트 버전·내용 해시·정책·프롬프트·단가 기준일을 전부 들고 다닌다")
    void runIdentityCarriesEveryVersionThatChangesMeaning() {
        RunIdentity identity = RunIdentity.stamp("2026-08-16");

        assertThat(identity.datasetVersion()).isEqualTo(LockedEvalSet.VERSION);
        assertThat(identity.lockedSetSha256()).isEqualTo(LockedEvalSet.fileSha256());
        assertThat(identity.promptVersion()).isEqualTo(EvalRunManifest.UNVERSIONED);
        assertThat(identity.pricingAsOf()).isEqualTo("2026-08-16");
        assertThat(identity.mismatchAgainst(identity)).isNull();
        assertThat(identity.mismatchAgainst(null)).contains("실행 도장이 없다");
        assertThat(new RunIdentity(identity.runId(), identity.datasetVersion(),
                identity.lockedSetSha256(), identity.policyVersion(), identity.promptVersion(),
                "2026-01-01").mismatchAgainst(identity))
                .as("같은 실행이라도 단가 기준일이 갈리면 원가 비교가 성립하지 않는다")
                .contains("단가 기준일");
    }

    @Test
    @DisplayName("실행 도장이 아카이브 manifest 에 남아 나중에 읽는 도구도 같은 검사를 할 수 있다")
    void manifestCarriesRunIdentity() {
        Map<String, String> metadata = CellReport.manifest(result, metrics).toMetadata();

        assertThat(metadata).containsEntry("run_id", result.identity().runId().toString());
        assertThat(metadata.get("run_identity_note")).contains("run_id");
    }

    // ── 셀 C: offline reference judge ───────────────────────────────

    @Test
    @DisplayName("셀 C 는 offline reference 채점 결과를 실제로 낸다 — 선언만 있고 미구현이 아니다")
    void cellCProducesReferenceReview() {
        CellRunner.Result cellC = runStub(BenchmarkCell.C, sample());

        assertThat(cellC.referenceReview())
                .as("REFERENCE_JUDGE 역할을 선언한 셀은 채점 pass 를 돌려야 한다")
                .isPresent();
        CellReferenceReview review = cellC.referenceReview().orElseThrow();
        assertThat(review.calls())
                .as("채점 호출이 0 이면 reference judge 경로가 죽은 것이다")
                .isPositive();
        assertThat(review.scored() + review.failed())
                .isEqualTo(LockedEvalSet.modelDiscriminatingCases().stream()
                        .filter(c -> sample().contains(c)).toList().size());
        assertThat(CellReport.render(cellC, CellMetrics.of(cellC)))
                .contains("offline reference judge")
                .contains(CellReferenceReview.SEPARATION_NOTE);
    }

    @Test
    @DisplayName("셀 C 의 offline 채점은 온라인 원장·원가·지연에 섞이지 않는다")
    void referenceReviewNeverEntersOnlineCost() {
        CellRunner.Result cellC = runStub(BenchmarkCell.C, sample());
        CellReferenceReview review = cellC.referenceReview().orElseThrow();

        assertThat(cellC.ledger().calls())
                .as("온라인 원장에 offline 태그 호출이 있으면 셀 C 의 전제가 깨진다")
                .noneMatch(call -> CellModelRole.OFFLINE_COMPONENT.equals(call.component()));
        assertThat(review.promptTokens())
                .as("offline pass 는 자기 토큰을 자기 원장에만 쌓는다")
                .isPositive();
        assertThat(cellC.ledger().promptTokens())
                .as("온라인 토큰 합계에 offline 토큰이 더해지면 안 된다")
                .isNotEqualTo(cellC.ledger().promptTokens() + review.promptTokens());
        assertThat(CellModelRole.REFERENCE_JUDGE.isOnline())
                .as("온라인 역할이 되는 순간 componentToModel 을 통해 운영 경로로 들어간다")
                .isFalse();
    }

    @Test
    @DisplayName("A==C 는 구성의 동일성을 단언하고, 수치 차이는 신호로만 낸다")
    void cellCMatchesBaselineComposition() {
        CellRunner.Result cellA = runStub(BenchmarkCell.A, sample());
        CellRunner.Result cellC = runStub(BenchmarkCell.C, sample());

        CellParity.Result parity = CellParity.check(cellA, CellMetrics.of(cellA), cellC,
                CellMetrics.of(cellC));

        assertThat(parity.violations()).isEmpty();
        assertThat(parity.held()).isTrue();
        assertThat(cellC.registry().componentToModel())
                .as("온라인 역할별 모델이 A 와 같아야 셀 C 다")
                .isEqualTo(cellA.registry().componentToModel());
        assertThat(parity.render())
                .as("단언하지 않는 것을 단언한다고 적지 않는다")
                .contains("수치(원가·p95) 동일성은 단언하지 않는다");
    }

    @Test
    @DisplayName("reference judge 모델을 온라인 역할에도 배정하면 동일성 검사가 잡는다")
    void parityCatchesReferenceModelLeakingOnline() {
        CellRunner.Result cellA = runStub(BenchmarkCell.A, sample());
        CellModelRegistry contaminated = CellModelRegistry.resolve(BenchmarkCell.C, Map.of(
                CellModelRegistry.MODEL_PROPERTY_PREFIX + "reference_judge", "frontier-x",
                CellModelRegistry.MODEL_PROPERTY_PREFIX + "generation", "frontier-x"));
        CellRunner.Result contaminatedC = runStub(CellVariant.of(BenchmarkCell.C), contaminated,
                sample(), IDENTITY);

        CellParity.Result parity = CellParity.check(cellA, CellMetrics.of(cellA), contaminatedC,
                CellMetrics.of(contaminatedC));

        assertThat(parity.held()).isFalse();
        assertThat(parity.violations().toString()).contains("reference judge 모델이 온라인 역할에도");
    }

    // ── CBT 분류기 ──────────────────────────────────────────────────

    @Test
    @DisplayName("프로덕션이 매 턴 부르는 CBT 분류 호출을 하네스도 부른다 — 제외 목록이 아니다")
    void cbtClassifierIsCalledLikeProduction() {
        assertThat(result.ledger().calls())
                .as("CBT_CLASSIFIER 호출이 0 이면 턴당 원가가 프로덕션보다 낮게 나온다")
                .anyMatch(call -> "CBT_CLASSIFIER".equals(call.component()));
        assertThat(metrics.modelDiscriminating().cbtClassifierCalls()).isPositive();
        assertThat(result.outcomes())
                .as("응답을 전달하지 않은 턴은 프로덕션도 부르지 않는다")
                .filteredOn(outcome -> !outcome.accepted())
                .allMatch(outcome -> !outcome.cbtClassifierCalled());
        assertThat(CellRunner.SCOPE).contains("CbtMetadataClassifier");
    }

    @Test
    @DisplayName("CBT 분류 역할은 전 셀에 있다 — 셀마다 다르면 상수가 아니라 변수가 된다")
    void everyCellDeclaresCbtClassifier() {
        for (BenchmarkCell cell : BenchmarkCell.values()) {
            assertThat(cell.onlineRoles())
                    .as("셀 %s 에 CBT 분류 역할이 없다", cell)
                    .contains(CellModelRole.CBT_CLASSIFIER);
        }
    }

    // ── 케이스 실패 격리 ────────────────────────────────────────────

    @Test
    @DisplayName("한 케이스가 타임아웃돼도 셀 전체가 중단되지 않고 그 케이스만 실패로 기록된다")
    void caseTimeoutIsRecordedInsteadOfAbortingTheCell() {
        List<LockedCase> few = LockedEvalSet.CASES.subList(0, 3);
        System.setProperty(CellRunner.CASE_TIMEOUT_PROPERTY, "1");
        try {
            CellRunner.Result timedOutRun = CellRunner.withClientFactory(
                            CellVariant.of(BenchmarkCell.A),
                            CellModelRegistry.resolve(BenchmarkCell.A, Map.of()),
                            (ledger, pricing) -> new HangingLlmClient())
                    .run(few, true, IDENTITY);

            assertThat(timedOutRun.outcomes())
                    .as("유료 실행의 이미 지출한 부분을 통째로 버리지 않는다 — 결과는 전부 돌아온다")
                    .hasSize(few.size());
            assertThat(timedOutRun.outcomes()).allMatch(CellCaseOutcome::timedOut);
            assertThat(timedOutRun.outcomes()).allMatch(outcome ->
                    outcome.acceptance() == CellCaseOutcome.Acceptance.REJECTED_EXTERNAL_FAILURE);
            assertThat(timedOutRun.outcomes())
                    .as("실패가 수용으로 세지면 §11.3 채택 조건 셋째가 깨진다")
                    .noneMatch(CellCaseOutcome::accepted);
            assertThat(CellMetrics.of(timedOutRun).modelDiscriminating().timedOutCases()
                    + CellMetrics.of(timedOutRun).deterministicLayer().timedOutCases())
                    .isEqualTo(few.size());
        } catch (Exception e) {
            throw new IllegalStateException("타임아웃 실행이 예외로 중단됐다 — 그것이 바로 고친 결함이다", e);
        } finally {
            System.clearProperty(CellRunner.CASE_TIMEOUT_PROPERTY);
        }
    }

    /** 무엇을 물어도 인터럽트 전까지 돌아오지 않는 클라이언트. 타임아웃 경로만 만든다. */
    private static final class HangingLlmClient implements com.mio.ai.llm.LlmClient {

        @Override
        public com.mio.ai.llm.LlmStreamResult stream(com.mio.ai.llm.LlmRequest request,
                                                     java.util.function.Consumer<String> handler) {
            return hang();
        }

        @Override
        public String completeText(com.mio.ai.llm.LlmRequest request) {
            return hang();
        }

        @Override
        public String completeJson(com.mio.ai.llm.LlmRequest request) {
            return hang();
        }

        private <T> T hang() {
            try {
                Thread.sleep(java.time.Duration.ofMinutes(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("도달하지 않는다");
        }
    }

    // ── batch 품질 모드 ─────────────────────────────────────────────

    @Test
    @DisplayName("batch 품질 모드는 1단계·생성 품질 셀에서만 허용되고, 나머지는 fail-closed 다")
    void batchQualityModeIsGatedToStageOneQualityCells() {
        assertThatThrownBy(() -> BatchQualityMode.requireEligible(BenchmarkStage.SEMIFINAL,
                List.of(BenchmarkCell.A, BenchmarkCell.B)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1단계 스크리닝에서만");
        assertThatThrownBy(() -> BatchQualityMode.requireEligible(BenchmarkStage.FULL,
                List.of(BenchmarkCell.A)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BatchQualityMode.requireEligible(BenchmarkStage.SCREEN,
                List.of(BenchmarkCell.A, BenchmarkCell.D)))
                .as("셀 D·E 는 호출 수 절감과 하네스 축소가 핵심이라 지연 없이는 가설을 못 본다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("돌릴 수 없는 셀");

        BatchQualityMode.requireEligible(BenchmarkStage.SCREEN,
                List.of(BenchmarkCell.A, BenchmarkCell.B));
        assertThatThrownBy(() -> BatchQualityMode.requireTransport(false))
                .as("전송 계층이 없는데 동기로 조용히 되돌아가면 batch 청구서를 받은 줄 알게 된다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전송 계층이 아직 없다");
        assertThat(BatchQualityMode.discounted(new java.math.BigDecimal("2.0")))
                .isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("지연을 재지 못한 실행은 0 이 아니라 미측정으로 찍히고, 순위가 그 사실을 말한다")
    void unmeasuredLatencyIsNeverZeroOrBlank() {
        CellRunner.Result batchLike;
        try {
            batchLike = CellRunner.stubbed(CellVariant.of(BenchmarkCell.A),
                            CellModelRegistry.resolve(BenchmarkCell.A, Map.of()))
                    .run(sample(), true, IDENTITY, false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        CellMetrics batchMetrics = CellMetrics.of(batchLike);

        String report = CellReport.render(batchLike, batchMetrics);
        assertThat(report).contains(BatchQualityMode.NOT_MEASURED);
        assertThat(CellReport.manifest(batchLike, batchMetrics).toMetadata())
                .containsEntry("latency_measured", BatchQualityMode.NOT_MEASURED);

        String screening = CellScreeningReport.render(List.of(
                        new CellScreeningReport.Row(CellVariant.of(BenchmarkCell.A),
                                batchMetrics, true, false),
                        new CellScreeningReport.Row(new CellVariant(BenchmarkCell.B, "cand"),
                                batchMetrics, true, false)),
                IDENTITY, List.of("cand"), BenchmarkStage.SCREEN);
        assertThat(screening)
                .contains(BatchQualityMode.PARTIAL_RANKING)
                .contains(BatchQualityMode.NOT_MEASURED);

        // 스텁 판정은 항상 CLEAR_LOW 라 안전 항목이 실제로 깨진다. 여기서 보려는 것은
        // '지연을 재지 못했을 때의 결론' 이므로 나머지 문턱은 통과하게 열어 둔다.
        CandidateElimination.Thresholds registered =
                CandidateElimination.thresholds(BenchmarkStage.SCREEN);
        CandidateElimination.Verdict verdict = CandidateElimination.evaluate(
                new CandidateElimination.Thresholds(registered.version(),
                        registered.registeredOn(), BenchmarkStage.SCREEN,
                        999, 999, 999, 999, 99.0, 99_000L, 99_000L, 99.0, 100.0, 6),
                CellVariant.of(BenchmarkCell.B), batchMetrics.modelDiscriminating(),
                batchMetrics.modelDiscriminating(), false);
        assertThat(verdict.outcome())
                .as("재지 못한 축을 통과로 접으면 지연으로 떨어져야 할 후보가 조용히 올라간다")
                .isEqualTo(CandidateElimination.Outcome.NOT_ASSESSABLE);
        assertThat(verdict.reason()).contains("동기 지연 프로브");
    }

    // ── 후보 스크리닝 ───────────────────────────────────────────────

    @Test
    @DisplayName("후보 여럿이 한 실행에서 같은 기준선·같은 도장으로 펼쳐진다")
    void screeningExpandsCandidatesWithinOneRun() {
        List<String> candidates = List.of("frontier-1", "frontier-2", "frontier-3");
        List<CellVariant> variants =
                CellVariant.expand(List.of(BenchmarkCell.A, BenchmarkCell.B), candidates);

        assertThat(variants).extracting(CellVariant::label)
                .as("기준선 A 는 후보와 무관하므로 한 번만, 셀 B 는 후보 수만큼")
                .containsExactly("A", "B/frontier-1", "B/frontier-2", "B/frontier-3");

        List<CellRunner.Result> runs = new java.util.ArrayList<>();
        for (CellVariant variant : variants) {
            CellModelRegistry registry = CellModelRegistry.resolveForVariant(variant, Map.of(
                    CellModelRegistry.PRICE_PROPERTY_PREFIX + "frontier-1", "2.0/0.2/12.0"));
            runs.add(runStub(variant, registry, sample(), IDENTITY));
        }

        assertThat(runs).extracting(run -> run.identity().runId()).containsOnly(IDENTITY.runId());
        assertThat(runs.get(1).registry().modelFor(CellModelRole.GENERATION))
                .isEqualTo("frontier-1");
        assertThat(runs.get(2).registry().modelFor(CellModelRole.GENERATION))
                .isEqualTo("frontier-2");
        assertThat(CellGoNoGo.evaluate(runs.get(0), CellMetrics.of(runs.get(0)),
                        runs.get(1), CellMetrics.of(runs.get(1))).candidate().label())
                .as("판정 결과도 어느 후보의 것인지 이름으로 구별돼야 한다")
                .isEqualTo("B/frontier-1");
    }

    @Test
    @DisplayName("단가 미상 후보도 품질·지연 비교는 나오고, 원가만 미상으로 남는다")
    void screeningKeepsQualityWhenPricesAreUnknown() {
        List<CellVariant> variants =
                CellVariant.expand(List.of(BenchmarkCell.A, BenchmarkCell.B),
                        List.of("priced-1", "unpriced-2"));
        Map<String, String> pins = Map.of(
                CellModelRegistry.PRICE_PROPERTY_PREFIX + "priced-1", "2.0/0.2/12.0");

        List<CellRunner.Result> runs = new java.util.ArrayList<>();
        List<CellScreeningReport.Row> rows = new java.util.ArrayList<>();
        for (CellVariant variant : variants) {
            CellRunner.Result run = runStub(variant,
                    CellModelRegistry.resolveForVariant(variant, pins), sample(), IDENTITY);
            runs.add(run);
            rows.add(new CellScreeningReport.Row(variant, CellMetrics.of(run), true));
        }

        assertThat(CellScreeningReport.unpricedCandidates(runs)).containsExactly("unpriced-2");
        String report = CellScreeningReport.render(rows, IDENTITY,
                CellScreeningReport.unpricedCandidates(runs), BenchmarkStage.SCREEN);
        assertThat(report)
                .contains(CellScreeningReport.NOT_A_VERDICT)
                .contains("B/priced-1")
                .contains("B/unpriced-2")
                .contains("미상")
                .contains("단가를 핀해야 하는 후보")
                .as("표본 실행이 안전 판정처럼 읽히면 안 된다")
                .contains("안전 판정은 나오지 않는다");
    }

    @Test
    @DisplayName("탈락 규칙이 사전 등록 데이터이고, 지연만으로도 후보가 떨어진다")
    void eliminationRulesArePreRegisteredAndLatencyAloneCanDrop() {
        CandidateElimination.Thresholds thresholds =
                CandidateElimination.thresholds(BenchmarkStage.SCREEN);

        assertThat(thresholds.maxHighRiskFalseNegativeIncrease()).isZero();
        assertThat(thresholds.maxHardCrisisDowngradeIncrease()).isZero();
        assertThat(thresholds.maxContraindicationViolations()).isZero();
        assertThat(thresholds.maxP95LatencyMs()).isPositive();
        assertThat(thresholds.maxGenerationTruncationRatePercent()).isPositive();
        assertThat(thresholds.keepTop()).isPositive();
        assertThat(CandidateElimination.thresholds(BenchmarkStage.SEMIFINAL).maxP95LatencyMs())
                .as("단계가 올라가면 문턱이 느슨해지면 안 된다")
                .isLessThanOrEqualTo(thresholds.maxP95LatencyMs());
        assertThatThrownBy(() -> CandidateElimination.thresholds(BenchmarkStage.FULL))
                .as("3단계는 좁히는 단계가 아니라 판정 단계다 — Go/No-Go 가 판정한다")
                .isInstanceOf(IllegalStateException.class);

        CellRunner.Result baseline = runStub(BenchmarkCell.A, sample());
        CellMetrics.Population population = CellMetrics.of(baseline).modelDiscriminating();
        // 안전·품질·비용 문턱은 전부 통과하게 열어 두고 지연 문턱만 만족 불가능하게 만든다.
        // 스텁 실행의 실측 지연은 0ms 에 가까워 양수 문턱으로는 이 경로를 만들 수 없다.
        CandidateElimination.Verdict verdict = CandidateElimination.evaluate(
                new CandidateElimination.Thresholds(thresholds.version(),
                        thresholds.registeredOn(), BenchmarkStage.SCREEN,
                        99, 99, 99, 99, 99.0, -1L, -1L, 99.0, 100.0, 6),
                CellVariant.of(BenchmarkCell.B), population, population);

        assertThat(verdict.outcome())
                .as("품질·안전·비용이 전부 통과해도 지연 하나로 떨어질 수 있어야 한다")
                .isEqualTo(CandidateElimination.Outcome.ELIMINATED);
        assertThat(verdict.reason()).contains("p95 지연");
    }

    @Test
    @DisplayName("안전 두 항목은 기준선 대비 증가분이다 — 기준선과 같은 값이면 통과한다")
    void safetyFloorsAreBaselineRelativeSoIdenticalDetectionPasses() {
        CandidateElimination.Thresholds thresholds =
                CandidateElimination.thresholds(BenchmarkStage.SCREEN);
        CellRunner.Result baseline = runStub(BenchmarkCell.A, sample());
        CellMetrics.Population population = CellMetrics.of(baseline).modelDiscriminating();

        assertThat(population.falseNegatives())
                .as("이 단언이 깨지면 아래 시나리오가 '절대 0 을 통과한 것' 과 구별되지 않는다")
                .isPositive();

        CandidateElimination.Verdict verdict = CandidateElimination.evaluate(thresholds,
                new CellVariant(BenchmarkCell.B, "탐지가-같은-후보"), population, population);

        assertThat(verdict.checks())
                .as("셀 B 는 탐지를 바꾸지 않으므로 후보와 기준선의 미탐·HARD 하향이 항상 같다. "
                        + "그 축으로 후보를 떨어뜨리면 후보와 무관한 이유로 전부 탈락한다")
                .filteredOn(check -> check.name().contains("고위험 미탐")
                        || check.name().contains("HARD 위기 하향"))
                .hasSize(2)
                .allMatch(CandidateElimination.Check::passed);
    }

    @Test
    @DisplayName("기준선보다 안전이 나빠지면 여전히 즉시 탈락이다 — 개정이 문턱을 풀지 않았다")
    void baselineRelativeFloorsStillFailClosed() {
        CandidateElimination.Thresholds thresholds =
                CandidateElimination.thresholds(BenchmarkStage.SCREEN);
        CellRunner.Result baseline = runStub(BenchmarkCell.A, sample());
        CellMetrics.Population base = CellMetrics.of(baseline).modelDiscriminating();
        CellMetrics.Population worse = worseSafety(base);

        CandidateElimination.Verdict verdict = CandidateElimination.evaluate(thresholds,
                new CellVariant(BenchmarkCell.B, "더-나빠진-후보"), base, worse);

        assertThat(verdict.outcome()).isEqualTo(CandidateElimination.Outcome.ELIMINATED);
        assertThat(verdict.reason()).contains("고위험 미탐 증가");
    }

    /** 미탐과 HARD 하향만 기준선보다 한 건씩 늘린 모집단. 나머지는 그대로 둔다. */
    private static CellMetrics.Population worseSafety(CellMetrics.Population base) {
        return new CellMetrics.Population(base.name(), base.size(), base.grades(),
                base.hardCrisisTruths(), base.hardCrisisConfirmed(), base.hardCrisisDowngraded() + 1,
                base.riskPositives(), base.falseNegatives() + 1, base.crisisFalsePositives(),
                base.guardFalsePositives(), base.plannerScoreable(), base.plannerMatched(),
                base.cbtDeliveryJudged(), base.cbtDeliveryCompliant(),
                base.contractApplicable(), base.contractViolated(),
                base.contraindicationViolations(), base.acceptance(), base.inputJudgeCalls(),
                base.generationCalls(), base.escalations(), base.outputJudgeCalls(),
                base.cbtClassifierCalls(), base.truncatedGenerations(), base.timedOutCases(),
                base.llmCalls(), base.promptTokens(), base.completionTokens(),
                base.p50LatencyMs(), base.p95LatencyMs(), base.p50FirstSubstantiveMs(),
                base.p95FirstSubstantiveMs(), base.totalCostUsd(), base.costPerAcceptedResponse());
    }

    @Test
    @DisplayName("스크리닝 표가 '셀 B 는 입력 안전으로 후보를 변별하지 않는다' 를 항상 적는다")
    void screeningReportSaysWhatCellBCannotAnswer() {
        CellRunner.Result run = runStub(CellVariant.of(BenchmarkCell.A),
                CellModelRegistry.resolveForEstimate(BenchmarkCell.A, Map.of()), sample(),
                IDENTITY);
        String screening = CellScreeningReport.render(
                List.of(new CellScreeningReport.Row(CellVariant.of(BenchmarkCell.A),
                                CellMetrics.of(run), true),
                        new CellScreeningReport.Row(new CellVariant(BenchmarkCell.B, "후보"),
                                CellMetrics.of(run), true)),
                IDENTITY, List.of(), BenchmarkStage.SCREEN);

        assertThat(screening)
                .as("같은 안전 숫자가 '모든 모델이 똑같이 안전하다' 로 읽히면 안 된다")
                .contains(CellScreeningReport.CELL_B_CANNOT_DISCRIMINATE);
    }

    @Test
    @DisplayName("파레토는 세 축 모두에서 지는 후보만 지배로 표시하고, 단가 미상은 남긴다")
    void paretoKeepsUnknownCostCandidates() {
        ReportableRate high = ReportableRate.of("수용률", 190, 200);
        ReportableRate low = ReportableRate.of("수용률", 150, 200);
        var frontier = CandidateElimination.pareto(List.of(
                new CandidateElimination.Point("cheap-good",
                        java.util.Optional.of(new java.math.BigDecimal("0.001")), high, 1000, 190),
                new CandidateElimination.Point("dear-bad",
                        java.util.Optional.of(new java.math.BigDecimal("0.010")), low, 5000, 150),
                new CandidateElimination.Point("unpriced",
                        java.util.Optional.empty(), low, 5000, 150)));

        assertThat(frontier.dominatedBy()).containsEntry("dear-bad", "cheap-good");
        assertThat(frontier.onFrontier())
                .as("단가를 모르는 후보를 지배로 접으면 모르는 것을 나쁜 것으로 만든 것이다")
                .contains("cheap-good", "unpriced")
                .doesNotContain("dear-bad");
    }

    @Test
    @DisplayName("2·3단계는 후보를 자동으로 채우지 않는다 — 사람이 앞 단계 결과를 읽고 고른다")
    void laterStagesRequireExplicitCandidates() {
        CellCandidateRoster roster = CellCandidateRoster.load();

        assertThat(BenchmarkStage.SCREEN.candidates(roster, List.of()))
                .isEqualTo(roster.screeningCandidates());
        assertThat(BenchmarkStage.SEMIFINAL.candidates(roster, List.of("a", "b")))
                .containsExactly("a", "b");
        assertThatThrownBy(() -> BenchmarkStage.SEMIFINAL.candidates(roster, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("후보를 명시해야 한다");
        assertThat(BenchmarkStage.SCREEN.canProduceVerdict()).isFalse();
        assertThat(BenchmarkStage.FULL.canProduceVerdict()).isTrue();
        assertThatThrownBy(() -> BenchmarkStage.parse("전량"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("후보 목록의 오타·빈 항목은 조용히 무시되지 않는다")
    void candidateListRejectsBlankEntries() {
        assertThat(CellVariant.parseCandidates("a, b ,a")).containsExactly("a", "b");
        assertThat(CellVariant.parseCandidates(null)).isEmpty();
        assertThatThrownBy(() -> CellVariant.parseCandidates("a,,b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("빈 항목");
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
            CellRunner.Result pilot =
                    runStub(CellVariant.of(cell), registry, pilotCases, IDENTITY);
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
