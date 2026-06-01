package com.mio.ai.policy;

import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    private final PolicyEngine policyEngine = new PolicyEngine();

    private CombinedSignal combined(SecurityLevel security, boolean hardCrisis,
                                    boolean riskCandidate, boolean l0Flagged) {
        SafetyL1Result l1 = new SafetyL1Result(
                hardCrisis, riskCandidate, false, false, false, l0Flagged,
                List.of(), hardCrisis ? 0.9 : 0.0
        );
        return new CombinedSignal(
                security, hardCrisis, riskCandidate,
                false, false, false, l0Flagged, false, l1, l1.combinedConfidence()
        );
    }

    private CombinedSignal combinedWithSignals(SecurityLevel security, boolean hardCrisis,
                                               boolean emotionSpike, boolean repetitiveNeg) {
        SafetyL1Result l1 = new SafetyL1Result(
                hardCrisis, false, emotionSpike, repetitiveNeg, false, false,
                List.of(), 0.0
        );
        return new CombinedSignal(
                security, hardCrisis, false, emotionSpike, repetitiveNeg,
                false, false, false, l1, 0.0
        );
    }

    private InputJudgeResult judgeResult(RiskLevel riskLevel) {
        return new InputJudgeResult(
                SecurityVerdict.clean(),
                new RiskVerdict(riskLevel, List.of(), GenerationMode.NORMAL, DeliveryMode.SPECULATIVE, false),
                0.8
        );
    }

    // === Phase 1 scenarios ===

    @Test
    @DisplayName("ATTACK → SECURITY_REFUSAL")
    void attack_returns_security_refusal() {
        var decision = policyEngine.decide(combined(SecurityLevel.ATTACK, false, false, false));
        assertThat(decision.action()).isEqualTo(DecisionAction.SECURITY_REFUSAL);
        assertThat(decision.allowGeneration()).isFalse();
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.ATTACK);
    }

    @Test
    @DisplayName("hardCrisis → CRISIS_FLOW")
    void hard_crisis_returns_crisis_flow() {
        var decision = policyEngine.decide(combined(SecurityLevel.CLEAN, true, false, false));
        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
    }

    @Test
    @DisplayName("일반 메시지 → GENERATE + SPECULATIVE + CLEAR_LOW")
    void normal_message_returns_generate_clear_low() {
        var decision = policyEngine.decide(combined(SecurityLevel.CLEAN, false, false, false));
        assertThat(decision.action()).isEqualTo(DecisionAction.GENERATE);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.SPECULATIVE);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.CLEAR_LOW);
        assertThat(decision.allowGeneration()).isTrue();
    }

    // === Phase 2 scenarios ===

    @Test
    @DisplayName("SUSPICIOUS → GENERATE + GUARDED + CAUTIOUS_SPECULATIVE + requireOutputGuard")
    void suspicious_returns_guarded_cautious_speculative() {
        var decision = policyEngine.decide(combined(SecurityLevel.SUSPICIOUS, false, false, false));
        assertThat(decision.action()).isEqualTo(DecisionAction.GENERATE);
        assertThat(decision.generationMode()).isEqualTo(GenerationMode.GUARDED);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
        assertThat(decision.requireOutputGuard()).isTrue();
    }

    @Test
    @DisplayName("InputJudge HIGH → GUARDED + BUFFER + requireOutputGuard")
    void input_judge_high_returns_buffer() {
        var combined = combined(SecurityLevel.CLEAN, false, true, false);
        var decision = policyEngine.decide(combined, judgeResult(RiskLevel.HIGH), null, null);
        assertThat(decision.action()).isEqualTo(DecisionAction.GENERATE);
        assertThat(decision.generationMode()).isEqualTo(GenerationMode.GUARDED);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.BUFFER);
        assertThat(decision.requireOutputGuard()).isTrue();
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("InputJudge MEDIUM → SUPPORTIVE + CAUTIOUS_SPECULATIVE")
    void input_judge_medium_returns_cautious_speculative() {
        var combined = combined(SecurityLevel.CLEAN, false, true, false);
        var decision = policyEngine.decide(combined, judgeResult(RiskLevel.MEDIUM), null, null);
        assertThat(decision.action()).isEqualTo(DecisionAction.GENERATE);
        assertThat(decision.generationMode()).isEqualTo(GenerationMode.SUPPORTIVE);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    @DisplayName("InputJudge LOW → NORMAL + SPECULATIVE")
    void input_judge_low_returns_speculative() {
        var combined = combined(SecurityLevel.CLEAN, false, true, false);
        var decision = policyEngine.decide(combined, judgeResult(RiskLevel.LOW), null, null);
        assertThat(decision.action()).isEqualTo(DecisionAction.GENERATE);
        assertThat(decision.generationMode()).isEqualTo(GenerationMode.NORMAL);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.SPECULATIVE);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("L1 repetitiveNegative 단독 → SUPPORTIVE + SPECULATIVE")
    void repetitive_negative_alone_returns_supportive() {
        var combined = combinedWithSignals(SecurityLevel.CLEAN, false, false, true);
        var decision = policyEngine.decide(combined);
        assertThat(decision.action()).isEqualTo(DecisionAction.GENERATE);
        assertThat(decision.generationMode()).isEqualTo(GenerationMode.SUPPORTIVE);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.SPECULATIVE);
    }

    @Test
    @DisplayName("decisionId는 항상 채워진다")
    void decision_id_is_always_populated() {
        var decision = policyEngine.decide(combined(SecurityLevel.CLEAN, false, false, false));
        assertThat(decision.decisionId()).isNotBlank();
    }

    @Test
    @DisplayName("소크라테스 2회 제한 도달 시 SUPPORTIVE 유지")
    void socratic_limit_reached_keeps_supportive() {
        SessionDelta limitReached = new SessionDelta(2, new java.util.HashMap<>());
        var combined = combined(SecurityLevel.CLEAN, false, true, false);
        var decision = policyEngine.decide(combined, judgeResult(RiskLevel.MEDIUM), null, limitReached);
        assertThat(decision.generationMode()).isEqualTo(GenerationMode.SUPPORTIVE);
    }
}
