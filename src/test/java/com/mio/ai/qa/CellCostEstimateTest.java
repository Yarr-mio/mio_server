package com.mio.ai.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실행 전 비용 견적 (이슈 #454, 로드맵 §11.3 "경제성").
 *
 * <p><b>태그가 없다.</b> 기본 {@code ./gradlew test} 에서 돌고, 모델을 한 번도 부르지 않는다.
 * 누구든 API 키 없이 이 테스트 하나로 A~E 실행이 얼마짜리인지 볼 수 있어야 한다는 것이 요구
 * 사항이다 — 승인은 청구서를 본 뒤에 하는 것이다.
 *
 * <pre>{@code
 * ./gradlew test --tests "com.mio.ai.qa.CellCostEstimateTest"
 * }</pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] A~E 셀 벤치마크 비용 견적 (모델 호출 없음)")
class CellCostEstimateTest {

    /** 파일럿 상한. 이 값을 넘기는 파일럿은 파일럿이 아니다. */
    private static final BigDecimal PILOT_BUDGET_USD = new BigDecimal("0.50");

    private static final int PILOT_SAMPLE = 20;

    @Test
    @DisplayName("전량 실행 견적을 셀별·합계로 출력한다")
    void printFullRunProjection() {
        var projection = CellCostEstimator.project(
                List.of(BenchmarkCell.values()), LockedEvalSet.CASES);

        System.out.print(CellCostEstimator.render(projection));

        assertThat(projection.estimates()).hasSize(BenchmarkCell.values().length);
        assertThat(projection.estimates())
                .allSatisfy(estimate -> {
                    assertThat(estimate.cases()).isEqualTo(LockedEvalSet.CASES.size());
                    assertThat(estimate.llmCalls())
                            .as("셀 %s 가 모델을 한 번도 부르지 않는다면 견적 경로가 죽은 것이다",
                                    estimate.cell())
                            .isPositive();
                    assertThat(estimate.promptTokens()).isPositive();
                });
    }

    @Test
    @DisplayName("기준선 A 는 단가가 전부 등록돼 있어 금액이 나온다")
    void baselineCellHasCompletePricing() {
        var projection = CellCostEstimator.project(List.of(BenchmarkCell.A), LockedEvalSet.CASES);
        var estimate = projection.estimates().get(0);

        assertThat(estimate.complete())
                .as("기준선 모델의 단가는 application.yml 에 있어야 한다")
                .isTrue();
        assertThat(estimate.highUsd()).isGreaterThan(estimate.lowUsd());
        System.out.printf("%n  [셀 A 전량 견적] $%s ~ $%s%n",
                estimate.lowUsd().toPlainString(), estimate.highUsd().toPlainString());
    }

    @Test
    @DisplayName("상위 모델을 핀하지 않은 셀은 금액이 0 이 아니라 미상으로 남는다")
    void unpinnedFrontierCellsReportUnknownRatherThanZero() {
        var projection = CellCostEstimator.project(
                List.of(BenchmarkCell.B, BenchmarkCell.E), LockedEvalSet.CASES);

        assertThat(projection.complete()).isFalse();
        assertThat(projection.estimates())
                .allSatisfy(estimate -> assertThat(estimate.unknownModels())
                        .containsKey(CellModelRegistry.UNPINNED_PLACEHOLDER));
        assertThat(CellCostEstimator.pilotUpperBound(projection)).isEmpty();
        assertThat(CellCostEstimator.render(projection))
                .contains("단가 미상")
                .contains("0 이 아니라 '아직 모른다'");
    }

    @Test
    @DisplayName("파일럿(-Pcells=A,D -PsampleSize=20) 견적이 예산 안이다")
    void pilotStaysWithinBudget() {
        var cases = StratifiedSampler.sample(LockedEvalSet.CASES,
                LockedEvalSet.LockedCase::subgroup, PILOT_SAMPLE, CellModelRegistry.DEFAULT_SEED);
        // 파일럿에서 상위 모델을 핀하지 않으면 D 의 escalation 모델이 미상이라 금액이 안 나온다.
        // 파일럿의 목적은 경로 검증이므로 기준선 A 와 경량 생성만으로 상한을 잡는다.
        var projection = CellCostEstimator.project(List.of(BenchmarkCell.A), cases);
        var upperBound = CellCostEstimator.pilotUpperBound(projection);

        System.out.printf("%n  [파일럿 견적] %d건 · 상한 %s%n", cases.size(),
                CellPricingBook.format(upperBound));

        assertThat(upperBound).isPresent();
        assertThat(upperBound.get())
                .as("파일럿이 예산을 넘기면 그건 파일럿이 아니라 축소 실행이다")
                .isLessThan(PILOT_BUDGET_USD);
    }

    @Test
    @DisplayName("토큰 추정은 점값이 아니라 구간으로 나온다")
    void estimateIsRangeNotPoint() {
        assertThat(CellTokenEstimator.LOWER_MULTIPLIER).isLessThan(1.0);
        assertThat(CellTokenEstimator.UPPER_MULTIPLIER).isGreaterThan(1.0);
        assertThat(CellTokenEstimator.tokens("안녕하세요")).isPositive();
        assertThat(CellTokenEstimator.tokens("")).isZero();
        assertThat(CellTokenEstimator.tokens(null)).isZero();
    }

    @Test
    @DisplayName("단가는 application.yml 에서 읽고, 등록되지 않은 모델은 미상이다")
    void pricingComesFromApplicationYaml() {
        CellPricingBook pricing = CellPricingBook.load(java.util.Map.of(), null);

        assertThat(pricing.prices()).containsKeys("gpt-4o", "gpt-4o-mini");
        assertThat(pricing.originOf("gpt-4o")).isEqualTo(CellPricingBook.SOURCE);
        assertThat(pricing.costUsd("gpt-4o", 1_000_000, 0, 0))
                .hasValueSatisfying(usd -> assertThat(usd).isEqualByComparingTo("2.50"));
        assertThat(pricing.costUsd("모르는-모델", 1_000_000, 0, 0)).isEmpty();
        assertThat(pricing.pricingAsOf()).isEqualTo(EvalRunManifest.PRICING_DATE_UNRECORDED);
    }
}
