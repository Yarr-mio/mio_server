package com.mio.ai.policy;

import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이슈 #255 — hardCrisisUnverified 검증 게이트.
 *
 * <p>강등된 위기 후보는 InputJudge 판정으로만 해제된다. 판정이 없거나 실패하면
 * 위기를 유지한다(fail-closed).
 */
class PolicyEngineCrisisVerificationTest {

    private final PolicyEngine policyEngine = new PolicyEngine();

    private CombinedSignal unverified() {
        SafetyL1Result l1 = new SafetyL1Result(
                false, true, true, false, false, false, false,
                List.of("crisis_keyword:죽고싶다", "crisis_context_marker:third_person"), 0.6);
        return new CombinedSignal(
                SecurityLevel.CLEAN, false, true, true, false, false, false,
                false, true, l1, 0.6);
    }

    private InputJudgeResult judged(RiskLevel riskLevel) {
        return new InputJudgeResult(
                SecurityVerdict.clean(),
                new RiskVerdict(riskLevel, List.of(), GenerationMode.NORMAL, DeliveryMode.SPECULATIVE, false),
                0.8);
    }

    @Test
    @DisplayName("검증 대기 상태에서 판정이 없으면 위기를 유지한다 (fail-closed)")
    void missingJudgeResultKeepsCrisis() {
        var decision = policyEngine.decide(unverified(), null, null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
    }

    @Test
    @DisplayName("InputJudge 실패 폴백이면 위기를 유지한다 (fail-closed)")
    void failedJudgeKeepsCrisis() {
        var decision = policyEngine.decide(unverified(), InputJudgeResult.fallback(), null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
    }

    @Test
    @DisplayName("InputJudge가 HARD_CRISIS로 확인하면 위기 플로우로 간다")
    void judgeConfirmsCrisis() {
        var decision = policyEngine.decide(unverified(), judged(RiskLevel.HARD_CRISIS), null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
    }

    @Test
    @DisplayName("InputJudge가 LOW로 판정하면 일반 생성으로 내려간다")
    void judgeClearsToLow() {
        var decision = policyEngine.decide(unverified(), judged(RiskLevel.LOW), null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.GENERATE);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("InputJudge가 CLEAR_LOW로 판정하면 일반 생성으로 내려간다")
    void judgeClearsToClearLow() {
        var decision = policyEngine.decide(unverified(), judged(RiskLevel.CLEAR_LOW), null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.GENERATE);
    }

    @Test
    @DisplayName("InputJudge가 MEDIUM으로 판정하면 위기를 유지한다 — 해제는 명시적 저위험 판정만")
    void judgeMediumKeepsCrisis() {
        var decision = policyEngine.decide(unverified(), judged(RiskLevel.MEDIUM), null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
    }

    /**
     * InputJudge 프롬프트가 모델에게 제시하는 risk_level 최댓값은 HIGH다(HARD_CRISIS는 선택지에 없다).
     * 따라서 실제 운영에서 강등된 위기가 만날 수 있는 가장 높은 판정이 HIGH이며,
     * 이 값이 위기를 해제하면 검증 게이트가 사실상 무효가 된다.
     */
    @Test
    @DisplayName("InputJudge가 HIGH로 판정하면 위기를 유지한다 — 게이트 무효화 방지")
    void judgeHighKeepsCrisis() {
        var decision = policyEngine.decide(unverified(), judged(RiskLevel.HIGH), null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
    }

    @Test
    @DisplayName("강등되지 않은 발화에 Judge가 HARD_CRISIS를 주면 위기 플로우로 올린다")
    void judgeHardCrisisEscalatesPlainMessage() {
        SafetyL1Result l1 = new SafetyL1Result(
                false, false, true, false, false, false, false, List.of(), 0.5);
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.CLEAN, false, false, true, false, false, false, false, false, l1, 0.5);

        var decision = policyEngine.decide(combined, judged(RiskLevel.HARD_CRISIS), null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
    }

    @Test
    @DisplayName("확정된 hardCrisis는 판정 없이도 기존대로 위기 플로우다")
    void confirmedHardCrisisUnaffected() {
        SafetyL1Result l1 = new SafetyL1Result(
                true, false, false, false, false, false, false, List.of("crisis_keyword:죽고싶다"), 0.9);
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.CLEAN, true, false, false, false, false, false, false, false, l1, 0.9);

        var decision = policyEngine.decide(combined, null, null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
    }

    @Test
    @DisplayName("보안 ATTACK은 검증 대기보다 우선한다")
    void attackTakesPrecedence() {
        SafetyL1Result l1 = new SafetyL1Result(
                false, true, true, false, false, false, false, List.of(), 0.6);
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.ATTACK, false, true, true, false, false, false, false, false, l1, 0.6);

        var decision = policyEngine.decide(combined, null, null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.SECURITY_REFUSAL);
    }
}
