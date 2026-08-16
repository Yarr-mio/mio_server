package com.mio.ai.qa;

import com.mio.ai.qa.LockedEvalSet.LockedCase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

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
 * # 전량 — 잠금 gold 323건
 * ./gradlew test -PllmTests -Pcells=A,B,C,D,E \
 *   -PcellModels="generation=<후보 ID>,escalation=<후보 ID>,reference_judge=<후보 ID>" \
 *   -PcellPrices="<후보 ID>=5.0/2.5/20.0" -PpricingAsOf=2026-08-16 \
 *   -PevalArchiveDir=docs/eval/runs \
 *   --tests "com.mio.ai.qa.CellBenchmarkLlmTest"
 * }</pre>
 *
 * <h2>파일럿이 증명하는 것과 증명하지 못하는 것</h2>
 *
 * <p>파일럿은 <b>경로</b>를 증명한다 — 모델 핀이 실제 요청에 반영되는가, 토큰·비용이 집계
 * 되는가, 아카이브가 써지는가. 안전 지표는 증명하지 못한다. 20건 표본의 미탐률은 보고 하한을
 * 한참 밑돌아 어떤 그룹 비교도 지지하지 못하고, 그 사실은 {@link CellGoNoGo} 가
 * {@code NOT_EVALUABLE} 로 못박는다.
 */
@Tag("llm-integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] A~E 셀 벤치마크 (실 LLM)")
class CellBenchmarkLlmTest {

    /** {@code -Pcells=A,D} → 실행할 셀. 비우면 전 셀. */
    private static final String CELLS_PROPERTY = "mio.eval.cells";
    /** {@code -PsampleSize=20} → 잠금 세트 표본 수. 비우면 전량. */
    private static final String SAMPLE_PROPERTY = "mio.eval.sampleSize";

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    @DisplayName("셀별 실행 → 리포트·아카이브 → 기준선 A 대비 Go/No-Go")
    void runCells() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && apiKey.startsWith("sk-"),
                "OPENAI_API_KEY 미설정 또는 placeholder — LLM 통합 테스트 skip");

        List<BenchmarkCell> cells = BenchmarkCell.parse(System.getProperty(CELLS_PROPERTY));
        List<LockedCase> cases = cases();
        boolean sampled = cases.size() < LockedEvalSet.CASES.size();

        Map<BenchmarkCell, CellRunner.Result> runs = new LinkedHashMap<>();
        Map<BenchmarkCell, CellMetrics> metrics = new LinkedHashMap<>();
        for (BenchmarkCell cell : cells) {
            // registry 해석이 여기서 실패하면 그 셀은 실행되지 않는다 — 상위 모델 후보를
            // 핀하지 않은 실행의 수치는 어느 모델의 것인지 확인할 수 없기 때문이다.
            CellModelRegistry registry = CellModelRegistry.resolve(cell);
            CellRunner.Result result = CellRunner.realLlm(cell, registry, apiKey).run(cases, sampled);
            CellMetrics cellMetrics = CellMetrics.of(result);
            String report = CellReport.render(result, cellMetrics);
            System.out.print(report);
            CellReport.archive(result, cellMetrics, report);
            runs.put(cell, result);
            metrics.put(cell, cellMetrics);
        }

        assertThat(runs).as("실행된 셀이 하나도 없다").isNotEmpty();
        appendVerdicts(runs, metrics);

        // 릴리스 게이트가 아니라 실행 무결성 검사다. 안전 하한 판정은 Go/No-Go 가 한다.
        runs.forEach((cell, result) -> assertThat(result.outcomes())
                .as("셀 %s 의 결과 건수가 입력과 다르다", cell)
                .hasSize(cases.size()));
    }

    /**
     * 기준선 A 가 같은 실행에 있을 때만 판정한다.
     *
     * <p>과거 실행의 A 와 비교하지 않는다. 코드·프롬프트·정책 버전이 다른 두 실행을 비교하면
     * 그 차이는 셀 차이가 아니다 — {@code EvalRunManifest} 가 존재하는 이유와 같은 문제다.
     */
    private void appendVerdicts(Map<BenchmarkCell, CellRunner.Result> runs,
                                Map<BenchmarkCell, CellMetrics> metrics) {
        if (!runs.containsKey(BenchmarkCell.A)) {
            System.out.println("\n  [Go/No-Go] 기준선 A 가 이번 실행에 없어 판정하지 않는다 "
                    + "— 다른 실행의 A 와 비교하면 코드·프롬프트 버전 차이가 셀 차이로 둔갑한다.");
            return;
        }
        runs.forEach((cell, result) -> {
            if (cell == BenchmarkCell.A) {
                return;
            }
            System.out.print(CellGoNoGo.evaluate(runs.get(BenchmarkCell.A),
                    metrics.get(BenchmarkCell.A), result, metrics.get(cell)).render());
        });
    }

    /** 표본은 하위 그룹 비율을 유지한 채 고정 시드로 뽑는다. 시드는 manifest 에 실린다. */
    private List<LockedCase> cases() {
        String sampleSize = System.getProperty(SAMPLE_PROPERTY);
        if (sampleSize == null || sampleSize.isBlank()) {
            return LockedEvalSet.CASES;
        }
        int size = Integer.parseInt(sampleSize.trim());
        return StratifiedSampler.sample(LockedEvalSet.CASES, LockedCase::subgroup, size,
                CellModelRegistry.DEFAULT_SEED);
    }
}
