package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.policy.GenerationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[QA] 정정 측정: 비용 상한 · 표본 추출 · 튜닝용 세트 (네트워크 없음)")
class CorrectionBudgetAndSampleTest {

    private static final List<GenerationMode> MODES = List.of(GenerationMode.NORMAL, GenerationMode.SUPPORTIVE);
    private static final List<CorrectionPromptArm> ARMS = List.of(CorrectionPromptArm.values());

    // ── 비용 상한 ─────────────────────────────────────────────────

    @Test
    @DisplayName("원화 상한을 환율로 달러로 환산한다")
    void convertsKrwToUsd() {
        CorrectionBudget budget = CorrectionBudget.ofKrw(1400, 1400);

        assertThat(budget.maxUsd()).isEqualByComparingTo("1");
        assertThat(budget.describe()).contains("1400원", "1400원/$");
        assertThatThrownBy(() -> CorrectionBudget.ofKrw(0, 1400)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorrectionBudget.ofKrw(1000, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("견적 상한이 예산을 넘으면 실행 전에 거부하고, 안 넘으면 통과한다")
    void rejectsRunWhoseEstimateCeilingExceedsBudget() {
        CorrectionEvalSet set = CorrectionCostEstimator.syntheticSet(35, 25);
        CorrectionCostEstimator.Estimate estimate = CorrectionCostEstimator.estimate(
                set, 5, MODES, ARMS, "gpt-4o", "gpt-4o-mini", CorrectionPricing.load());

        // 풀 측정 견적 상한은 약 $6.89 = 9,650원. 1,000원 예산은 거부되어야 한다.
        assertThatThrownBy(() -> CorrectionBudget.ofKrw(1000, 1400).requireEstimateWithin(estimate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("견적 상한").hasMessageContaining("1000원");
        CorrectionBudget.ofKrw(20000, 1400).requireEstimateWithin(estimate);
    }

    @Test
    @DisplayName("파일럿 규모(20건 × 1회 × 2모드 × 2팔)는 1,000원 예산 안이다")
    void pilotFitsWithinPilotBudget() {
        CorrectionEvalSet pilot = CorrectionCostEstimator.syntheticSet(35, 25).sample(20);
        CorrectionCostEstimator.Estimate estimate = CorrectionCostEstimator.estimate(
                pilot, 1, MODES, ARMS, "gpt-4o", "gpt-4o-mini", CorrectionPricing.load());

        CorrectionBudget.ofKrw(1000, 1400).requireEstimateWithin(estimate);
        assertThat(estimate.generationCalls()).isEqualTo(20 * 1 * 2 * 2);
    }

    @Test
    @DisplayName("단가 미등록 모델이 있어 견적을 못 내면 예산을 지킬 수 없으므로 거부한다")
    void rejectsWhenEstimateCannotBeComputed() {
        CorrectionEvalSet set = CorrectionCostEstimator.syntheticSet(2, 2);
        CorrectionCostEstimator.Estimate estimate = CorrectionCostEstimator.estimate(
                set, 1, MODES, ARMS, "gpt-4o", "no-such-model", CorrectionPricing.load());

        assertThatThrownBy(() -> CorrectionBudget.ofKrw(20000, 1400).requireEstimateWithin(estimate))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("단가 미등록");
    }

    @Test
    @DisplayName("실제 비용이 예산을 넘으면 남은 호출을 시작하지 않고, 결과에 중단 표시를 남긴다")
    void stopsRunningWhenActualCostExceedsBudget() {
        CorrectionCostLedger ledger = new CorrectionCostLedger();
        AtomicInteger generations = new AtomicInteger();
        LlmClient client = new LlmClient() {
            @Override
            public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
                generations.incrementAndGet();
                // 생성 한 번마다 $0.60 이 청구됐다고 원장에 기록한다.
                ledger.record(new CorrectionCostLedger.Call("CORRECTION_EVAL_GENERATION", "gpt-4o", 100, 100, 0,
                        new BigDecimal("0.60")));
                chunkHandler.accept("제가 잘못 짚었네요. 쉴 틈이 없으셨군요.");
                return new LlmStreamResult(1, LlmUsage.unresolved(request.model()), false);
            }

            @Override
            public String completeJson(LlmRequest request) {
                return """
                        {"acknowledged": true, "reused_frame": false, "repeated_advice": false,
                         "new_generic_advice": false, "restated": true, "invented_facts": false, "evidence": {}}""";
            }

            @Override
            public String completeText(LlmRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        CorrectionEvalSet set = new CorrectionEvalSet("v", List.of(
                new CorrectionEvalCase("E-1", "fact", "strong", true, List.of(
                        new CorrectionEvalCase.Turn("USER", "a"), new CorrectionEvalCase.Turn("ASSISTANT", "b"),
                        new CorrectionEvalCase.Turn("USER", "c")))));
        CorrectionBudget budget = CorrectionBudget.ofKrw(1400, 1400); // $1.00 → 두 번째 호출 뒤에 초과

        CorrectionEvalRunner.Result result = new CorrectionEvalRunner(client,
                new CorrectionJudge(client, "m"), null).run(set, List.of(GenerationMode.NORMAL),
                List.of(CorrectionPromptArm.BASELINE), 20, 1,
                CorrectionRunIdentity.stamp("v", "sha", 20, List.of("NORMAL"), List.of("BASELINE"), "gpt-4o", "m"),
                () -> budget.exceeded(ledger));

        assertThat(result.budgetStopped()).isTrue();
        assertThat(generations.get()).as("상한을 넘은 뒤에는 생성 호출을 시작하지 않는다").isEqualTo(2);
        assertThat(result.samples()).hasSize(20);
        assertThat(result.samples().stream().filter(s -> s.sample().failed()).count()).isEqualTo(18);
        assertThat(CorrectionReport.render(result, ledger, CorrectionReport.Detail.SUMMARY))
                .contains("비용 상한에 닿아 실행을 중단했다");
    }

    // ── 층화 표본 ─────────────────────────────────────────────────

    @Test
    @DisplayName("표본은 정정 종류와 대조군(조언 요청 포함)을 고르게 뽑고, 같은 입력이면 항상 같다")
    void stratifiedSampleIsBalancedAndDeterministic() throws IOException {
        CorrectionEvalSet dev = loadDevSet();

        CorrectionEvalSet sample = dev.sample(20);
        CorrectionEvalSet again = dev.sample(20);

        assertThat(sample.cases()).hasSize(20);
        assertThat(sample.cases()).extracting(CorrectionEvalCase::id)
                .isEqualTo(again.cases().stream().map(CorrectionEvalCase::id).toList());
        assertThat(sample.cases().stream().map(CorrectionEvalCase::type).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("fact", "emotion", "advice_refusal", "soft_disagreement",
                        "restatement", "control");
        assertThat(sample.cases()).as("조언 요청 대조군이 표본에 들어가야 부작용을 볼 수 있다")
                .anyMatch(CorrectionEvalCase::adviceRequested);
        assertThat(sample.version()).isEqualTo("correction-dev-v1-sample20");
        assertThat(dev.sample(48)).isSameAs(dev);
        assertThatThrownBy(() -> dev.sample(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── 튜닝용 세트 ───────────────────────────────────────────────

    @Test
    @DisplayName("튜닝용 세트는 로더 검증을 통과하고 정정 30건 + 대조군 18건의 계획된 분포를 갖는다")
    void devSetLoadsWithPlannedDistribution() throws IOException {
        CorrectionEvalSet dev = loadDevSet();
        CorrectionEvalSetSummary summary = CorrectionEvalSetSummary.of(dev);

        assertThat(dev.version()).isEqualTo("correction-dev-v1");
        assertThat(summary.total()).isEqualTo(48);
        assertThat(summary.corrections()).isEqualTo(30);
        assertThat(summary.controls()).isEqualTo(18);
        assertThat(summary.adviceRequested()).isEqualTo(4);
        assertThat(summary.byType()).containsEntry("fact", 6).containsEntry("emotion", 6)
                .containsEntry("advice_refusal", 6).containsEntry("soft_disagreement", 6)
                .containsEntry("restatement", 6).containsEntry("control", 18);
        assertThat(summary.byStrength()).containsKeys("strong", "medium", "weak");
        assertThat(summary.byTurnCount()).containsKeys(1, 3, 5);
    }

    @Test
    @DisplayName("튜닝용 세트의 모든 정정 케이스에는 바로잡을 AI 발화가 있고, 마지막 발화는 사용자다")
    void devSetCorrectionCasesCarryAssistantTurn() throws IOException {
        for (CorrectionEvalCase c : loadDevSet().cases()) {
            assertThat(c.turns().get(c.turns().size() - 1).isUser()).as(c.id()).isTrue();
            if (c.correction()) {
                assertThat(c.priorTurns()).as(c.id()).anyMatch(CorrectionEvalCase.Turn::isAssistant);
            }
        }
    }

    private static CorrectionEvalSet loadDevSet() throws IOException {
        try (InputStream in = CorrectionBudgetAndSampleTest.class.getClassLoader()
                .getResourceAsStream("eval/correction/correction-dev-v1.json")) {
            assertThat(in).as("튜닝용 세트가 클래스패스에 없다").isNotNull();
            return CorrectionEvalSet.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
