package com.mio.ai.policy;

import com.mio.ai.security.EffectiveSecurityResolver;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.moderation.ModerationStatus;
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

    /** 같은 신호에서 L0 판정만 받아오지 못한 상태 (이슈 #294). */
    private CombinedSignal unresolvedModeration(CombinedSignal base) {
        return new CombinedSignal(
                base.securityLevel(), base.attackKind(), base.hardCrisis(),
                base.hardCrisisUnverified(), base.riskCandidate(), base.emotionSpike(),
                base.repetitiveNegative(), base.dependencyHint(), base.l0Flagged(),
                base.requiresJudge(), base.l1Result(), base.confidence(),
                base.securityEvidenceUnverifiableByJudge(), ModerationStatus.UNRESOLVED
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
        assertThat(decision.judgeStatus()).isEqualTo(JudgeStatus.SKIPPED);
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
    @DisplayName("Judge SUSPICIOUS + HIGH는 보안 조기 반환으로 강등되지 않고 BUFFER를 유지한다")
    void suspiciousSecurityWithHighRiskKeepsHighBufferedPath() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);
        InputJudgeResult judge = new InputJudgeResult(
                new SecurityVerdict(SecurityLevel.SUSPICIOUS, List.of("obfuscated"), true),
                new RiskVerdict(RiskLevel.HIGH, List.of(), GenerationMode.GUARDED,
                        DeliveryMode.BUFFER, true),
                0.9
        );

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision)
                .extracting(
                        PolicyDecision::securityLevel,
                        PolicyDecision::riskLevel,
                        PolicyDecision::deliveryMode,
                        PolicyDecision::requireOutputGuard)
                .containsExactly(
                        SecurityLevel.SUSPICIOUS,
                        RiskLevel.HIGH,
                        DeliveryMode.BUFFER,
                        true);
    }

    @Test
    @DisplayName("Judge SUSPICIOUS + MEDIUM은 위험도를 LOW로 축약하지 않는다")
    void suspiciousSecurityWithMediumRiskPreservesMediumRisk() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);
        InputJudgeResult judge = new InputJudgeResult(
                new SecurityVerdict(SecurityLevel.SUSPICIOUS, List.of("obfuscated"), true),
                new RiskVerdict(RiskLevel.MEDIUM, List.of(), GenerationMode.SUPPORTIVE,
                        DeliveryMode.CAUTIOUS_SPECULATIVE, true),
                0.9
        );

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
        assertThat(decision.requireOutputGuard()).isTrue();
    }

    @Test
    @DisplayName("비자해 L0 + Judge HIGH는 L0 조기 반환으로 강등되지 않고 BUFFER를 유지한다")
    void nonSelfHarmL0WithHighRiskKeepsHighBufferedPath() {
        SafetyL1Result l1 = new SafetyL1Result(
                false, true, false, false, false, false,
                List.of("moderation:violence"), 0.7
        );
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.CLEAN, false, true,
                false, false, false, true, true, l1, 0.7
        );

        PolicyDecision decision =
                policyEngine.decide(combined, judgeResult(RiskLevel.HIGH), null, null);

        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.BUFFER);
        assertThat(decision.requireOutputGuard()).isTrue();
    }

    @Test
    @DisplayName("Input Judge 스키마 밖 ATTACK risk는 성공 저위험으로 처리하지 않고 fail-closed 한다")
    void unsupportedJudgeAttackRiskFailsClosed() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);
        InputJudgeResult judge = new InputJudgeResult(
                SecurityVerdict.clean(),
                new RiskVerdict(RiskLevel.ATTACK, List.of(), GenerationMode.NORMAL,
                        DeliveryMode.SPECULATIVE, false),
                0.9
        );

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision.judgeStatus()).isEqualTo(JudgeStatus.FAILED);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.BUFFER);
        assertThat(decision.requireOutputGuard()).isTrue();
    }

    @Test
    @DisplayName("성공으로 표시됐어도 risk verdict가 없으면 fail-closed 한다")
    void missingJudgeRiskVerdictFailsClosed() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);
        InputJudgeResult judge = new InputJudgeResult(
                SecurityVerdict.clean(), null, 0.9
        );

        PolicyDecision decision = policyEngine.decide(combined, judge, null, null);

        assertThat(decision.judgeStatus()).isEqualTo(JudgeStatus.FAILED);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.BUFFER);
        assertThat(decision.requireOutputGuard()).isTrue();
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
        assertThat(decision.judgeStatus()).isEqualTo(JudgeStatus.SUCCEEDED);
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
        assertThat(decision.deliveryMode())
                .as("Judge 실패가 SUSPICIOUS의 부분 스트리밍 경로보다 낮은 보호 수준으로 처리되면 안 된다")
                .isEqualTo(DeliveryMode.BUFFER);
        assertThat(decision.requireOutputGuard())
                .as("판정을 못 받았으면 보호를 유지한다")
                .isTrue();
    }

    @Test
    @DisplayName("SUSPICIOUS여도 자해 moderation 교집합이면 위기 플로우를 우선한다")
    void suspiciousSelfHarmModerationRoutesToCrisisBeforeSecurityGuard() {
        SafetyL1Result l1 = new SafetyL1Result(
                false, true, false, false, false, true,
                List.of("moderation:self-harm"), 0.9
        );
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.SUSPICIOUS, false, true,
                false, false, false, true, true, l1, 0.9
        );

        PolicyDecision decision =
                policyEngine.decide(combined, InputJudgeResult.fallback(), null, null);

        assertThat(decision.action())
                .as("보안 의심 경로가 확정적 자해 신호를 가려서는 안 된다")
                .isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.crisisTrigger()).isEqualTo(com.mio.ai.crisis.CrisisTrigger.MODERATION);
    }

    @Test
    @DisplayName("Judge 실패는 비자해 L0 플래그도 완전 버퍼링한다")
    void failedJudgeBuffersNonSelfHarmL0Signal() {
        SafetyL1Result l1 = new SafetyL1Result(
                false, true, false, false, false, false,
                List.of("moderation:violence"), 0.7
        );
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.CLEAN, false, true,
                false, false, false, true, true, l1, 0.7
        );

        PolicyDecision decision =
                policyEngine.decide(combined, InputJudgeResult.fallback(), null, null);

        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.BUFFER);
        assertThat(decision.requireOutputGuard()).isTrue();
        assertThat(decision.judgeStatus()).isEqualTo(JudgeStatus.FAILED);
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
    @DisplayName("아무 신호도 없던 턴은 기존대로 SPECULATIVE 를 유지한다")
    void noSignalTurnKeepsSpeculative() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, false, false);
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

    // === 룰의 위험 승격은 Judge 하향 판정에도 남는다 (이슈 #298) ===

    /** 룰이 위험 후보로 보고 Judge 호출을 요구한 상태 — 실제 결합부가 만드는 조합이다. */
    private CombinedSignal ruleEscalated() {
        SafetyL1Result l1 = new SafetyL1Result(
                false, true, false, false, false, false, List.of("risk_keyword"), 0.5);
        return new CombinedSignal(
                SecurityLevel.CLEAN, false, true, false, false, false, false, true, l1, 0.5);
    }

    @Test
    @DisplayName("룰이 위험 후보로 올린 턴은 Judge 가 LOW 로 내려도 무검사로 나가지 않는다")
    void ruleEscalatedTurnNeverEndsUnguardedAfterJudgeDowngrade() {
        CombinedSignal combined = ruleEscalated();

        PolicyDecision decision =
                policyEngine.decide(combined, judgeResult(RiskLevel.LOW), null, null);

        assertThat(decision)
                .as("계획·수단 발화처럼 단문만으로 판정이 어려운 턴이 무검사 스트리밍으로 끝났다")
                .extracting(
                        PolicyDecision::generationMode,
                        PolicyDecision::deliveryMode,
                        PolicyDecision::requireOutputGuard)
                .containsExactly(
                        GenerationMode.SUPPORTIVE,
                        DeliveryMode.CAUTIOUS_SPECULATIVE,
                        true);
    }

    @Test
    @DisplayName("룰 승격 턴을 Judge 가 CLEAR_LOW 로 내려도 마찬가지다")
    void ruleEscalatedTurnSurvivesClearLowJudgement() {
        CombinedSignal combined = ruleEscalated();

        PolicyDecision decision =
                policyEngine.decide(combined, judgeResult(RiskLevel.CLEAR_LOW), null, null);

        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
        assertThat(decision.generationMode()).isEqualTo(GenerationMode.SUPPORTIVE);
    }

    @Test
    @DisplayName("룰 승격이 위험 등급을 올리지는 않는다")
    void ruleEscalationDoesNotInflateRiskLevel() {
        CombinedSignal combined = ruleEscalated();

        PolicyDecision decision =
                policyEngine.decide(combined, judgeResult(RiskLevel.LOW), null, null);

        assertThat(decision.riskLevel())
                .as("전달 방식만 보수화하고 판정값은 Judge 의 것을 그대로 남긴다")
                .isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("의존 신호 단독 턴도 Judge 하향 판정 후 무검사로 나가지 않는다")
    void dependencySignalTurnStaysGuarded() {
        SafetyL1Result l1 = new SafetyL1Result(
                false, false, false, false, true, false, List.of("dependency_phrase"), 0.0);
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.CLEAN, false, false, false, false, true,
                false, true, l1, 0.0);

        PolicyDecision decision =
                policyEngine.decide(combined, judgeResult(RiskLevel.LOW), null, null);

        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
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
                        PolicyDecision::allowStreaming,
                        PolicyDecision::requireOutputGuard,
                        PolicyDecision::riskLevel)
                .containsExactly(
                        GenerationMode.GUARDED,
                        DeliveryMode.BUFFER,
                        true,
                        true,
                        RiskLevel.MEDIUM);
    }

    @Test
    @DisplayName("Judge 판정 실패 상태를 PolicyDecision에 명시적으로 남긴다")
    void failedJudgeStatusIsExplicitInPolicyDecision() {
        CombinedSignal combined = combined(SecurityLevel.CLEAN, false, true, false);

        PolicyDecision decision =
                policyEngine.decide(combined, InputJudgeResult.fallback(), null, null);

        assertThat(decision.judgeStatus()).isEqualTo(JudgeStatus.FAILED);
    }

    // === L0 Moderation 판정 부재 (이슈 #294) ===

    @Test
    @DisplayName("L0 판정을 받지 못한 턴은 무검사 SPECULATIVE 로 내려가지 않는다")
    void unresolvedModerationNeverStreamsWithoutOutputCheck() {
        CombinedSignal combined = unresolvedModeration(
                combined(SecurityLevel.CLEAN, false, false, false));

        PolicyDecision decision = policyEngine.decide(combined);

        assertThat(decision)
                .as("안전 계층 하나가 통째로 빠진 턴을 정상 판정 턴과 같게 처리하면 안 된다")
                .extracting(
                        PolicyDecision::deliveryMode,
                        PolicyDecision::requireOutputGuard,
                        PolicyDecision::moderationStatus)
                .containsExactly(
                        DeliveryMode.CAUTIOUS_SPECULATIVE,
                        true,
                        ModerationStatus.UNRESOLVED);
    }

    @Test
    @DisplayName("L0 미해결은 Judge 가 LOW 로 판정한 턴도 가드 경로로 올린다")
    void unresolvedModerationGuardsSucceededLowTurn() {
        CombinedSignal combined = unresolvedModeration(
                combined(SecurityLevel.CLEAN, false, true, false));

        PolicyDecision decision =
                policyEngine.decide(combined, judgeResult(RiskLevel.LOW), null, null);

        assertThat(decision.deliveryMode())
                .as("Judge 는 L0 가 보던 표현을 대신 보지 않는다 — 두 축은 독립이다")
                .isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("L0 미해결은 L1 약신호 단독 경로도 가드 경로로 올린다")
    void unresolvedModerationGuardsWeakSignalOnlyTurn() {
        CombinedSignal combined = unresolvedModeration(
                combinedWithSignals(SecurityLevel.CLEAN, false, false, true));

        PolicyDecision decision = policyEngine.decide(combined);

        assertThat(decision.generationMode()).isEqualTo(GenerationMode.SUPPORTIVE);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
    }

    @Test
    @DisplayName("L0 판정을 받아온 정상 턴의 전달 방식은 바뀌지 않는다")
    void resolvedModerationKeepsSpeculative() {
        PolicyDecision decision =
                policyEngine.decide(combined(SecurityLevel.CLEAN, false, false, false));

        assertThat(decision)
                .as("가드 승격이 정상 턴까지 번지면 모든 대화가 느려진다")
                .extracting(
                        PolicyDecision::deliveryMode,
                        PolicyDecision::requireOutputGuard,
                        PolicyDecision::moderationStatus)
                .containsExactly(
                        DeliveryMode.SPECULATIVE,
                        false,
                        ModerationStatus.RESOLVED);
    }

    @Test
    @DisplayName("L0 미해결이 확정 위기 경로를 바꾸지 않는다")
    void unresolvedModerationDoesNotDowngradeCrisisFlow() {
        CombinedSignal combined = unresolvedModeration(
                combined(SecurityLevel.CLEAN, true, false, false));

        PolicyDecision decision = policyEngine.decide(combined);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.CRISIS_FLOW);
        assertThat(decision.moderationStatus()).isEqualTo(ModerationStatus.UNRESOLVED);
    }

    @Test
    @DisplayName("L0 미해결이 Judge 실패의 BUFFER 하한을 낮추지 않는다")
    void unresolvedModerationKeepsFailedJudgeBuffer() {
        CombinedSignal combined = unresolvedModeration(
                combined(SecurityLevel.CLEAN, false, true, false));

        PolicyDecision decision =
                policyEngine.decide(combined, InputJudgeResult.fallback(), null, null);

        assertThat(decision.deliveryMode()).isEqualTo(DeliveryMode.BUFFER);
        assertThat(decision.judgeStatus()).isEqualTo(JudgeStatus.FAILED);
        assertThat(decision.moderationStatus()).isEqualTo(ModerationStatus.UNRESOLVED);
    }
}
