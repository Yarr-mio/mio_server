package com.mio.ai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmCostCalculator;
import com.mio.ai.llm.LlmPricingProperties;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.domain.AiPolicyDecision;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.memory.retrieval.MemoryContextResult;
import com.mio.ai.memory.retrieval.RetrievalSource;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.plan.ResponseContractResult;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.repository.AiPolicyDecisionRepository;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiDecisionLoggerTest {

    private final AiPolicyDecisionRepository repository = mock(AiPolicyDecisionRepository.class);
    private final AiDecisionLogger logger = new AiDecisionLogger(repository, new ObjectMapper(),
            new LlmCostCalculator(new LlmPricingProperties()));

    @Test
    @DisplayName("정책 결정의 risk_level을 집계 컬럼에도 저장한다")
    void logPersistsRiskLevelColumn() {
        PolicyDecision decision = new PolicyDecision(
                "pd_test",
                DecisionAction.GENERATE,
                GenerationMode.NORMAL,
                DeliveryMode.SPECULATIVE,
                SecurityLevel.CLEAN,
                true,
                true,
                false,
                InterventionHints.empty(),
                "test-policy",
                RiskLevel.CLEAR_LOW,
                null,
                JudgeStatus.SKIPPED
        );

        logger.log(
                UUID.randomUUID(),
                UUID.randomUUID(),
                decision,
                new ModerationResult(false, Map.of(), Map.of()),
                SafetyL1Result.clear(),
                SecurityAssessment.clean(),
                100,
                10,
                false,
                false,
                null,
                null,
                "default",
                false,
                false,
                false,
                decision.crisisTrigger(),
                null
        );

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getRiskLevel()).isEqualTo("CLEAR_LOW");
        assertThat(captor.getValue().getTrace())
                .as("위기가 아닌 턴에는 진입 경로가 없다")
                .contains("\"crisis_trigger\":null");
    }

    /**
     * 이슈 #260 — 위기 진입 경로를 트레이스에 남긴다.
     *
     * <p>{@code crisis_events.trigger_type} 은 CHECK 제약이 4값이라 {@code INPUT_JUDGE} 와
     * {@code OUTPUT_GUARD} 가 똑같이 {@code pattern} 으로 저장된다. 어느 계층이 위기를 잡았는지
     * 구분할 수 있는 곳은 이 트레이스뿐이므로 직접 고정한다.
     */
    @Test
    @DisplayName("위기 진입 경로를 트레이스에 남긴다")
    void logPersistsCrisisTriggerInTrace() {
        PolicyDecision decision = new PolicyDecision(
                "pd_crisis",
                DecisionAction.CRISIS_FLOW,
                GenerationMode.CRISIS,
                DeliveryMode.CRISIS_FLOW,
                SecurityLevel.ATTACK,
                false,
                false,
                false,
                InterventionHints.empty(),
                "test-policy",
                RiskLevel.HARD_CRISIS,
                CrisisTrigger.SELF_HARM_INQUIRY,
                JudgeStatus.SKIPPED
        );

        logger.log(
                UUID.randomUUID(),
                UUID.randomUUID(),
                decision,
                new ModerationResult(false, Map.of(), Map.of()),
                SafetyL1Result.clear(),
                SecurityAssessment.selfHarmInquiry(List.of("자살 방법 알려줘")),
                100,
                10,
                true,
                false,
                null,
                null,
                "default",
                false,
                false,
                false,
                decision.crisisTrigger(),
                null
        );

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getTrace())
                .contains("\"crisis_trigger\":\"SELF_HARM_INQUIRY\"")
                .contains("\"crisis_flow_triggered\":true");
    }

    /**
     * 출력 가드가 승격시킨 위기는 {@code PolicyDecision} 에 경로가 없다.
     *
     * <p>PolicyEngine 은 {@code GENERATE} 를 결정했고, 위기는 응답을 생성한 뒤 출력 검사 단계에서
     * 발견된다. 그래서 트레이스가 {@code decision.crisisTrigger()} 를 읽으면 위기로 갔는데도
     * {@code crisis_trigger} 가 {@code null} 로 남아 "어느 계층이 잡았는지"를 구분할 수 없다.
     * 결정이 아니라 실제로 적용된 경로를 기록해야 한다.
     */
    @Test
    @DisplayName("출력 가드가 승격시킨 위기도 진입 경로가 트레이스에 남는다")
    void logPersistsCrisisTriggerEscalatedByOutputGuard() {
        // PolicyEngine 이 내린 결정은 GENERATE — crisisTrigger 는 null 이다.
        PolicyDecision generateDecision = new PolicyDecision(
                "pd_output_guard",
                DecisionAction.GENERATE,
                GenerationMode.NORMAL,
                DeliveryMode.SPECULATIVE,
                SecurityLevel.CLEAN,
                true,
                true,
                true,
                InterventionHints.empty(),
                "test-policy",
                RiskLevel.LOW,
                null,
                JudgeStatus.SKIPPED
        );
        assertThat(generateDecision.crisisTrigger()).isNull();

        logger.log(
                UUID.randomUUID(),
                UUID.randomUUID(),
                generateDecision,
                new ModerationResult(false, Map.of(), Map.of()),
                SafetyL1Result.clear(),
                SecurityAssessment.clean(),
                100,
                10,
                true,
                false,
                OutputPreFilterResult.pass(),
                null,
                "default",
                false,
                false,
                false,
                CrisisTrigger.OUTPUT_GUARD,
                null
        );

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getTrace())
                .as("결정이 아니라 실제로 적용된 경로를 기록해야 한다")
                .contains("\"crisis_trigger\":\"OUTPUT_GUARD\"")
                .contains("\"crisis_flow_triggered\":true");
        assertThat(captor.getValue().getAction())
                .as("PolicyEngine 이 내린 결정 자체는 그대로 남는다")
                .isEqualTo("GENERATE");
    }

    /**
     * 이슈 #263 / #261 — 안전 신호를 확인하지 못한 턴을 사후에 식별할 수 있어야 한다.
     *
     * <p>{@code l0_flagged=false} 만으로는 "안전 판정"과 "판정을 못 받아옴"이 구분되지 않고,
     * 프로파일도 마찬가지로 근거 없이 만들어졌는지가 드러나지 않는다. 두 상태 모두
     * 안전 계층이 실질적으로 빠진 채 처리된 턴이므로 트레이스에 남는다.
     */
    @Test
    @DisplayName("L0 미판정과 프로파일 degraded 상태를 트레이스에 남긴다")
    void logPersistsUnresolvedSafetySignals() {
        PolicyDecision decision = generateDecision("pd_degraded");

        logger.log(
                UUID.randomUUID(),
                UUID.randomUUID(),
                decision,
                ModerationResult.failOpen(),
                SafetyL1Result.clear(),
                SecurityAssessment.clean(),
                100,
                10,
                false,
                false,
                null,
                null,
                "default",
                false,
                false,
                true,
                null,
                null
        );

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getTrace())
                .contains("\"l0_flagged\":false")
                .contains("\"l0_resolved\":false")
                .contains("\"safety_profile_degraded\":true");
    }

    @Test
    @DisplayName("정상 판정 턴은 l0_resolved=true로 남는다")
    void logMarksResolvedModeration() {
        PolicyDecision decision = generateDecision("pd_ok");

        logger.log(
                UUID.randomUUID(),
                UUID.randomUUID(),
                decision,
                ModerationResult.clear(),
                SafetyL1Result.clear(),
                SecurityAssessment.clean(),
                100,
                10,
                false,
                false,
                null,
                null,
                "default",
                false,
                false,
                false,
                null,
                null
        );

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getTrace())
                .contains("\"l0_resolved\":true")
                .contains("\"safety_profile_degraded\":false");
    }

    @Test
    @DisplayName("Input Judge 실패 상태를 정책 결정 트레이스에 남긴다")
    void logPersistsFailedInputJudgeStatus() {
        PolicyDecision decision = failedJudgeDecision("pd_judge_failed");

        logger.log(
                UUID.randomUUID(),
                UUID.randomUUID(),
                decision,
                ModerationResult.clear(),
                SafetyL1Result.clear(),
                SecurityAssessment.clean(),
                100,
                10,
                false,
                true,
                null,
                null,
                "default",
                false,
                false,
                false,
                null,
                null
        );

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getJudgeStatus())
                .as("운영상의 MEDIUM 폴백과 실제 MEDIUM 판정을 집계 컬럼에서 구분해야 한다")
                .isEqualTo("FAILED");
        assertThat(captor.getValue().getTrace())
                .contains("\"input_judge_status\":\"FAILED\"");
    }

    @Test
    @DisplayName("LLM을 호출하지 않은 턴은 모델·비용을 null로 남긴다 — 하드코딩된 gpt-4o/0.0이 아니다")
    void logLeavesLlmFieldsNullWhenNoLlmCall() {
        logAndCapture(new LlmCostCalculator(pricedProperties()), null);

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getTrace())
                .as("호출하지 않은 모델을 사용한 것처럼 남기면 비용·모델 집계가 통째로 틀어진다")
                .contains("\"llm_model\":null")
                .contains("\"llm_usage_resolved\":null")
                .contains("\"llm_prompt_tokens\":null")
                .contains("\"llm_cost_usd\":null");
    }

    @Test
    @DisplayName("사용량을 받은 턴은 실제 토큰·비용을 남긴다")
    void logWritesResolvedUsageAndCost() {
        logAndCapture(new LlmCostCalculator(pricedProperties()),
                LlmUsage.of("gpt-4o-mini", 1000, 500));

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        // 0.15*1000/1e6 + 0.60*500/1e6 = 0.00015 + 0.0003 = 0.000450
        assertThat(captor.getValue().getTrace())
                .contains("\"llm_model\":\"gpt-4o-mini\"")
                .contains("\"llm_usage_resolved\":true")
                .contains("\"llm_prompt_tokens\":1000")
                .contains("\"llm_completion_tokens\":500")
                .contains("\"llm_cost_usd\":0.00045");
    }

    @Test
    @DisplayName("사용량을 못 받으면 토큰·비용은 0이 아니라 null, 모델은 남는다")
    void logDistinguishesUnresolvedUsageFromZero() {
        logAndCapture(new LlmCostCalculator(pricedProperties()),
                LlmUsage.unresolved("gpt-4o-mini"));

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getTrace())
                .as("0으로 남기면 '안 썼다'와 '모른다'가 같은 값이 된다")
                .contains("\"llm_model\":\"gpt-4o-mini\"")
                .contains("\"llm_usage_resolved\":false")
                .contains("\"llm_prompt_tokens\":null")
                .contains("\"llm_cost_usd\":null");
    }

    @Test
    @DisplayName("단가 미등록 모델은 비용을 0이 아니라 null로 남긴다")
    void logLeavesCostNullForUnpricedModel() {
        logAndCapture(new LlmCostCalculator(new LlmPricingProperties()),
                LlmUsage.of("gpt-4o-mini", 1000, 500));

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getTrace())
                .contains("\"llm_prompt_tokens\":1000")
                .contains("\"llm_cost_usd\":null");
    }

    // ── 실패 상태 전파 (이슈 #364, 로드맵 §12 P0-2 · §10.1) ──────────────────

    @Test
    @DisplayName("검색 부분 실패를 상태와 실패 소스로 남긴다")
    void logPersistsPartialRetrievalFailure() {
        logWithFailureState(
                null,
                MemoryContextResult.partial("남은 기억", Set.of(RetrievalSource.VECTOR_EPISODE)));

        assertThat(capturedTrace())
                .contains("\"retrieval_status\":\"PARTIAL\"")
                .contains("\"retrieval_failed_sources\":\"VECTOR_EPISODE\"");
    }

    @Test
    @DisplayName("검색 성공은 결과가 비어도 OK 로 남는다")
    void logDistinguishesEmptyResultFromFailure() {
        logWithFailureState(null, MemoryContextResult.ok(""));

        // "기억 없음" 을 실패로 세면 실패율 지표가 의미를 잃는다.
        assertThat(capturedTrace())
                .contains("\"retrieval_status\":\"OK\"")
                .contains("\"retrieval_failed_sources\":null");
    }

    @Test
    @DisplayName("컨텍스트 조립 실패를 FAILED 로 남긴다")
    void logPersistsRetrievalFailure() {
        logWithFailureState(null, MemoryContextResult.failed());

        assertThat(capturedTrace()).contains("\"retrieval_status\":\"FAILED\"");
    }

    @Test
    @DisplayName("Judge 실패로 인한 REPLACE 를 판정 REPLACE 와 구분한다")
    void logDistinguishesJudgeFailureFromReplaceVerdict() {
        logWithFailureState(OutputJudgeResult.fallback(), MemoryContextResult.ok("기억"));

        // action 은 REPLACE 로 같지만 status 가 다르다. 이 구분이 없으면 판정 실패가
        // 안전 판정으로 집계된다 (#289 가 Input Judge 에서 고친 결함과 같은 형태).
        assertThat(capturedTrace())
                .contains("\"output_judge_action\":\"REPLACE\"")
                .contains("\"output_judge_status\":\"FAILED\"");
    }

    @Test
    @DisplayName("모델이 실제로 내린 REPLACE 판정은 SUCCEEDED 로 남는다")
    void logMarksRealReplaceVerdictSucceeded() {
        logWithFailureState(OutputJudgeResult.replace(), MemoryContextResult.ok("기억"));

        assertThat(capturedTrace())
                .contains("\"output_judge_action\":\"REPLACE\"")
                .contains("\"output_judge_status\":\"SUCCEEDED\"");
    }

    @Test
    @DisplayName("Judge 를 부르지 않은 턴은 SKIPPED 다")
    void logMarksAbsentJudgeSkipped() {
        logWithFailureState(null, MemoryContextResult.ok("기억"));

        assertThat(capturedTrace()).contains("\"output_judge_status\":\"SKIPPED\"");
    }

    private void logWithFailureState(OutputJudgeResult judgeResult, MemoryContextResult memoryResult) {
        logger.log(
                UUID.randomUUID(),
                UUID.randomUUID(),
                generateDecision("pd_failure_state"),
                ModerationResult.clear(),
                SafetyL1Result.clear(),
                SecurityAssessment.clean(),
                100,
                10,
                false,
                false,
                OutputPreFilterResult.pass(),
                judgeResult,
                "default",
                false,
                false,
                false,
                null,
                null,
                ResponseContractResult.notApplicable(),
                -1,
                -1,
                false,
                0,
                memoryResult
        );
    }

    private String capturedTrace() {
        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());
        return captor.getValue().getTrace();
    }

    private void logAndCapture(LlmCostCalculator calculator, LlmUsage usage) {
        new AiDecisionLogger(repository, new ObjectMapper(), calculator).log(
                UUID.randomUUID(),
                UUID.randomUUID(),
                generateDecision("pd_llm"),
                ModerationResult.clear(),
                SafetyL1Result.clear(),
                SecurityAssessment.clean(),
                100,
                10,
                false,
                false,
                null,
                null,
                "default",
                false,
                false,
                false,
                null,
                usage
        );
    }

    private LlmPricingProperties pricedProperties() {
        LlmPricingProperties properties = new LlmPricingProperties();
        properties.setModels(Map.of("gpt-4o-mini", new LlmPricingProperties.ModelPrice(
                new java.math.BigDecimal("0.15"), new java.math.BigDecimal("0.075"), new java.math.BigDecimal("0.60"))));
        return properties;
    }

    private PolicyDecision generateDecision(String decisionId) {
        return new PolicyDecision(
                decisionId,
                DecisionAction.GENERATE,
                GenerationMode.NORMAL,
                DeliveryMode.SPECULATIVE,
                SecurityLevel.CLEAN,
                true,
                true,
                false,
                InterventionHints.empty(),
                "test-policy",
                RiskLevel.CLEAR_LOW,
                null,
                JudgeStatus.SKIPPED
        );
    }

    private PolicyDecision failedJudgeDecision(String decisionId) {
        return new PolicyDecision(
                decisionId,
                DecisionAction.GENERATE,
                GenerationMode.GUARDED,
                DeliveryMode.BUFFER,
                SecurityLevel.CLEAN,
                true,
                true,
                true,
                InterventionHints.empty(),
                "test-policy",
                RiskLevel.MEDIUM,
                null,
                JudgeStatus.FAILED
        );
    }
}
