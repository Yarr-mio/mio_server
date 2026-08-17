package com.mio.ai.qa;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.plan.GenerationFreedom;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.plan.ResponseContractValidator;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.prompt.PromptBuilder;
import com.mio.ai.qa.CellCaseOutcome.ContractOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 계약 준수 하네스 자체 검사 (이슈 #305). 모델을 부르지 않는다.
 *
 * <p>유료 실행 전에 답해야 하는 것은 셋이다. (1) A/B 가 프롬프트만 가르는가, (2) 위반이
 * 실제로 잡히고 유형별로 세어지는가, (3) 하한 미달 행위의 비율이 계산되지 않는가.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] 계약 준수 하네스 자체 검사 (모델 호출 없음)")
class ContractComplianceHarnessTest {

    private static final RunIdentity IDENTITY = RunIdentity.stamp("2026-08-17");

    /** 계약이 걸리는 계획 하나. 프롬프트 조립만 볼 때 쓴다. */
    private static final ResponsePlan PLAN = new ResponsePlan(
            ResponseAct.EMPATHIC_REFLECTION, GenerationFreedom.CONSTRAINED, 1, 3,
            List.of("diagnosis", "certainty_about_user", "guaranteed_outcome",
                    "cbt_intervention", "advice"));

    // ── A/B 가 정확히 무엇을 가르는가 ────────────────────────────────

    @Test
    @DisplayName("대조군 프롬프트에서 [응답 계약] 블록만 빠진다")
    void controlArmDropsOnlyTheContractBlock() {
        PromptBuilder builder = new PromptBuilder();
        String with = builder.buildSystemPrompt(GenerationMode.GUARDED, InterventionHints.empty(),
                null, com.mio.character.domain.CharacterPersona.DEFAULT.characterId(), null,
                ContractPromptArm.WITH_CONTRACT_BLOCK.promptPlan(PLAN));
        String without = builder.buildSystemPrompt(GenerationMode.GUARDED, InterventionHints.empty(),
                null, com.mio.character.domain.CharacterPersona.DEFAULT.characterId(), null,
                ContractPromptArm.WITHOUT_CONTRACT_BLOCK.promptPlan(PLAN));

        assertThat(with).contains("[응답 계약]");
        assertThat(without).doesNotContain("[응답 계약]");
        assertThat(without)
                .as("계약 블록 말고 다른 것이 함께 빠지면 A/B 의 차이가 계약의 효과가 아니게 된다")
                .isEqualTo(with.replace(with.substring(with.indexOf("\n\n[응답 계약]"),
                        with.length()), ""));
    }

    @Test
    @DisplayName("대조군도 진짜 계획으로 채점된다 — 채점 기준은 팔에 따라 바뀌지 않는다")
    void bothArmsAreScoredWithTheRealPlan() {
        assertThat(ContractPromptArm.WITH_CONTRACT_BLOCK.promptPlan(PLAN)).isSameAs(PLAN);
        assertThat(ContractPromptArm.WITHOUT_CONTRACT_BLOCK.promptPlan(PLAN)).isNull();

        // 검사에 쓰이는 계획은 프롬프트 팔과 무관하게 하나뿐이다.
        ResponseContractValidator validator = new ResponseContractValidator();
        String violating = "지금 많이 힘드시겠어요. 그건 분명히 지나갈 거예요. "
                + "잠깐 산책이라도 해보세요. 언제부터 그랬나요? 오늘은 어떠셨어요?";
        assertThat(validator.validate(PLAN, violating).passed()).isFalse();
    }

    @Test
    @DisplayName("계약 팔이 기본값이면 변형 이름·파일명이 예전과 같다")
    void defaultArmKeepsExistingLabels() {
        assertThat(CellVariant.of(BenchmarkCell.A).label()).isEqualTo("A");
        assertThat(new CellVariant(BenchmarkCell.B, "gpt-4.1-mini").fileLabel())
                .isEqualTo("b-gpt-4.1-mini");
        assertThat(CellVariant.of(BenchmarkCell.A, ContractPromptArm.WITHOUT_CONTRACT_BLOCK).label())
                .isEqualTo("A/without-contract");
        assertThat(CellVariant.of(BenchmarkCell.A, ContractPromptArm.WITHOUT_CONTRACT_BLOCK)
                .fileLabel())
                .as("두 팔이 같은 파일명을 쓰면 아카이브가 서로를 덮는다")
                .isEqualTo("a-without-contract");
    }

    @Test
    @DisplayName("실행이 실제로 팔마다 다른 프롬프트를 보낸다")
    void runnerSendsDifferentPromptsPerArm() {
        PromptSpy withSpy = new PromptSpy();
        PromptSpy withoutSpy = new PromptSpy();
        run(ContractPromptArm.WITH_CONTRACT_BLOCK, withSpy);
        run(ContractPromptArm.WITHOUT_CONTRACT_BLOCK, withoutSpy);

        assertThat(withSpy.generationPrompts())
                .as("계약 팔인데 계약 블록이 없는 프롬프트가 있다")
                .isNotEmpty()
                .allSatisfy(prompt -> assertThat(prompt).contains("[응답 계약]"));
        assertThat(withoutSpy.generationPrompts())
                .isNotEmpty()
                .allSatisfy(prompt -> assertThat(prompt).doesNotContain("[응답 계약]"));
    }

    // ── 지표 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("스텁 실행에서 계약 모집단과 행위 분포가 나온다")
    void stubRunProducesAContractPopulation() {
        ContractComplianceMetrics metrics = metricsOf(run(ContractPromptArm.WITH_CONTRACT_BLOCK,
                new PromptSpy()));

        System.out.print(ContractComplianceReport.render(
                run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy()), metrics));

        assertThat(metrics.applicable())
                .as("계약 적용 턴이 없으면 이 평가는 아무것도 재지 않는다")
                .isEqualTo(ContractEvalSet.CASES.size());
        assertThat(metrics.byAct().get(ResponseAct.CLARIFY_CONTEXT).applicable())
                .as("스텁 InputJudge 는 CLEAR_LOW 를 돌려주므로 룰 승격이 남아 CLARIFY_CONTEXT 가 된다")
                .isEqualTo(ContractEvalSet.CASES.size());
        assertThat(metrics.crisisRouted()).isZero();
        assertThat(metrics.unplanned()).isZero();
    }

    @Test
    @DisplayName("위반이 유형별로 세어진다 — 상한 위반의 실제 수치는 유형으로 접힌다")
    void violationsAreCountedByType() {
        // 계약을 대놓고 깨는 본문: 문장 5개·질문 2개·조언·단정.
        String violating = "많이 힘드셨겠어요. 그건 분명히 지나갈 거예요. "
                + "잠깐이라도 산책을 해보세요. 언제부터 그랬나요? 오늘은 어떠셨어요?";
        ContractComplianceMetrics metrics = metricsOf(
                run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy(), violating));

        assertThat(metrics.violated()).isEqualTo(metrics.applicable());
        assertThat(metrics.violationTypes().keySet())
                .as("유형 분포가 비면 '무엇이 가장 자주 깨지는가' 에 답할 수 없다")
                .contains("max_sentences", "max_questions");
        assertThat(metrics.violationTypes().keySet())
                .as("max_questions(3>1) 처럼 수치가 붙은 채로 세면 유형 분포가 아니라 값 분포가 된다")
                .allSatisfy(type -> assertThat(type).doesNotContain("("));
        assertThat(metrics.shape().maxSentences()).isGreaterThan(3);
    }

    @Test
    @DisplayName("하한 미달 행위는 비율이 계산되지 않는다")
    void ratesBelowTheFloorAreSuppressed() {
        ContractComplianceMetrics metrics = metricsOf(run(ContractPromptArm.WITH_CONTRACT_BLOCK,
                new PromptSpy()));

        assertThat(metrics.byAct().get(ResponseAct.EMPATHIC_REFLECTION).violationRate())
                .as("모집단이 0 인 행위에 비율이 붙으면 안 된다")
                .isInstanceOf(ReportableRate.Suppressed.class);
        assertThat(metrics.byAct().get(ResponseAct.CLARIFY_CONTEXT).violationRate())
                .as("하한을 넘긴 행위는 비율을 낸다 — 하한이 모든 것을 막는 규칙이면 쓸모가 없다")
                .isInstanceOf(ReportableRate.Reported.class);
        assertThat(ContractComplianceReport.render(
                run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy()), metrics))
                .contains("미보고 (n=0");
    }

    @Test
    @DisplayName("A/B 리포트는 한쪽이라도 하한 미달이면 비율 차이를 내지 않는다")
    void comparisonRefusesDeltaWhenEitherArmIsSuppressed() {
        ContractComplianceMetrics with = metricsOf(run(ContractPromptArm.WITH_CONTRACT_BLOCK,
                new PromptSpy()));
        ContractComplianceMetrics without = metricsOf(run(ContractPromptArm.WITHOUT_CONTRACT_BLOCK,
                new PromptSpy()));

        String comparison = ContractComplianceReport.renderComparison(with, without);
        System.out.print(comparison);

        assertThat(comparison).contains("비율 비교 불가 (한쪽 이상 하한 미달)");
        assertThat(comparison)
                .as("이 A/B 가 답하지 못하는 것을 리포트가 스스로 적어야 한다")
                .contains("답 못 한다");
    }

    @Test
    @DisplayName("스텁 실행은 아카이브를 남기지 않는다")
    void stubRunsAreNotArchived() {
        CellRunner.Result result = run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy());
        ContractComplianceMetrics metrics = metricsOf(result);

        assertThat(catchThrowable(() ->
                ContractComplianceReport.archive(result, metrics, "본문")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("스텁 실행");
    }

    @Test
    @DisplayName("manifest 가 dev_gold 와 튜닝 노출을 그대로 싣는다")
    void manifestCarriesTheHonestSplit() {
        CellRunner.Result result = run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy());
        EvalRunManifest manifest = ContractComplianceReport.manifest(
                result, metricsOf(result), Map.of("stub", "true"));

        Map<String, String> metadata = manifest.toMetadata();
        assertThat(metadata.get("dataset_split")).isEqualTo("dev_gold");
        assertThat(metadata.get("tuning_exposure"))
                .isEqualTo(EvalRunManifest.TuningExposure.USED_FOR_TUNING.attestation());
        assertThat(metadata.get("dataset")).isEqualTo(ContractEvalSet.VERSION);
        assertThat(metadata.get("scope")).contains("contract compliance (dev-gold)");
    }

    // ── 실행 도우미 ────────────────────────────────────────────────

    private static ContractComplianceMetrics metricsOf(CellRunner.Result result) {
        return ContractComplianceMetrics.of(result);
    }

    private static CellRunner.Result run(ContractPromptArm arm, PromptSpy spy) {
        return run(arm, spy, null);
    }

    private static CellRunner.Result run(ContractPromptArm arm, PromptSpy spy, String generationText) {
        CellVariant variant = CellVariant.of(BenchmarkCell.A, arm);
        try {
            return CellRunner.withClientFactory(variant,
                    CellModelRegistry.resolveForEstimate(BenchmarkCell.A, Map.of()),
                    (ledger, pricing) -> spy.wrap(new StubLlmClient(ledger, pricing),
                            ledger, pricing, generationText))
                    .run(ContractEvalSet.CASES, false, IDENTITY);
        } catch (Exception e) {
            throw new IllegalStateException("스텁 실행 실패: " + variant.label(), e);
        }
    }

    /** 생성 프롬프트를 그대로 붙잡는 클라이언트. A/B 가 무엇을 갈랐는지는 프롬프트로만 확인된다. */
    private static final class PromptSpy {

        private final ConcurrentLinkedQueue<String> generationPrompts = new ConcurrentLinkedQueue<>();

        List<String> generationPrompts() {
            return List.copyOf(generationPrompts);
        }

        LlmClient wrap(LlmClient delegate, CellTokenLedger ledger, CellPricingBook pricing,
                       String generationText) {
            return new LlmClient() {
                @Override
                public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
                    generationPrompts.add(systemPromptOf(request));
                    if (generationText == null) {
                        return delegate.stream(request, chunkHandler);
                    }
                    chunkHandler.accept(generationText);
                    long completion = CellTokenEstimator.tokens(generationText);
                    long prompt = CellTokenEstimator.promptTokens(request.messages());
                    ledger.writer().write(request.userId(), request.sessionId(), request.component(),
                            request.model(), "stream", prompt, completion, 0L,
                            pricing.costUsd(request.model(), prompt, completion, 0L).orElse(null),
                            OffsetDateTime.now(ZoneOffset.UTC));
                    return new LlmStreamResult(0L,
                            LlmUsage.of(request.model(), prompt, completion), false);
                }

                @Override
                public String completeText(LlmRequest request) {
                    return delegate.completeText(request);
                }

                @Override
                public String completeJson(LlmRequest request) {
                    return delegate.completeJson(request);
                }
            };
        }

        private static String systemPromptOf(LlmRequest request) {
            return request.messages().stream()
                    .filter(m -> "system".equalsIgnoreCase(m.role()))
                    .map(LlmRequest.Message::content)
                    .findFirst()
                    .orElse("");
        }
    }

    /** 검사 대상 어휘가 프로덕션에서 사라지면 이 하네스는 조용히 아무것도 재지 않게 된다. */
    @Test
    @DisplayName("계약 행위 어휘가 프로덕션 enum 과 같다")
    void contractActsMatchProduction() {
        assertThat(ContractEvalSet.CONTRACT_ACTS)
                .allSatisfy(act -> assertThat(ResponseAct.valueOf(act.name())).isEqualTo(act));
        assertThat(ContractOutcome.values())
                .containsExactly(ContractOutcome.NOT_APPLICABLE, ContractOutcome.UNCHECKED,
                        ContractOutcome.PASSED, ContractOutcome.VIOLATED);
    }
}
