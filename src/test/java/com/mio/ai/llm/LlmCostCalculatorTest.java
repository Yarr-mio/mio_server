package com.mio.ai.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCostCalculatorTest {

    @Test
    @DisplayName("입력·출력 단가를 각각 적용해 합산한다")
    void computesCostFromBothTokenKinds() {
        LlmCostCalculator calculator = calculator(Map.of("gpt-4o",
                new LlmPricingProperties.ModelPrice(new BigDecimal("2.50"), null, new BigDecimal("10.00"))));

        // 2.50*2000/1e6 + 10.00*800/1e6 = 0.005 + 0.008
        assertThat(calculator.costUsd(LlmUsage.of("gpt-4o", 2000, 800)))
                .isEqualByComparingTo(new BigDecimal("0.013"));
    }

    @Test
    @DisplayName("캐시 적중 토큰은 정가가 아니라 캐시 할인 단가로 계산한다")
    void cachedTokensUseCachedInputPrice() {
        LlmCostCalculator calculator = calculator(Map.of("gpt-4o",
                new LlmPricingProperties.ModelPrice(
                        new BigDecimal("2.50"), new BigDecimal("1.25"), new BigDecimal("10.00"))));

        // promptTokens=2000 중 800은 캐시 적중: (2000-800)*2.50/1e6 + 800*1.25/1e6 + 800*10.00/1e6
        // = 0.003 + 0.001 + 0.008 = 0.012 (캐시 할인 없으면 0.013)
        assertThat(calculator.costUsd(LlmUsage.of("gpt-4o", 2000, 800, 800)))
                .isEqualByComparingTo(new BigDecimal("0.012"));
    }

    @Test
    @DisplayName("캐시 단가가 등록되지 않은 모델은 캐시 적중분도 정가로 계산한다")
    void cachedTokensFallBackToInputPriceWhenNoCachedPriceConfigured() {
        LlmCostCalculator calculator = calculator(Map.of("text-embedding-3-small",
                new LlmPricingProperties.ModelPrice(new BigDecimal("0.02"), null, new BigDecimal("0.00"))));

        assertThat(calculator.costUsd(LlmUsage.of("text-embedding-3-small", 1_000_000, 0, 500_000)))
                .isEqualByComparingTo(new BigDecimal("0.02"));
    }

    @Test
    @DisplayName("사용량을 못 받았으면 0이 아니라 null")
    void unresolvedUsageHasNoCost() {
        LlmCostCalculator calculator = calculator(Map.of("gpt-4o",
                new LlmPricingProperties.ModelPrice(new BigDecimal("2.50"), null, new BigDecimal("10.00"))));

        assertThat(calculator.costUsd(LlmUsage.unresolved("gpt-4o"))).isNull();
        assertThat(calculator.costUsd(null)).isNull();
    }

    @Test
    @DisplayName("단가가 등록되지 않은 모델은 0이 아니라 null — 등록 누락을 묻지 않는다")
    void unknownModelHasNoCost() {
        LlmCostCalculator calculator = calculator(Map.of());

        assertThat(calculator.costUsd(LlmUsage.of("gpt-5-future", 100, 50))).isNull();
    }

    @Test
    @DisplayName("단가 설정이 깨져 있으면(누락·음수) 계산하지 않는다")
    void invalidPriceIsTreatedAsUnknown() {
        LlmCostCalculator calculator = calculator(Map.of(
                "half-configured", new LlmPricingProperties.ModelPrice(new BigDecimal("1.00"), null, null),
                "negative", new LlmPricingProperties.ModelPrice(new BigDecimal("-1.00"), null, BigDecimal.ONE)));

        assertThat(calculator.costUsd(LlmUsage.of("half-configured", 100, 50))).isNull();
        assertThat(calculator.costUsd(LlmUsage.of("negative", 100, 50))).isNull();
    }

    @Test
    @DisplayName("출력 단가가 0인 임베딩 모델도 입력 비용은 계산한다")
    void embeddingModelCostsInputOnly() {
        LlmCostCalculator calculator = calculator(Map.of("text-embedding-3-small",
                new LlmPricingProperties.ModelPrice(new BigDecimal("0.02"), null, new BigDecimal("0.00"))));

        assertThat(calculator.costUsd(LlmUsage.of("text-embedding-3-small", 1_000_000, 0)))
                .isEqualByComparingTo(new BigDecimal("0.02"));
    }

    @Test
    @DisplayName("아주 작은 비용이 0으로 반올림되지 않는다 — 반올림되면 임베딩 비용이 영원히 0으로 쌓인다")
    void tinyCostSurvivesRounding() {
        LlmCostCalculator calculator = calculator(Map.of("text-embedding-3-small",
                new LlmPricingProperties.ModelPrice(new BigDecimal("0.02"), null, new BigDecimal("0.00"))));

        // 24 토큰 * $0.02/1M = 4.8e-7 — 실제 운영 스크레이프에서 0.0 으로 사라졌던 값이다.
        assertThat(calculator.costUsd(LlmUsage.of("text-embedding-3-small", 24, 0)))
                .isEqualByComparingTo(new BigDecimal("0.00000048"))
                .isNotEqualByComparingTo(BigDecimal.ZERO);
    }

    private LlmCostCalculator calculator(Map<String, LlmPricingProperties.ModelPrice> models) {
        LlmPricingProperties properties = new LlmPricingProperties();
        properties.setModels(models);
        return new LlmCostCalculator(properties);
    }
}
