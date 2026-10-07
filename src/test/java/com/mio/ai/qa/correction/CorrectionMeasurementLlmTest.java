package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정정 대응 측정 실행 진입점 (이슈 #552). <b>실 LLM 을 부른다 — 과금된다.</b>
 *
 * <p>시험 세트를 <b>생성 모드 × 팔(기준선/후보) × 케이스 × 반복</b> 으로 돌려 응답을 만들고,
 * 채점기로 채점해, 집계 리포트를 출력하고 결과를 로컬에 저장한다.
 *
 * <h2>실행 전에</h2>
 * <ol>
 *   <li>{@code CorrectionCostEstimateTest} 로 비용 견적을 본다 (무과금).</li>
 *   <li>평가용 세트라면 <b>리포트를 SUMMARY 로 둔다</b> (기본값). DETAIL 은 튜닝용 세트 전용이다.</li>
 * </ol>
 *
 * <pre>{@code
 * OPENAI_API_KEY=sk-... \
 * MIO_EVAL_CORRECTION_SET_PATH=eval-private/correction-eval-v1.json \
 * MIO_EVAL_CORRECTION_REPEATS=3 \
 *   ./gradlew test -PllmTests --tests "com.mio.ai.qa.correction.CorrectionMeasurementLlmTest" -i
 * }</pre>
 *
 * <p>설정 환경 변수는 {@link CorrectionRunConfig} 에 있다. {@code -PllmTests} 없이는 이 테스트가 돌지
 * 않는다 (다른 실 LLM 테스트와 같은 규칙).
 *
 * <h2>이 테스트가 검사하는 것</h2>
 *
 * <p>결과의 <b>방향</b>(후보가 더 좋은가)은 검사하지 않는다 — 결과를 미리 정해 두고 통과시키는 평가는
 * 평가가 아니다. 검사하는 것은 "이 실행이 인용할 수 있는 값을 냈는가"뿐이다: 생성 실패와 채점 실패가
 * 상한을 넘으면 남은 표본은 무작위 표본이 아니므로 실패로 처리한다.
 */
@Tag("llm-integration")
@DisplayName("[QA] 정정 대응 측정 실행 (실 LLM)")
class CorrectionMeasurementLlmTest {

    /** 시작 기준(근거 있는 숫자가 아님). 실행이 이보다 많이 실패하면 남은 표본을 인용하지 않는다. */
    static final double MAX_EXTERNAL_FAILURE_SHARE = 0.10;

    static String judgeLabel(String judgeModel, int votes) {
        return votes > 1 ? judgeModel + " ×" + votes + "투표" : judgeModel;
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.MINUTES)
    @DisplayName("세트를 모드 × 팔 × 반복으로 돌려 채점하고 집계 리포트를 출력한다")
    void measure() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && apiKey.startsWith("sk-"),
                "OPENAI_API_KEY 가 없다 — 실 LLM 정정 측정을 건너뛴다");
        Assumptions.assumeTrue(System.getenv(CorrectionRunConfig.SET_PATH) != null,
                CorrectionRunConfig.SET_PATH + " 가 없다 — 정정 측정을 건너뛴다");

        CorrectionRunConfig config = CorrectionRunConfig.fromEnv(System::getenv);
        byte[] setBytes = Files.readAllBytes(config.setPath());
        CorrectionEvalSet fullSet = CorrectionEvalSet.fromPath(config.setPath());
        CorrectionEvalSet set = config.sampleSize() > 0 ? fullSet.sample(config.sampleSize()) : fullSet;
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(setBytes));

        CorrectionCostLedger ledger = new CorrectionCostLedger();
        LlmClient client = CorrectionClients.real(apiKey, ledger);
        String judgeModel = CorrectionJudge.configuredModel();
        int votes = CorrectionJudge.configuredVotes();
        CorrectionJudge judge = new CorrectionJudge(client, judgeModel, CorrectionEvalRunner.JUDGE_COMPONENT, votes)
                .withRetries(3, 1500);
        CorrectionRunIdentity identity = CorrectionRunIdentity.stamp(set.version(), sha256, config.repeats(),
                config.modes().stream().map(Enum::name).toList(),
                config.arms().stream().map(Enum::name).toList(),
                CorrectionGenerationRunner.productionModel(), judgeLabel(judgeModel, votes));

        System.out.print(CorrectionEvalSetSummary.of(set).render(sha256));

        // 비용 상한: 견적 상한이 예산을 넘으면 호출을 시작하지 않는다.
        CorrectionCostEstimator.Estimate estimate = CorrectionCostEstimator.estimate(set, config.repeats(),
                config.modes(), config.arms(), CorrectionGenerationRunner.productionModel(), judgeModel,
                CorrectionPricing.load());
        System.out.print(CorrectionCostEstimator.render(estimate, config.repeats(), config.modes().size(),
                config.arms().size(), CorrectionGenerationRunner.productionModel(), judgeModel));
        System.out.printf("  %s%n", config.budget().describe());
        config.budget().requireEstimateWithin(estimate);

        CorrectionEvalRunner.Result result = new CorrectionEvalRunner(client, judge,
                CorrectionEvalRunner.GENERATION_COMPONENT)
                .run(set, config.modes(), config.arms(), config.repeats(), config.parallelism(), identity,
                        () -> config.budget().exceeded(ledger));

        System.out.print(CorrectionReport.render(result, ledger, config.detail()));
        System.out.printf("  [채점 호출 실패 사유] %s%n", judge.failureReasons().isEmpty() ? "없음" : judge.failureReasons());
        Path archived = CorrectionRunArchive.write(result, config.archiveDir());
        System.out.printf("%n[correction-eval] 결과 저장(로컬, 커밋 금지): %s%n", archived);

        assertThat(result.budgetStopped())
                .as("비용 상한에 닿아 실행이 중단됐다 — 결과를 인용할 수 없다. %s", config.budget().describe())
                .isFalse();
        assertThat(CorrectionReport.generationFailureShare(result))
                .as("생성 호출 실패 비율이 상한을 넘었다 — 남은 표본은 무작위 표본이 아니므로 인용할 수 없다")
                .isLessThanOrEqualTo(MAX_EXTERNAL_FAILURE_SHARE);
        assertThat(CorrectionReport.judgeFailureShare(result))
                .as("채점 실패 비율이 상한을 넘었다 — 채점 모델·출력 상한·기준표를 확인한다")
                .isLessThanOrEqualTo(MAX_EXTERNAL_FAILURE_SHARE);
        assertThat(List.of(result.samples())).isNotEmpty();
    }
}
