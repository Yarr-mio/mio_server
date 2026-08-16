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

    /**
     * 실행 시점에 핀된 단가.
     *
     * <p>여기에 실제 후보 단가를 <b>적지 않는다.</b> 로드맵 §11.3 이 "정확한 후보 ID 와 당시
     * 단가는 코드 상수나 문서의 영구 결론으로 고정하지 않는다" 고 정했기 때문이다. 실제 금액을
     * 보려면 실행할 때 넘긴다:
     *
     * <pre>{@code
     * ./gradlew test --tests "com.mio.ai.qa.CellCostEstimateTest" \
     *   -PcellPrices="<후보>=<input>/<cachedInput>/<output>,..." -PpricingAsOf=<YYYY-MM-DD>
     * }</pre>
     *
     * <p>핀하지 않으면 후보 금액은 0 이 아니라 미상으로 나오고, 그 사실이 리포트에 찍힌다.
     */
    private static Map<String, String> runtimePricePins() {
        Map<String, String> pins = new LinkedHashMap<>();
        System.getProperties().stringPropertyNames().stream()
                .filter(key -> key.startsWith(CellModelRegistry.PRICE_PROPERTY_PREFIX)
                        || key.equals(CellModelRegistry.PRICING_AS_OF_PROPERTY))
                .forEach(key -> pins.put(key, System.getProperty(key)));
        return pins;
    }

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
    @DisplayName("0단계 명부가 어떤 모델을 왜 뺐는지 값으로 답한다")
    void rosterAnswersWhyEachModelIsOrIsNotACandidate() {
        CellCandidateRoster roster = CellCandidateRoster.load();
        System.out.print(roster.render());

        assertThat(roster.screeningCandidates())
                .as("스크리닝 후보가 없으면 깔때기의 입구가 막힌 것이다")
                .isNotEmpty();
        assertThat(roster.entries())
                .as("사유 없는 판정은 나중에 검토할 수 없다")
                .allSatisfy(entry -> assertThat(entry.reason()).isNotBlank());
        assertThat(roster.excludedByReason())
                .as("제외 사유가 값으로 집계돼야 '왜 안 봤나' 에 기록이 답한다")
                .containsKeys(CellCandidateRoster.Decision.EXCLUDED_LEGACY_DOMINATED,
                        CellCandidateRoster.Decision.EXCLUDED_UNECONOMIC_PER_TURN,
                        CellCandidateRoster.Decision.EXCLUDED_UNRESOLVABLE_PRICE);
        assertThat(roster.candidatesFor(CellModelRole.REFERENCE_JUDGE))
                .as("생성에서 뺀 고가 모델을 reference judge 후보로는 남긴다 — 셀 C 는 턴당 경제성 제약이 없다")
                .isNotEmpty();
        assertThat(roster.screeningCandidates())
                .as("단가를 확정할 수 없는 alias 는 비용 기준 순위에 들어갈 수 없다")
                .noneMatch(id -> id.endsWith("-chat-latest"));
        assertThat(roster.treatAsReasoningModel("gpt-4o")).isFalse();
        assertThat(roster.treatAsReasoningModel("o3")).isTrue();
        assertThat(roster.treatAsReasoningModel("명부에-없는-모델"))
                .as("모르는 모델은 보수적으로 추론 모델로 본다 — 모르는 것을 싼 쪽으로 접지 않는다")
                .isTrue();
    }

    @Test
    @DisplayName("깔때기 단계별·누적 청구서가 나온다 — 승인은 이 숫자를 보고 한다")
    void funnelProjectsPerStageAndCumulativeCost() {
        var plan = CellCostEstimator.defaultPlan(CellCandidateRoster.load());
        var projections = CellCostEstimator.projectPlan(plan, runtimePricePins());

        String report = CellCostEstimator.renderPlan(projections);
        System.out.print(report);

        assertThat(projections).hasSize(BenchmarkStage.values().length);
        assertThat(report).contains("누적");
        assertThat(projections.get(0).plan().stage()).isEqualTo(BenchmarkStage.SCREEN);
        assertThat(projections.get(0).projection().estimates().size())
                .as("1단계는 기준선 A 한 번 + 후보 수만큼의 셀 B 변형이다")
                .isEqualTo(CellCandidateRoster.load().screeningCandidates().size() + 1);
        assertThat(projections.get(2).plan().stage().canProduceVerdict())
                .as("판정은 마지막 단계에서만 나온다")
                .isTrue();
        assertThat(projections.get(0).plan().stage().canProduceVerdict()).isFalse();
    }

    @Test
    @DisplayName("추론 모델의 견적은 상한이 아니라고 리포트가 말한다")
    void reasoningModelsAreNotPresentedAsACeiling() {
        // 실제 후보 ID·단가가 아니라 합성 픽스처다. 명부에 없는 이름이라 하네스가 보수적으로
        // 추론 모델로 취급하는 경로까지 같이 검사한다.
        Map<String, String> pins = Map.of(
                CellModelRegistry.PRICE_PROPERTY_PREFIX + "reasoning-candidate-x", "2.0/0.5/8.0");
        var projection = CellCostEstimator.projectScreening(List.of(BenchmarkCell.B),
                List.of("reasoning-candidate-x"), pins,
                StratifiedSampler.sample(LockedEvalSet.CASES,
                        LockedEvalSet.LockedCase::subgroup, 20, CellModelRegistry.DEFAULT_SEED));
        var estimate = projection.estimates().get(0);

        assertThat(estimate.hasReasoningModel()).isTrue();
        assertThat(estimate.withReasoningUsd())
                .hasValueSatisfying(usd -> assertThat(usd).isGreaterThan(estimate.highUsd()));
        assertThat(CellCostEstimator.render(projection))
                .contains("추론 토큰 보정")
                .contains("상한이 아니다");
    }

    @Test
    @DisplayName("escalation 재시도의 상한이 숫자로 나온다 — '과소' 라는 말만 남기지 않는다")
    void escalationUnderestimateHasAQuantifiedCeiling() {
        var projection = CellCostEstimator.project(List.of(BenchmarkCell.D), LockedEvalSet.CASES);
        var estimate = projection.estimates().get(0);

        assertThat(estimate.escalationCeiling())
                .as("escalation 이 켜진 셀은 최악 상한을 내야 한다")
                .isPresent();
        var ceiling = estimate.escalationCeiling().orElseThrow();
        assertThat(ceiling.extraCalls())
                .as("최악은 '생성한 모든 턴이 한 번씩 재시도' 다 — 재시도는 턴당 1회로 하드 제한돼 있다")
                .isPositive();
        assertThat(CellCostEstimator.render(projection))
                .contains("escalation 재시도 상한")
                .contains("천장이 아니다");
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
