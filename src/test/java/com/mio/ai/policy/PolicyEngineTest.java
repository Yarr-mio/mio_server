package com.mio.ai.policy;

import com.mio.ai.security.EffectiveSecurityResolver;
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

    private final PolicyEngine policyEngine = new PolicyEngine(new EffectiveSecurityResolver());

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
        SessionDelta limitReached = new SessionDelta(2, "none", new java.util.HashMap<>(), 0, new java.util.HashSet<>(), new java.util.HashSet<>());
        var combined = combined(SecurityLevel.CLEAN, false, true, false);
        var decision = policyEngine.decide(combined, judgeResult(RiskLevel.MEDIUM), null, limitReached);
        assertThat(decision.generationMode()).isEqualTo(GenerationMode.SUPPORTIVE);
    }

    // ── 이슈 #262: Judge 보안 판정이 실제로 결정에 반영되는지 ──────────────

    @Test
    @DisplayName("규칙 오탐을 Judge 가 복구하면 가드 없이 정상 생성으로 돌아간다")
    void judgeClearsRuleFalsePositiveAndRestoresNormalPath() {
        // "회사에서 관리자 권한을 못 받아서" 같은 발화 — 규칙은 SUSPICIOUS, Judge 는 CLEAN
        CombinedSignal combined = combined(SecurityLevel.SUSPICIOUS, false, false, false);
        InputJudgeResult judge = new InputJudgeResult(
                new SecurityVerdict(SecurityLevel.CLEAN, List.of(), false),
                RiskVerdict.clearLow(), 0.9);

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision.securityLevel())
                .as("결정에 기록되는 등급은 실제로 적용된 등급이어야 한다")
                .isEqualTo(SecurityLevel.CLEAN);
        assertThat(decision.generationMode())
                .as("Judge 가 깨끗하다고 했는데 GUARDED 로 남으면 오탐 복구가 안 된 것이다")
                .isNotEqualTo(GenerationMode.GUARDED);
    }

    @Test
    @DisplayName("Judge 판정이 실패하면 규칙의 SUSPICIOUS 를 유지한다")
    void judgeFailureKeepsRuleSuspicious() {
        CombinedSignal combined = combined(SecurityLevel.SUSPICIOUS, false, false, false);

        PolicyDecision decision =
                policyEngine.decide(combined, InputJudgeResult.fallback(), null, null);

        assertThat(decision.securityLevel()).isEqualTo(SecurityLevel.SUSPICIOUS);
        assertThat(decision.generationMode()).isEqualTo(GenerationMode.GUARDED);
        assertThat(decision.requireOutputGuard())
                .as("판정을 못 받았으면 보호를 유지한다")
                .isTrue();
    }

    @Test
    @DisplayName("규칙이 놓친 인젝션을 Judge 가 잡으면 가드가 켜진다")
    void judgeRaisesWhatRulesMissed() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);
        InputJudgeResult judge = new InputJudgeResult(
                new SecurityVerdict(SecurityLevel.SUSPICIOUS, List.of("obfuscated"), true),
                RiskVerdict.clearLow(), 0.8);

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision.securityLevel())
                .as("이 경로가 없으면 Judge 보안 판정은 파싱만 되고 버려진다 (#262)")
                .isEqualTo(SecurityLevel.SUSPICIOUS);
        assertThat(decision.requireOutputGuard()).isTrue();
    }

    @Test
    @DisplayName("Judge 는 ATTACK 까지 올리지 못한다 — 거절 확정은 규칙만")
    void judgeCannotCauseSecurityRefusal() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);
        InputJudgeResult judge = new InputJudgeResult(
                new SecurityVerdict(SecurityLevel.ATTACK, List.of("injection"), true),
                RiskVerdict.clearLow(), 0.95);

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision.action())
                .as("LLM 판정만으로 대화를 끊으면 오탐 비용이 너무 크다")
                .isNotEqualTo(DecisionAction.SECURITY_REFUSAL);
        assertThat(decision.securityLevel()).isEqualTo(SecurityLevel.SUSPICIOUS);
    }

    // ── 이슈 #262 후속: 가드 요구 필드도 소비되는지 ────────────────────

    @Test
    @DisplayName("Judge 가 가드를 요구하면 LOW 턴도 가드 경로로 올린다")
    void judgeGuardRequestUpgradesLowTurn() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);
        InputJudgeResult judge = new InputJudgeResult(
                new SecurityVerdict(SecurityLevel.CLEAN, List.of(), true),
                new RiskVerdict(RiskLevel.LOW, List.of(), GenerationMode.NORMAL,
                        DeliveryMode.SPECULATIVE, false),
                0.9);

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision.requireOutputGuard()).isTrue();
        assertThat(decision.deliveryMode())
                .as("플래그만 켜면 아무 일도 안 일어난다 — SPECULATIVE 분기에는 사후 가드가 없다")
                .isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
    }

    @Test
    @DisplayName("safety 가드 요구만 있어도 가드 경로로 올린다")
    void safetyGuardAloneUpgradesDelivery() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);
        InputJudgeResult judge = new InputJudgeResult(
                new SecurityVerdict(SecurityLevel.CLEAN, List.of(), false),
                new RiskVerdict(RiskLevel.LOW, List.of(), GenerationMode.NORMAL,
                        DeliveryMode.SPECULATIVE, true),
                0.9);

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
    }

    @Test
    @DisplayName("가드 요구가 없으면 기존대로 SPECULATIVE 를 유지한다")
    void noGuardRequestKeepsSpeculative() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);
        InputJudgeResult judge = new InputJudgeResult(
                new SecurityVerdict(SecurityLevel.CLEAN, List.of(), false),
                new RiskVerdict(RiskLevel.LOW, List.of(), GenerationMode.NORMAL,
                        DeliveryMode.SPECULATIVE, false),
                0.9);

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision.deliveryMode())
                .as("가드 승격이 남발되면 모든 턴이 느려진다")
                .isEqualTo(DeliveryMode.SPECULATIVE);
        assertThat(decision.requireOutputGuard()).isFalse();
    }

    @Test
    @DisplayName("Judge 판정 실패는 GUARDED + BUFFER + 출력 가드로 보수적으로 처리한다")
    void failedJudgeUsesGuardedBufferedPath() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);

        PolicyDecision decision =
                policyEngine.decide(combined, InputJudgeResult.fallback(), null, null);

        assertThat(decision)
                .as("판정하지 못한 위험 후보가 정상 CLEAR_LOW 무검사 스트리밍으로 합류하면 안 된다")
                .extracting(
                        PolicyDecision::generationMode,
                        PolicyDecision::deliveryMode,
                        PolicyDecision::requireOutputGuard,
                        PolicyDecision::riskLevel)
                .containsExactly(
                        GenerationMode.GUARDED,
                        DeliveryMode.BUFFER,
                        true,
                        RiskLevel.MEDIUM);
    }

    @Test
    @DisplayName("Judge 판정 실패 상태를 PolicyDecision에 명시적으로 남긴다")
    void failedJudgeStatusIsExplicitInPolicyDecision() throws Exception {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);

        PolicyDecision decision =
                policyEngine.decide(combined, InputJudgeResult.fallback(), null, null);

        Object judgeStatus = PolicyDecision.class.getMethod("judgeStatus").invoke(decision);

        assertThat(judgeStatus.toString()).isEqualTo("FAILED");
    }
}
