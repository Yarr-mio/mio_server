package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmPricingProperties;
import com.mio.ai.policy.GenerationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실 LLM 측정 전에 돌리는 <b>무과금 견적</b> (이슈 #552). API 키가 필요 없다.
 *
 * <pre>{@code
 * # 평가용 세트 파일이 없을 때: 60건(정정 35 + 대조군 25) 규모의 대표 세트로 견적
 * ./gradlew test --tests "com.mio.ai.qa.correction.CorrectionCostEstimateTest" -i
 *
 * # 실제 세트 파일로 견적
 * MIO_EVAL_CORRECTION_SET_PATH=eval-private/correction-eval-v1.json \
 *   ./gradlew test --tests "com.mio.ai.qa.correction.CorrectionCostEstimateTest" -i
 * }</pre>
 *
 * <p>반복 횟수를 정하기 위한 표다 — 1·3·5회를 나란히 보여 준다. 채점 모델은
 * {@code MIO_EVAL_CORRECTION_JUDGE_MODEL} 로 바꿔 견적할 수 있다.
 */
@DisplayName("[QA] 정정 측정 비용 견적 표 (무과금)")
class CorrectionCostEstimateTest {

    @Test
    @DisplayName("반복 횟수별 호출 수와 비용 범위를 출력한다")
    void printsEstimateByRepeats() {
        String path = System.getenv(CorrectionEvalSetFileTest.PATH_ENV);
        boolean fromFile = path != null && !path.isBlank();
        CorrectionEvalSet set = fromFile
                ? CorrectionEvalSet.fromPath(Path.of(path))
                : CorrectionCostEstimator.syntheticSet(35, 25);
        List<GenerationMode> modes = List.of(GenerationMode.NORMAL, GenerationMode.SUPPORTIVE);
        List<CorrectionPromptArm> arms = List.of(CorrectionPromptArm.values());
        String generationModel = CorrectionGenerationRunner.productionModel();
        String judgeModel = CorrectionJudge.configuredModel();
        LlmPricingProperties pricing = CorrectionPricing.load();

        StringBuilder out = new StringBuilder(String.format(
                "%n[correction-estimate] %s · 케이스 %d건 · 생성 %s · 채점 %s%n"
                        + "  가정: 생성 응답 기대 %d토큰(상한 %d), 채점 응답 기대 %d토큰(상한 %d), 캐시 할인 미반영. "
                        + "입력 토큰은 문자 종류별 근사(오차 %.2f~%.2f배).%n",
                fromFile ? "세트 파일" : "대표 세트(파일 없음)", set.cases().size(), generationModel, judgeModel,
                CorrectionCostEstimator.EXPECTED_GENERATION_COMPLETION_TOKENS,
                CorrectionGenerationRunner.PRODUCTION_MAX_COMPLETION_TOKENS,
                CorrectionCostEstimator.EXPECTED_JUDGE_COMPLETION_TOKENS, CorrectionJudge.MAX_COMPLETION_TOKENS,
                com.mio.ai.qa.CellTokenEstimator.LOWER_MULTIPLIER, com.mio.ai.qa.CellTokenEstimator.UPPER_MULTIPLIER));
        CorrectionCostEstimator.Estimate previous = null;
        for (int repeats : new int[]{1, 3, 5}) {
            CorrectionCostEstimator.Estimate estimate = CorrectionCostEstimator.estimate(
                    set, repeats, modes, arms, generationModel, judgeModel, pricing);
            out.append(CorrectionCostEstimator.render(estimate, repeats, modes.size(), arms.size(),
                    generationModel, judgeModel));
            assertThat(estimate.priced()).as("단가가 등록되지 않은 모델이 있다: %s", estimate.unpricedModels()).isTrue();
            assertThat(estimate.lowUsd()).isLessThanOrEqualTo(estimate.expectedUsd());
            assertThat(estimate.expectedUsd()).isLessThanOrEqualTo(estimate.highUsd());
            if (previous != null) {
                assertThat(estimate.expectedUsd()).isGreaterThan(previous.expectedUsd());
            }
            previous = estimate;
        }
        System.out.print(out);
    }
}
