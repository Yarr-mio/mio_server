package com.mio.ai.qa;

import com.mio.ai.qa.LockedEvalSet.LockedCase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A~E 셀 벤치마크 실행 (이슈 #454, 로드맵 §11.3).
 *
 * <p><b>실 LLM 을 호출하므로 과금된다.</b> 기본 {@code ./gradlew test} 에서는 제외되며
 * {@code -PllmTests} 로만 실행된다. 돌리기 전에 반드시 견적을 먼저 본다 —
 * {@link CellCostEstimateTest} 는 태그가 없어 아무나 돌릴 수 있다.
 *
 * <h2>실행</h2>
 *
 * <pre>{@code
 * # 파일럿 — 경로 검증용 소액 실행
 * ./gradlew test -PllmTests -Pcells=A,D -PsampleSize=20 \
 *   -PcellModels="escalation=<후보 ID>" \
 *   --tests "com.mio.ai.qa.CellBenchmarkLlmTest"
 *
 * # 상위 모델 후보 스크리닝 — 후보 여럿을 한 실행에서 같은 기준선에 붙인다
 * ./gradlew test -PllmTests -Pcells=A,B -PsampleSize=60 \
 *   -PfrontierCandidates="<후보1>,<후보2>,<후보3>" \
 *   -PcellPrices="<후보1>=2.0/0.2/12.0,<후보2>=5.0/0.5/30.0" \
 *   -PpricingAsOf=2026-08-16 \
 *   --tests "com.mio.ai.qa.CellBenchmarkLlmTest"
 *
 * # 전량 — 잠금 gold 323건
 * ./gradlew test -PllmTests -Pcells=A,B,C,D,E \
 *   -PcellModels="generation=<후보 ID>,escalation=<후보 ID>,reference_judge=<후보 ID>" \
 *   -PcellPrices="<후보 ID>=5.0/2.5/20.0" -PpricingAsOf=2026-08-16 \
 *   -PevalArchiveDir=docs/eval/runs \
 *   --tests "com.mio.ai.qa.CellBenchmarkLlmTest"
 * }</pre>
 *
 * <h2>파일럿·스크리닝이 증명하는 것과 증명하지 못하는 것</h2>
 *
 * <p>둘 다 <b>경로와 상대 순위</b>를 증명한다 — 모델 핀이 실제 요청에 반영되는가, 토큰·비용이
 * 집계되는가, 후보 사이에 눈에 띄는 차이가 있는가. 안전 지표는 증명하지 못한다. 표본의
 * 미탐률은 보고 하한을 밑돌아 어떤 그룹 비교도 지지하지 못하고, 그 사실은 {@link CellGoNoGo}
 * 가 {@code NOT_EVALUABLE} 로 못박는다.
 */
@Tag("llm-integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] A~E 셀 벤치마크 (실 LLM)")
class CellBenchmarkLlmTest {

    /** {@code -Pcells=A,D} → 실행할 셀. 비우면 전 셀. */
    private static final String CELLS_PROPERTY = "mio.eval.cells";
    /** {@code -PsampleSize=20} → 잠금 세트 표본 수. 비우면 전량. */
    private static final String SAMPLE_PROPERTY = "mio.eval.sampleSize";
    /** {@code -PfrontierCandidates="a,b,c"} → 상위 모델 후보 목록. 비우면 registry 핀 그대로. */
    private static final String CANDIDATES_PROPERTY = "mio.eval.frontierCandidates";
    /** {@code -Pstage=screen|semifinal|full} → 깔때기 단계. 표본 수·후보 정책이 여기서 나온다. */
    private static final String STAGE_PROPERTY = "mio.eval.stage";
    /** {@code -PlatencyProbe=20} → batch 모드에서 지연만 따로 재는 동기 표본 수. */
    private static final String PROBE_PROPERTY = "mio.eval.latencyProbe";

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    @DisplayName("셀·후보별 실행 → 리포트·아카이브 → 스크리닝 표 → 기준선 A 대비 Go/No-Go")
    void runCells() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && apiKey.startsWith("sk-"),
                "OPENAI_API_KEY 미설정 또는 placeholder — LLM 통합 테스트 skip");

        BenchmarkStage stage = BenchmarkStage.parse(System.getProperty(STAGE_PROPERTY));
        CellCandidateRoster roster = CellCandidateRoster.load();
        boolean batchQuality = BatchQualityMode.enabled();
        if (stage != null) {
            System.out.print(roster.render());
            System.out.printf("%n  [단계] %s%n", stage.describe());
        }

        List<BenchmarkCell> cells = BenchmarkCell.parse(System.getProperty(CELLS_PROPERTY));
        List<String> explicit =
                CellVariant.parseCandidates(System.getProperty(CANDIDATES_PROPERTY));
        List<String> candidates = stage == null ? explicit : stage.candidates(roster, explicit);
        if (batchQuality) {
            // 적격성부터 막는다. 여기서 통과해도 전송 계층이 아직 없어 실 실행은 멈춘다 —
            // 조용히 동기로 되돌아가면 batch 로 돌린 줄 알고 동기 청구서를 받게 된다.
            BatchQualityMode.requireEligible(stage, cells);
            BatchQualityMode.requireTransport(false);
        }
        List<CellVariant> variants = CellVariant.expand(cells, candidates);
        List<LockedCase> cases = cases(stage);
        boolean sampled = cases.size() < LockedEvalSet.CASES.size();

        // 실행 도장은 여기서 <b>한 번만</b> 찍는다. 아래 모든 결과가 같은 도장을 들고 나가고,
        // Go/No-Go 는 도장이 같은 결과끼리만 비교한다.
        RunIdentity identity = RunIdentity.stamp(
                System.getProperty(CellModelRegistry.PRICING_AS_OF_PROPERTY,
                        EvalRunManifest.PRICING_DATE_UNRECORDED));
        System.out.printf("%n  [실행 도장] run_id=%s · 변형 %s%n",
                identity.runId(), CellVariant.describe(variants));

        Map<String, CellRunner.Result> runs = new LinkedHashMap<>();
        Map<String, CellMetrics> metrics = new LinkedHashMap<>();
        for (CellVariant variant : variants) {
            // registry 해석이 여기서 실패하면 그 변형은 실행되지 않는다 — 상위 모델 후보를
            // 핀하지 않은 실행의 수치는 어느 모델의 것인지 확인할 수 없기 때문이다.
            CellModelRegistry registry = CellModelRegistry.resolveForVariant(variant);
            CellRunner.Result result = CellRunner.realLlm(variant, registry, apiKey)
                    .run(cases, sampled, identity);
            CellMetrics cellMetrics = CellMetrics.of(result);
            String report = CellReport.render(result, cellMetrics);
            System.out.print(report);
            CellReport.archive(result, cellMetrics, report);
            runs.put(variant.label(), result);
            metrics.put(variant.label(), cellMetrics);
        }

        assertThat(runs).as("실행된 변형이 하나도 없다").isNotEmpty();
        appendLatencyProbe(variants, apiKey, identity, batchQuality);
        appendScreening(variants, runs, metrics, identity, stage);
        assertCellCMatchesA(variants, runs, metrics);
        appendVerdicts(variants, runs, metrics);

        // 릴리스 게이트가 아니라 실행 무결성 검사다. 안전 하한 판정은 Go/No-Go 가 한다.
        runs.forEach((label, result) -> assertThat(result.outcomes())
                .as("변형 %s 의 결과 건수가 입력과 다르다", label)
                .hasSize(cases.size()));
    }

    /**
     * 동기 지연 프로브.
     *
     * <p>batch 품질 모드는 스트리밍이 없어 p95 를 낼 수 없다. 그렇다고 지연 탈락을 다음
     * 단계로 미루면 느린 후보가 비싼 단계까지 올라간다. 그래서 작은 표본만 <b>동기 스트리밍
     * 경로로</b> 다시 돌려 지연만 잰다 — 품질은 batch 로, 지연은 프로브로 나눠 재는 구조다.
     *
     * <p>지연을 이미 잰 실행(동기 실행)에서는 돌리지 않는다. 같은 값을 두 번 재면서 돈만 쓴다.
     */
    private void appendLatencyProbe(List<CellVariant> variants, String apiKey,
                                    RunIdentity identity, boolean batchQuality) throws Exception {
        int probeSize = Integer.parseInt(System.getProperty(PROBE_PROPERTY, "0"));
        if (!batchQuality || probeSize <= 0) {
            return;
        }
        List<LockedCase> probeCases = StratifiedSampler.sample(LockedEvalSet.CASES,
                LockedCase::subgroup, probeSize, CellModelRegistry.DEFAULT_SEED);
        System.out.printf("%n  [동기 지연 프로브 — %d건, 스트리밍 실경로]%n", probeCases.size());
        System.out.println("    ** 품질 지표는 batch 결과를 쓴다. 이 표는 지연만 말한다. **");
        for (CellVariant variant : variants) {
            CellRunner.Result probe = CellRunner
                    .realLlm(variant, CellModelRegistry.resolveForVariant(variant), apiKey)
                    .run(probeCases, true, identity);
            CellMetrics.Population population = CellMetrics.of(probe).modelDiscriminating();
            System.out.printf("    %-20s p50 %d / p95 %d ms · 첫 실질 토큰 p50 %d / p95 %d ms%n",
                    variant.label(), population.p50LatencyMs(), population.p95LatencyMs(),
                    population.p50FirstSubstantiveMs(), population.p95FirstSubstantiveMs());
        }
    }

    /**
     * 후보 스크리닝 표.
     *
     * <p>후보를 하나도 주지 않은 실행에서는 찍지 않는다 — 비교할 후보가 없는 표는 셀 리포트를
     * 한 번 더 요약한 것뿐이고, 요약이 늘면 어느 것이 원본인지 흐려진다.
     */
    private void appendScreening(List<CellVariant> variants, Map<String, CellRunner.Result> runs,
                                 Map<String, CellMetrics> metrics, RunIdentity identity,
                                 BenchmarkStage stage) {
        if (CellVariant.candidatesOf(variants).isEmpty()) {
            return;
        }
        List<CellScreeningReport.Row> rows = new ArrayList<>();
        variants.forEach(variant -> {
            CellRunner.Result result = runs.get(variant.label());
            rows.add(new CellScreeningReport.Row(variant, metrics.get(variant.label()),
                    result.sampled()));
        });
        System.out.print(CellScreeningReport.render(rows, identity,
                CellScreeningReport.unpricedCandidates(List.copyOf(runs.values())), stage));
    }

    /**
     * 셀 C 의 운영 경로가 셀 A 와 같은지 단언한다.
     *
     * <p>{@link CellParity} 가 단언하는 것은 구성의 동일성이다. 원가·p95 의 수치 동일성은
     * 단언하지 않는다 — 같은 모델이라도 샘플링으로 달라지므로, 그것을 단언하면 오염이 아니라
     * 샘플링을 잡는다.
     */
    private void assertCellCMatchesA(List<CellVariant> variants,
                                     Map<String, CellRunner.Result> runs,
                                     Map<String, CellMetrics> metrics) {
        CellRunner.Result baseline = runs.get(BenchmarkCell.A.name());
        if (baseline == null) {
            return;
        }
        variants.stream()
                .filter(variant -> variant.cell() == BenchmarkCell.C)
                .forEach(variant -> {
                    CellParity.Result parity = CellParity.check(baseline,
                            metrics.get(BenchmarkCell.A.name()), runs.get(variant.label()),
                            metrics.get(variant.label()));
                    System.out.print(parity.render());
                    assertThat(parity.violations())
                            .as("셀 %s 의 운영 경로가 A 와 다르다 — 셀 C 의 '운영비 증가 없음' 전제가 깨졌다",
                                    variant.label())
                            .isEmpty();
                });
    }

    /**
     * 기준선 A 가 같은 실행에 있을 때만 판정한다.
     *
     * <p>과거 실행의 A 와 비교하지 않는다. 코드·프롬프트·정책 버전이 다른 두 실행을 비교하면
     * 그 차이는 셀 차이가 아니다. 예전에는 그것을 "호출부가 같은 맵만 본다" 는 사실에 기댔지만,
     * 이제는 {@link RunIdentity} 도장이 {@link CellGoNoGo} 안에서 직접 막는다.
     */
    private void appendVerdicts(List<CellVariant> variants, Map<String, CellRunner.Result> runs,
                                Map<String, CellMetrics> metrics) {
        CellRunner.Result baseline = runs.get(BenchmarkCell.A.name());
        if (baseline == null) {
            System.out.println("\n  [Go/No-Go] 기준선 A 가 이번 실행에 없어 판정하지 않는다 "
                    + "— 다른 실행의 A 와 비교하면 코드·프롬프트 버전 차이가 셀 차이로 둔갑한다.");
            return;
        }
        CellMetrics baselineMetrics = metrics.get(BenchmarkCell.A.name());
        variants.stream()
                .filter(variant -> variant.cell() != BenchmarkCell.A)
                .forEach(variant -> System.out.print(CellGoNoGo.evaluate(baseline, baselineMetrics,
                        runs.get(variant.label()), metrics.get(variant.label())).render()));
    }

    /**
     * 표본은 하위 그룹 비율을 유지한 채 고정 시드로 뽑는다. 시드는 manifest 에 실린다.
     *
     * <p>{@code -PsampleSize} 가 단계 기본값을 이긴다 — 단계를 쓰면서도 표본을 줄여 리허설할
     * 수 있어야 하기 때문이다. 다만 줄인 표본이 안전 판정을 만들어 주지는 않는다.
     */
    private List<LockedCase> cases(BenchmarkStage stage) {
        String sampleSize = System.getProperty(SAMPLE_PROPERTY);
        int size = sampleSize != null && !sampleSize.isBlank()
                ? Integer.parseInt(sampleSize.trim())
                : (stage == null ? BenchmarkStage.ALL : stage.sampleSize());
        if (size == BenchmarkStage.ALL) {
            return LockedEvalSet.CASES;
        }
        return StratifiedSampler.sample(LockedEvalSet.CASES, LockedCase::subgroup, size,
                CellModelRegistry.DEFAULT_SEED);
    }
}
