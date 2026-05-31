package com.mio.ai.policy;

import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    private final PolicyEngine policyEngine = new PolicyEngine();

    private CombinedSignal combined(SecurityLevel security, boolean hardCrisis, boolean riskCandidate, boolean l0Flagged) {
        SafetyL1Result l1 = new SafetyL1Result(
                hardCrisis, riskCandidate, false, false, false, l0Flagged,
                List.of(), hardCrisis ? 0.9 : 0.0
        );
        return new CombinedSignal(
                security, hardCrisis, riskCandidate,
                false, false, false, l0Flagged, false, l1, l1.combinedConfidence()
        );
    }

    @Test
    @DisplayName("ATTACK → SECURITY_REFUSAL")
    void attack_returns_security_refusal() {
        var decision = policyEngine.decide(combined(SecurityLevel.ATTACK, false, false, false));
        assertThat(decision.action()).isEqualTo(DecisionAction.SECURITY_REFUSAL);
        assertThat(decision.allowGeneration()).isFalse();
    }

    @Test
    @DisplayName("hardCrisis → CRISIS_FLOW")
    void hard_crisis_returns_crisis_flow() {
        var decision = policyEngine.decide(combined(SecurityLevel.CLEAN, true, false, false));
        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CRISIS_FLOW);
    }

    @Test
    @DisplayName("L0 self-harm + L1 moderation flagged → CRISIS_FLOW")
    void l0_self_harm_with_l1_flagged_returns_crisis_flow() {
        var decision = policyEngine.decide(combined(SecurityLevel.CLEAN, false, false, true));
        // L0 flagged but L1 moderationFlagged also true
        SafetyL1Result l1 = new SafetyL1Result(false, true, false, false, false, true, List.of(), 0.6);
        var combinedWithL1Flagged = new CombinedSignal(
                SecurityLevel.CLEAN, false, true, false, false, false, true, false, l1, 0.6
        );
        var crisisDecision = policyEngine.decide(combinedWithL1Flagged);
        assertThat(crisisDecision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
    }

    @Test
    @DisplayName("일반 메시지 → GENERATE + SPECULATIVE")
    void normal_message_returns_generate() {
        var decision = policyEngine.decide(combined(SecurityLevel.CLEAN, false, false, false));
        assertThat(decision.action()).isEqualTo(DecisionAction.GENERATE);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.SPECULATIVE);
        assertThat(decision.allowGeneration()).isTrue();
        assertThat(decision.allowStreaming()).isTrue();
    }

    @Test
    @DisplayName("decisionId는 항상 채워진다")
    void decision_id_is_always_populated() {
        var decision = policyEngine.decide(combined(SecurityLevel.CLEAN, false, false, false));
        assertThat(decision.decisionId()).isNotBlank();
    }
}
