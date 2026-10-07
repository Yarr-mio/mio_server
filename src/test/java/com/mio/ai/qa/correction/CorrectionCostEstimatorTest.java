package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmPricingProperties;
import com.mio.ai.policy.GenerationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[QA] 정정 측정 비용 견적 (무과금)")
class CorrectionCostEstimatorTest {

    private static final List<GenerationMode> MODES = List.of(GenerationMode.NORMAL, GenerationMode.SUPPORTIVE);
    private static final List<CorrectionPromptArm> ARMS = List.of(CorrectionPromptArm.values());

    /** 100만 토큰당 100만 달러 = 토큰 1개 1달러. 그러면 비용이 곧 토큰 수라 산술을 눈으로 검산할 수 있다. */
    private static LlmPricingProperties dollarPerToken() {
        LlmPricingProperties pricing = new LlmPricingProperties();
        BigDecimal million = new BigDecimal("1000000");
        pricing.setModels(Map.of(
                "gen", new LlmPricingProperties.ModelPrice(million, null, million),
                "judge", new LlmPricingProperties.ModelPrice(million, null, million)));
        return pricing;
    }

    @Test
    @DisplayName("호출 수는 케이스 × 반복 × 팔 × 모드이고, 생성과 채점이 같다")
    void countsCalls() {
        CorrectionEvalSet set = CorrectionCostEstimator.syntheticSet(5, 5);

        CorrectionCostEstimator.Estimate estimate = CorrectionCostEstimator.estimate(
                set, 3, MODES, ARMS, "gen", "judge", dollarPerToken());

        assertThat(estimate.generationCalls()).isEqualTo(10 * 3 * 2 * 2);
        assertThat(estimate.judgeCalls()).isEqualTo(estimate.generationCalls());
    }

    @Test
    @DisplayName("기대 비용은 입력 토큰 + 기대 출력 토큰이고, 범위는 낮은 값 ≤ 기대 ≤ 높은 값이다")
    void expectedCostIsPromptPlusExpectedCompletionWithinRange() {
        CorrectionEvalSet set = CorrectionCostEstimator.syntheticSet(5, 5);

        CorrectionCostEstimator.Estimate e = CorrectionCostEstimator.estimate(
                set, 2, MODES, ARMS, "gen", "judge", dollarPerToken());

        long expectedCompletion = (long) e.generationCalls()
                * (CorrectionCostEstimator.EXPECTED_GENERATION_COMPLETION_TOKENS
                + CorrectionCostEstimator.EXPECTED_JUDGE_COMPLETION_TOKENS);
        assertThat(e.expectedUsd().doubleValue())
                .isEqualTo((double) (e.generationPromptTokens() + e.judgePromptTokens() + expectedCompletion));
        assertThat(e.lowUsd()).isLessThan(e.expectedUsd());
        assertThat(e.highUsd()).isGreaterThan(e.expectedUsd());
        assertThat(e.priced()).isTrue();
    }

    @Test
    @DisplayName("반복 횟수에 비례해 비용이 늘어난다")
    void costScalesLinearlyWithRepeats() {
        CorrectionEvalSet set = CorrectionCostEstimator.syntheticSet(5, 5);

        BigDecimal one = CorrectionCostEstimator.estimate(set, 1, MODES, ARMS, "gen", "judge", dollarPerToken())
                .expectedUsd();
        BigDecimal five = CorrectionCostEstimator.estimate(set, 5, MODES, ARMS, "gen", "judge", dollarPerToken())
                .expectedUsd();

        assertThat(five.doubleValue()).isCloseTo(one.doubleValue() * 5, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("후보 팔의 프롬프트가 더 길어 후보 팔만 넣으면 기준선만보다 입력 토큰이 많다")
    void candidateArmCostsMorePromptTokens() {
        CorrectionEvalSet set = CorrectionCostEstimator.syntheticSet(5, 5);

        long baselineOnly = CorrectionCostEstimator.estimate(set, 1, MODES,
                List.of(CorrectionPromptArm.BASELINE), "gen", "judge", dollarPerToken()).generationPromptTokens();
        long candidateOnly = CorrectionCostEstimator.estimate(set, 1, MODES,
                List.of(CorrectionPromptArm.WITH_CORRECTION_BLOCK), "gen", "judge", dollarPerToken())
                .generationPromptTokens();

        assertThat(candidateOnly).isGreaterThan(baselineOnly);
    }

    @Test
    @DisplayName("단가 미등록 모델은 비용을 0 으로 접지 않고 미등록으로 표시한다")
    void reportsUnpricedModels() {
        CorrectionEvalSet set = CorrectionCostEstimator.syntheticSet(2, 2);

        CorrectionCostEstimator.Estimate estimate = CorrectionCostEstimator.estimate(
                set, 1, MODES, ARMS, "gen", "no-such-model", dollarPerToken());

        assertThat(estimate.priced()).isFalse();
        assertThat(estimate.unpricedModels()).containsExactly("no-such-model");
        assertThat(CorrectionCostEstimator.render(estimate, 1, 2, 2, "gen", "no-such-model"))
                .contains("단가 미등록 모델 [no-such-model]");
    }
}
