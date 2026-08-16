package com.mio.ai.qa;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.qa.LockedEvalSet.LockedCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1단계 실 실행(run_id {@code 826444f8-e896-4582-8784-5895439eb042}, 19변형·표본 50건, $1.62)이
 * 드러낸 하네스 결함의 회귀 테스트 (이슈 #454 후속, P0-8).
 *
 * <p>여기 있는 테스트는 전부 <b>실행이 실제로 낸 잘못된 결론</b>에서 역산한 것이다. 무엇이
 * 틀렸는지는 아래에 적어 둔다 — 나중에 이 테스트가 왜 있는지 묻는 사람이 아카이브를 뒤지지
 * 않아도 되게.
 *
 * <ol>
 *   <li><b>빈 응답이 수용으로 세졌다.</b> {@code gpt-5-nano} 는 47/47 케이스에서 사용자에게
 *       보일 텍스트를 한 글자도 내지 않았는데 수용률 100%·최저 원가로 표에 올랐다. 빈 문자열은
 *       pre-filter 도 계약 검사도 자명하게 통과하기 때문이다.</li>
 *   <li><b>추론 모델이 프로덕션 토큰 예산 안에서 출력을 못 낸다.</b> 400 토큰을 내부 추론에
 *       전부 쓰고 잘린다. 그 사실이 경고 로그로만 남고 점수에는 반영되지 않아, 대부분 잘린
 *       후보가 순위표에 그대로 올랐다.</li>
 *   <li><b>역할별 호출 수가 포맷 문자열 그대로 찍혔다.</b> 리포트에 {@code InputJudge %d} 가
 *       숫자 대신 인쇄됐고, 이스케이프되지 않은 {@code %n} 이 세 줄을 한 줄로 붙였다.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] 1단계 실행이 드러낸 하네스 결함 회귀")
class CellStage1RegressionTest {

    private static final RunIdentity IDENTITY = RunIdentity.stamp("2026-08-16");

    /** 결함 재현에는 큰 표본이 필요 없다. 구조만 본다. */
    private static final int SAMPLE = 40;

    private static List<LockedCase> sample() {
        return StratifiedSampler.sample(LockedEvalSet.CASES, LockedCase::subgroup, SAMPLE,
                CellModelRegistry.DEFAULT_SEED);
    }

    private static CellRunner.Result run(CellRunner.ClientFactory factory) {
        try {
            return CellRunner.withClientFactory(CellVariant.of(BenchmarkCell.A),
                            CellModelRegistry.resolve(BenchmarkCell.A, Map.of(
                                    CellModelRegistry.PRICE_PROPERTY_PREFIX + "gpt-4o",
                                    "2.5/1.25/10.0",
                                    CellModelRegistry.PRICE_PROPERTY_PREFIX + "gpt-4o-mini",
                                    "0.15/0.075/0.6",
                                    CellModelRegistry.PRICING_AS_OF_PROPERTY, "2026-08-16")),
                            factory)
                    .run(sample(), true, IDENTITY);
        } catch (Exception e) {
            throw new IllegalStateException("회귀 실행이 예외로 멈췄다", e);
        }
    }

    // ── 결함 1: 빈 응답이 수용으로 세진다 ────────────────────────────

    @Test
    @DisplayName("빈 응답은 수용이 아니라 REJECTED_EMPTY_RESPONSE 로 세진다")
    void emptyResponseIsNeverAccepted() {
        CellRunner.Result empty = run((ledger, pricing) ->
                new FixedTextLlmClient(ledger, pricing, ""));

        assertThat(empty.outcomes())
                .filteredOn(CellCaseOutcome::generationCalled)
                .as("생성을 부른 턴이 하나도 없으면 이 테스트가 아무것도 검사하지 않는다")
                .isNotEmpty()
                .as("사용자에게 아무것도 전달하지 않은 턴이 수용으로 세지면 '아무것도 안 하는 모델' 이 1등이 된다")
                .noneMatch(CellCaseOutcome::accepted)
                .allMatch(o -> o.acceptance()
                        == CellCaseOutcome.Acceptance.REJECTED_EMPTY_RESPONSE);
        assertThat(empty.outcomes())
                .filteredOn(CellCaseOutcome::accepted)
                .as("수용으로 남는 것은 모델을 부르지 않은 고정 경로(위기·보안 거절·가드)뿐이다")
                .allMatch(o -> !o.generationCalled());
    }

    @Test
    @DisplayName("공백만 있는 응답도 빈 응답과 같게 센다")
    void whitespaceOnlyResponseIsRejectedToo() {
        CellRunner.Result blank = run((ledger, pricing) ->
                new FixedTextLlmClient(ledger, pricing, "   \n\t  "));

        assertThat(blank.outcomes())
                .filteredOn(CellCaseOutcome::generationCalled)
                .isNotEmpty()
                .allMatch(o -> o.acceptance()
                        == CellCaseOutcome.Acceptance.REJECTED_EMPTY_RESPONSE);
    }

    @Test
    @DisplayName("빈 응답은 수용률·수용 응답당 원가 분모·리포트에 전부 반영된다")
    void emptyResponseFlowsIntoEveryDerivedNumber() {
        CellMetrics.Population empty = CellMetrics.of(run((ledger, pricing) ->
                new FixedTextLlmClient(ledger, pricing, ""))).modelDiscriminating();
        CellMetrics.Population healthy = CellMetrics.of(run((ledger, pricing) ->
                new FixedTextLlmClient(ledger, pricing, "그렇게 느끼셨군요. 조금 더 들려주실 수 있을까요?")))
                .modelDiscriminating();

        assertThat(empty.emptyResponses())
                .as("빈 응답 수가 생성 호출 수와 같아야 한다 — 모든 생성이 빈 본문이었다")
                .isEqualTo(empty.generationCalls());
        assertThat(empty.accepted())
                .as("빈 응답 하나하나가 분자에서 빠진다")
                .isEqualTo(healthy.accepted() - empty.generationCalls());
        assertThat(rate(empty.acceptanceRate()))
                .as("47/47 이 100%% 로 찍히던 것이 이 수정의 대상이다")
                .isLessThan(rate(healthy.acceptanceRate()));
        assertThat(empty.totalCostUsd())
                .as("호출은 실제로 일어났으므로 총 원가는 0 이 아니다 — 청구서는 그대로다")
                .isPresent();
        assertThat(empty.costPerAcceptedResponse().orElseThrow())
                .as("수용 응답당 원가의 분모가 줄었으므로 값은 올라간다 — 내려가면 분모가 틀린 것이다")
                .isGreaterThan(healthy.costPerAcceptedResponse().orElseThrow());
    }

    private static double rate(ReportableRate rate) {
        return rate instanceof ReportableRate.Reported reported ? reported.percent() : -1.0;
    }

    @Test
    @DisplayName("빈 응답으로 수용 0 이 된 후보는 파레토 프론티어에 올라가지 않는다")
    void zeroAcceptedCandidateIsExcludedFromPareto() {
        ReportableRate good = ReportableRate.of("수용률", 190, 200);
        ReportableRate none = ReportableRate.of("수용률", 0, 200);

        CandidateElimination.Frontier frontier = CandidateElimination.pareto(List.of(
                new CandidateElimination.Point("B/정상",
                        java.util.Optional.of(new java.math.BigDecimal("0.001")), good, 4000, 190),
                new CandidateElimination.Point("B/빈응답",
                        java.util.Optional.empty(), none, 1000, 0)));

        assertThat(frontier.onFrontier()).containsExactly("B/정상");
        assertThat(frontier.notComparable())
                .as("전달한 것이 없는 후보를 '단가 미상' 과 같이 취급하면 프론티어에 그대로 남는다")
                .containsOnlyKeys("B/빈응답");
        assertThat(frontier.notComparable().get("B/빈응답")).contains("수용 응답 0건");
    }

    // ── 결함 2: 추론 모델의 절단 ─────────────────────────────────────

    @Test
    @DisplayName("절단된 생성은 케이스·모집단 지표로 세진다 — 경고 로그로만 남지 않는다")
    void truncatedGenerationsAreCounted() {
        CellRunner.Result truncated = run((ledger, pricing) ->
                new FixedTextLlmClient(ledger, pricing, "", true));
        CellMetrics.Population population = CellMetrics.of(truncated).modelDiscriminating();

        assertThat(truncated.outcomes())
                .filteredOn(CellCaseOutcome::generationCalled)
                .isNotEmpty()
                .allMatch(CellCaseOutcome::generationTruncated);
        assertThat(population.truncatedGenerations()).isEqualTo(population.generationCalls());
        assertThat(population.truncationRatePercent()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("대부분 절단된 후보는 순위가 아니라 NOT_EVALUABLE 로 보고된다")
    void mostlyTruncatedCandidateIsNotRankedButReportedNotEvaluable() {
        CellRunner.Result truncated = run((ledger, pricing) ->
                new FixedTextLlmClient(ledger, pricing, "", true));
        CellRunner.Result healthy = run((ledger, pricing) ->
                new FixedTextLlmClient(ledger, pricing, "그렇게 느끼셨군요. 조금 더 들려주실 수 있을까요?"));
        CandidateElimination.Thresholds thresholds =
                CandidateElimination.thresholds(BenchmarkStage.SCREEN);

        CandidateElimination.Verdict verdict = CandidateElimination.evaluate(thresholds,
                new CellVariant(BenchmarkCell.B, "잘리는-후보"),
                CellMetrics.of(healthy).modelDiscriminating(),
                CellMetrics.of(truncated).modelDiscriminating());

        assertThat(verdict.outcome())
                .as("대부분 잘리는 후보를 조용히 채점하면 '내용 없는 응답' 이 점수를 얻는다")
                .isEqualTo(CandidateElimination.Outcome.NOT_EVALUABLE);
        assertThat(verdict.reason()).contains("절단").contains("토큰 예산");
    }

    @Test
    @DisplayName("후보별 completion 토큰 예산을 덮어쓸 수 있고, 기본값은 프로덕션 상수다")
    void completionTokenBudgetIsOverridablePerCandidate() {
        CellModelRegistry production = CellModelRegistry.resolve(BenchmarkCell.A, Map.of());
        CellModelRegistry raised = CellModelRegistry.resolve(BenchmarkCell.A, Map.of(
                CellModelRegistry.MAX_COMPLETION_TOKENS_PROPERTY_PREFIX + "gpt-4o", "4000"));

        assertThat(production.maxCompletionTokensFor("gpt-4o"))
                .as("덮어쓰지 않으면 프로덕션 예산 그대로여야 셀 비용이 프로덕션 비용을 잰다")
                .isEqualTo(CellRunner.PRODUCTION_MAX_COMPLETION_TOKENS);
        assertThat(raised.maxCompletionTokensFor("gpt-4o")).isEqualTo(4000);
        assertThat(raised.maxCompletionTokensFor("gpt-4o-mini"))
                .as("덮어쓴 모델만 바뀐다 — 판정 역할까지 같이 바뀌면 셀 정의가 달라진다")
                .isEqualTo(CellRunner.PRODUCTION_MAX_COMPLETION_TOKENS);
        assertThat(raised.raisedCompletionBudgets())
                .as("프로덕션과 다른 예산으로 잰 수치라는 사실이 기록에 남아야 한다")
                .containsEntry("gpt-4o", 4000);
    }

    @Test
    @DisplayName("예산을 올려 잰 실행은 리포트·manifest 가 그 사실을 밝힌다")
    void raisedBudgetIsDisclosedInReportAndManifest() {
        CellRunner.Result raised;
        try {
            raised = CellRunner.withClientFactory(CellVariant.of(BenchmarkCell.A),
                            CellModelRegistry.resolve(BenchmarkCell.A, Map.of(
                                    CellModelRegistry.MAX_COMPLETION_TOKENS_PROPERTY_PREFIX
                                            + "gpt-4o", "4000")),
                            (ledger, pricing) -> new FixedTextLlmClient(ledger, pricing, "네, 그러셨군요."))
                    .run(sample(), true, IDENTITY);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        CellMetrics metrics = CellMetrics.of(raised);

        assertThat(CellReport.render(raised, metrics))
                .contains("프로덕션 예산")
                .contains("gpt-4o=4000");
        assertThat(CellReport.manifest(raised, metrics).toMetadata())
                .containsKey("max_completion_tokens");
    }

    // ── 결함 3: 리포트 렌더링 ────────────────────────────────────────

    @Test
    @DisplayName("역할별 호출 수가 포맷 문자열이 아니라 숫자로 찍힌다")
    void perRoleCallCountsAreRenderedAsNumbers() {
        CellRunner.Result healthy = run((ledger, pricing) ->
                new FixedTextLlmClient(ledger, pricing, "그렇게 느끼셨군요. 조금 더 들려주실 수 있을까요?"));
        CellMetrics metrics = CellMetrics.of(healthy);
        CellMetrics.Population population = metrics.modelDiscriminating();

        String report = CellReport.render(healthy, metrics);
        String roleLine = report.lines()
                .filter(line -> line.contains("역할별"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("역할별 호출 줄이 없다"));

        assertThat(roleLine)
                .as("포맷 문자열이 그대로 인쇄되면 역할별 호출 수를 읽을 방법이 없다")
                .doesNotContain("%d")
                .contains("InputJudge " + population.inputJudgeCalls())
                .contains("생성 " + population.generationCalls())
                .contains("escalation " + population.escalations())
                .contains("OutputJudge " + population.outputJudgeCalls())
                .contains("CBT 분류 " + population.cbtClassifierCalls());
    }

    @Test
    @DisplayName("리포트 어디에도 이스케이프되지 않은 %n 이 남지 않는다 — 줄이 붙어 읽히지 않는다")
    void reportNeverPrintsLiteralNewlineToken() {
        CellRunner.Result healthy = run((ledger, pricing) ->
                new FixedTextLlmClient(ledger, pricing, "그렇게 느끼셨군요."));

        assertThat(CellReport.render(healthy, CellMetrics.of(healthy)))
                .doesNotContain("%n")
                .doesNotContain("%d");
    }

    // ── 스텁 ────────────────────────────────────────────────────────

    /**
     * 지정한 텍스트만 돌려주는 생성 클라이언트.
     *
     * <p>판정 호출은 {@link StubLlmClient} 와 같은 고정 JSON 을 쓴다 — 이 테스트가 보려는 것은
     * <b>생성 결과의 처리</b>이지 판정 모델의 성능이 아니다.
     */
    private static final class FixedTextLlmClient implements LlmClient {

        private final StubLlmClient delegate;
        private final CellTokenLedger ledger;
        private final CellPricingBook pricing;
        private final String text;
        private final boolean truncated;

        FixedTextLlmClient(CellTokenLedger ledger, CellPricingBook pricing, String text) {
            this(ledger, pricing, text, false);
        }

        FixedTextLlmClient(CellTokenLedger ledger, CellPricingBook pricing, String text,
                           boolean truncated) {
            this.delegate = new StubLlmClient(ledger, pricing);
            this.ledger = ledger;
            this.pricing = pricing;
            this.text = text;
            this.truncated = truncated;
        }

        @Override
        public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
            chunkHandler.accept(text);
            long completion = request.maxCompletionTokens() == null
                    ? CellTokenEstimator.tokens(text)
                    : request.maxCompletionTokens();
            long prompt = CellTokenEstimator.promptTokens(request.messages());
            ledger.writer().write(request.userId(), request.sessionId(), request.component(),
                    request.model(), "stream", prompt, completion, 0L,
                    pricing.costUsd(request.model(), prompt, completion, 0L).orElse(null),
                    java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
            return new LlmStreamResult(0L, LlmUsage.of(request.model(), prompt, completion),
                    truncated);
        }

        @Override
        public String completeText(LlmRequest request) {
            return delegate.completeText(request);
        }

        @Override
        public String completeJson(LlmRequest request) {
            return delegate.completeJson(request);
        }
    }
}
