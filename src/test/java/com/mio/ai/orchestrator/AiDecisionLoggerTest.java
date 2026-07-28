package com.mio.ai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.domain.AiPolicyDecision;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiDecisionLoggerTest {

    private final AiPolicyDecisionRepository repository = mock(AiPolicyDecisionRepository.class);
    private final AiDecisionLogger logger = new AiDecisionLogger(repository, new ObjectMapper());

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
                RiskLevel.CLEAR_LOW
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
                decision.crisisTrigger()
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
                CrisisTrigger.SELF_HARM_INQUIRY
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
                decision.crisisTrigger()
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
                RiskLevel.LOW
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
                CrisisTrigger.OUTPUT_GUARD
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
}
