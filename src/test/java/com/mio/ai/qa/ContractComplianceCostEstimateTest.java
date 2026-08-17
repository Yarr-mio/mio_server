package com.mio.ai.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약 준수 실측의 실행 전 견적 (이슈 #305). <b>태그가 없다 — 모델을 부르지 않는다.</b>
 *
 * <p>{@link CellCostEstimateTest} 와 같은 규칙을 따른다. 승인은 청구서를 본 뒤에 한다.
 *
 * <pre>{@code
 * ./gradlew test --tests "com.mio.ai.qa.ContractComplianceCostEstimateTest"
 * }</pre>
 *
 * <p>견적은 손으로 적은 토큰 수가 아니라 <b>스텁 클라이언트로 실제 하네스를 태워</b> 나온다
 * ({@code CellCostEstimator.projectVariants} → {@code CellRunner.stubbed}). 그래서 계약 팔의
 * 프롬프트가 대조군보다 긴 만큼 견적도 그만큼 는다 — 두 팔을 따로 잡는 이유가 그것이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] 계약 준수 실측 비용 견적 (모델 호출 없음)")
class ContractComplianceCostEstimateTest {

    /**
     * 이 실행의 상한. 넘기면 세트를 줄이거나 승인을 다시 받는다.
     *
     * <p>P0-8 3단계가 잠금 323건을 기준선 A 로 돌린 실비가 $0.33 이었다. 이 실행은 240턴
     * (120건 × 2팔)이고, 케이스가 전부 룰 승격 턴이라 InputJudge 를 매 턴 부른다 — 3단계는
     * 301턴 중 12턴만 불렀다. 그 차이를 감안해도 한 자릿수 달러 안이어야 한다.
     */
    private static final BigDecimal BUDGET_USD = new BigDecimal("3.00");

    private static Map<String, String> runtimePricePins() {
        Map<String, String> pins = new LinkedHashMap<>();
        System.getProperties().stringPropertyNames().stream()
                .filter(key -> key.startsWith(CellModelRegistry.PRICE_PROPERTY_PREFIX)
                        || key.equals(CellModelRegistry.PRICING_AS_OF_PROPERTY))
                .forEach(key -> pins.put(key, System.getProperty(key)));
        return pins;
    }

    private static List<CellVariant> arms() {
        return List.of(
                CellVariant.of(BenchmarkCell.A, ContractPromptArm.WITH_CONTRACT_BLOCK),
                CellVariant.of(BenchmarkCell.A, ContractPromptArm.WITHOUT_CONTRACT_BLOCK));
    }

    @Test
    @DisplayName("두 팔 전량 실행의 청구서를 출력한다 — 승인은 이 숫자를 보고 한다")
    void printsTheBillForBothArms() {
        var projection = CellCostEstimator.projectVariants(
                arms(), runtimePricePins(), ContractEvalSet.CASES);

        // 공용 렌더러는 머리글에 "잠금 세트" 라고 적는다 — 이 실행이 도는 세트를 먼저 밝힌다.
        System.out.printf("%n  [세트] %s (dev_gold, 튜닝 노출 %s)%n",
                ContractEvalSet.VERSION, ContractEvalSet.tuningExposure());
        System.out.print(CellCostEstimator.render(projection));
        System.out.printf("%n  [계약 A/B 견적] 세트 %d건 × 2팔 = %d턴 · 합계 $%s%n",
                ContractEvalSet.CASES.size(), ContractEvalSet.CASES.size() * 2,
                projection.totalKnownUsd().toPlainString());

        assertThat(projection.estimates()).hasSize(2);
        assertThat(projection.complete())
                .as("기준선 모델 단가는 application.yml 에 있어야 한다")
                .isTrue();
        assertThat(projection.estimates())
                .allSatisfy(estimate -> {
                    assertThat(estimate.cases()).isEqualTo(ContractEvalSet.CASES.size());
                    assertThat(estimate.llmCalls())
                            .as("모델을 한 번도 부르지 않는 견적은 경로가 죽은 것이다")
                            .isPositive();
                });
    }

    @Test
    @DisplayName("전량 실행이 예산 안이다")
    void staysWithinBudget() {
        var projection = CellCostEstimator.projectVariants(
                arms(), runtimePricePins(), ContractEvalSet.CASES);
        var upperBound = CellCostEstimator.pilotUpperBound(projection);

        System.out.printf("%n  [계약 A/B 상한] %s%n", CellPricingBook.format(upperBound));

        assertThat(upperBound).isPresent();
        assertThat(upperBound.get())
                .as("예산을 넘기면 승인 전에 세트 크기를 다시 정한다")
                .isLessThan(BUDGET_USD);
    }

    @Test
    @DisplayName("계약 팔이 대조군보다 프롬프트가 길다 — 견적이 그 차이를 반영한다")
    void contractArmCostsMoreInPromptTokens() {
        var projection = CellCostEstimator.projectVariants(
                arms(), runtimePricePins(), ContractEvalSet.CASES);
        var with = projection.estimates().get(0);
        var without = projection.estimates().get(1);

        System.out.printf("  [프롬프트 토큰] 계약 있음 %d · 없음 %d (차이 %d)%n",
                with.promptTokens(), without.promptTokens(),
                with.promptTokens() - without.promptTokens());

        assertThat(with.promptTokens())
                .as("계약 블록이 프롬프트에 실리는데 토큰이 같다면 A/B 가 아무것도 가르지 않은 것이다")
                .isGreaterThan(without.promptTokens());
    }

    @Test
    @DisplayName("계약 모집단이 견적 단계에서 이미 하한을 넘는다 — 실행 후에 알지 않는다")
    void populationIsKnownBeforePaying() {
        assertThat(ContractEvalSet.CASES.size())
                .as("P0-8 3단계는 323건을 돌린 뒤에야 계약 적용이 12건임을 알았다. "
                        + "그 순서를 뒤집는 것이 이 세트의 목적이다")
                .isGreaterThanOrEqualTo(LockedEvalSet.REPORTING.minSubgroupN());
    }
}
