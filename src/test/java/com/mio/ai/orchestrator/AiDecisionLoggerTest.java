package com.mio.ai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.AiPolicyDecision;
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
                null
        );

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getRiskLevel()).isEqualTo("CLEAR_LOW");
    }
}
