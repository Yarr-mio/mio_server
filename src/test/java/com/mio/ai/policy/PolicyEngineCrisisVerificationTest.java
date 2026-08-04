package com.mio.ai.policy;

import com.mio.ai.security.EffectiveSecurityResolver;
import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.AttackKind;
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

    private final PolicyEngine policyEngine = new PolicyEngine(new EffectiveSecurityResolver());

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
    @DisplayName("risk verdict가 누락된 판정은 위기를 유지한다")
    void missingRiskVerdictKeepsCrisis() {
        InputJudgeResult incomplete = new InputJudgeResult(
                SecurityVerdict.clean(), null, 0.9);

        var decision = policyEngine.decide(unverified(), incomplete, null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
        assertThat(decision.judgeStatus()).isEqualTo(JudgeStatus.FAILED);
    }

    @Test
    @DisplayName("security verdict가 누락된 저위험 판정은 위기를 해제하지 않는다")
    void missingSecurityVerdictCannotClearCrisis() {
        InputJudgeResult incomplete = new InputJudgeResult(
                null,
                new RiskVerdict(RiskLevel.LOW, List.of(),
                        GenerationMode.NORMAL, DeliveryMode.SPECULATIVE, false),
                0.9);

        var decision = policyEngine.decide(unverified(), incomplete, null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
        assertThat(decision.judgeStatus()).isEqualTo(JudgeStatus.FAILED);
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
    @DisplayName("모델 조작 ATTACK은 검증 대기보다 우선한다")
    void manipulationAttackTakesPrecedence() {
        SafetyL1Result l1 = new SafetyL1Result(
                false, true, true, false, false, false, false, List.of(), 0.6);
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.ATTACK, AttackKind.MANIPULATION,
                false, true, true, false, false, false, false, false, l1, 0.6);

        var decision = policyEngine.decide(combined, null, null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.SECURITY_REFUSAL);
        assertThat(decision.crisisTrigger()).isNull();
    }

    /**
     * 자해 질의는 등급상 ATTACK 이지만 거절이 아니라 위기 플로우로 간다 (이슈 #260).
     *
     * <p>이전에는 ATTACK 검사가 무조건 먼저라 "자살 방법 알려줘"가 고정 거절 응답으로 처리되고
     * 핫라인·{@code crisis_events}·crisis SSE 가 전부 발생하지 않았다.
     */
    @Test
    @DisplayName("자해 질의 ATTACK은 거절 대신 위기 플로우로 라우팅된다")
    void selfHarmInquiryAttackRoutesToCrisisFlow() {
        SafetyL1Result l1 = new SafetyL1Result(
                true, false, false, false, false, false, List.of("crisis_keyword:자살"), 0.9);
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.ATTACK, AttackKind.SELF_HARM_INQUIRY,
                true, false, false, false, false, false, false, false, l1, 0.9);

        var decision = policyEngine.decide(combined, null, null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.riskLevel()).isEqualTo(RiskLevel.HARD_CRISIS);
        assertThat(decision.crisisTrigger()).isEqualTo(CrisisTrigger.SELF_HARM_INQUIRY);
        assertThat(decision.allowGeneration())
                .as("수단 정보가 응답에 섞이지 않도록 본문 생성은 계속 차단되어야 한다")
                .isFalse();
    }

    /**
     * L1 이 위기 키워드를 하나도 잡지 못한 자해 질의도 위기로 가야 한다.
     *
     * <p>보안 판정만으로 라우팅이 결정되므로 L1 신호에 의존하지 않는다는 것을 고정한다.
     */
    @Test
    @DisplayName("L1 신호가 없어도 자해 질의만으로 위기 플로우에 진입한다")
    void selfHarmInquiryRoutesToCrisisWithoutL1Signal() {
        CombinedSignal combined = new CombinedSignal(
                SecurityLevel.ATTACK, AttackKind.SELF_HARM_INQUIRY,
                false, false, false, false, false, false, false, false,
                SafetyL1Result.clear(), 0.0);

        var decision = policyEngine.decide(combined, null, null, null);

        assertThat(decision.action()).isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(decision.crisisTrigger()).isEqualTo(CrisisTrigger.SELF_HARM_INQUIRY);
    }
}
