package com.mio.ai.policy;

import com.mio.ai.judge.CrisisAttribution;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.EffectiveSecurityResolver;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 강등된 위기 후보({@code hardCrisisUnverified})의 해제 조건 (이슈 #505).
 *
 * <p>이슈 #297 은 3인칭 걱정 발화의 위기 오탐을 잡기 위해 {@code crisis_attribution} 을
 * 도입했다. 그런데 그 값 <b>하나만으로</b> 해제되면, 판정자를 조종할 수 있는 사용자 텍스트가
 * 곧 해제 스위치가 된다. 이 저장소는 보안 축에서 이미 같은 원칙을 지킨다 —
 * {@code EffectiveSecurityResolver} 는 LLM 이 {@code SUSPICIOUS} 까지만 올리게 하고
 * {@code ATTACK} 확정은 결정론 룰 전용으로 둔다. 안전 축에도 같은 규율을 적용한다.
 *
 * <p>해제 조건: <b>결정론 마커와 Judge 귀속이 같은 방향일 때만.</b>
 * {@code CrisisContextMarkers} 가 이미 마커를 계산해 {@code crisis_context_marker:*} 시그널로
 * 남기므로 새로 만들 것이 없고 추가 LLM 호출도 없다.
 */
class PolicyEngineCrisisAttributionTest {

    private final PolicyEngine policyEngine = new PolicyEngine(new EffectiveSecurityResolver());

    private static final String MARKER_THIRD_PERSON = "crisis_context_marker:third_person";
    private static final String MARKER_QUOTATION = "crisis_context_marker:quotation";
    private static final String MARKER_NEGATION = "crisis_context_marker:negation";
    private static final String MARKER_PAST_RECOVERY = "crisis_context_marker:past_recovery";
    /** 가시 구분자 우회로 강등된 경우 — 맥락 마커가 없다 (`SafetyL1:133-138`). */
    private static final String SIGNAL_OBFUSCATED = "crisis_obfuscated_keyword:죽고싶어";

    /** 위기 키워드는 걸렸으나 확정하지 않고 Judge 검증으로 넘긴 상태. */
    private CombinedSignal downgradedCrisis(List<String> l1Signals) {
        SafetyL1Result l1 = new SafetyL1Result(
                false, true, true, false, false, false, false, l1Signals, 0.6);
        return new CombinedSignal(
                SecurityLevel.CLEAN, false, true, true, false, false, false,
                true, true, l1, 0.6);
    }

    private InputJudgeResult judged(RiskLevel riskLevel, CrisisAttribution attribution) {
        return new InputJudgeResult(
                SecurityVerdict.clean(),
                new RiskVerdict(riskLevel, List.of(), GenerationMode.NORMAL,
                        DeliveryMode.SPECULATIVE, false, attribution),
                0.8);
    }

    private DecisionAction actionFor(List<String> l1Signals,
                                     RiskLevel riskLevel,
                                     CrisisAttribution attribution) {
        return policyEngine.decide(downgradedCrisis(l1Signals),
                judged(riskLevel, attribution), null, null).action();
    }

    // ── 이슈 #297 의 성과는 유지된다 ────────────────────────────────────────────

    @Test
    @DisplayName("결정론 마커와 Judge 귀속이 일치하면 위기를 해제한다 — 이슈 #297 유지")
    void agreeingMarkerAndAttributionStillClearsCrisis() {
        assertThat(actionFor(List.of(MARKER_THIRD_PERSON), RiskLevel.HIGH, CrisisAttribution.THIRD_PARTY))
                .as("3인칭 마커 + THIRD_PARTY 는 해제된다 — 친구 걱정 발화가 위기 개입을 받으면 안 된다")
                .isNotEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(actionFor(List.of(MARKER_QUOTATION), RiskLevel.MEDIUM, CrisisAttribution.QUOTED))
                .isNotEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(actionFor(List.of(MARKER_PAST_RECOVERY), RiskLevel.MEDIUM, CrisisAttribution.SELF_PAST))
                .isNotEqualTo(DecisionAction.CRISIS_FLOW);
    }

    // ── 새로 닫는 경로 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("결정론 마커가 없으면 귀속 판정만으로 위기를 해제하지 않는다")
    void attributionAloneCannotClearWithoutDeterministicMarker() {
        // 가시 구분자 우회로 강등된 경우다. 사용자가 위기 어휘를 일부러 쪼갰는데
        // 판정자가 "타인 얘기"라고 답하면 해제되던 경로다.
        assertThat(actionFor(List.of(SIGNAL_OBFUSCATED), RiskLevel.HIGH, CrisisAttribution.THIRD_PARTY))
                .as("마커 없는 강등은 귀속만으로 해제될 수 없다")
                .isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(actionFor(List.of(SIGNAL_OBFUSCATED), RiskLevel.MEDIUM, CrisisAttribution.QUOTED))
                .isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(actionFor(List.of(), RiskLevel.HIGH, CrisisAttribution.SELF_PAST))
                .isEqualTo(DecisionAction.CRISIS_FLOW);
    }

    @Test
    @DisplayName("부정 마커만 있으면 귀속 판정으로 위기를 해제하지 않는다")
    void negationMarkerIsNotEvidenceOfNonSelfAttribution() {
        // 부정은 "누구의 위기인가"가 아니라 "위기 진술이 아님"을 뜻한다.
        // 그 판단은 위험도 쪽에서 하고, 비-자기 귀속의 근거로는 세지 않는다.
        assertThat(actionFor(List.of(MARKER_NEGATION), RiskLevel.HIGH, CrisisAttribution.QUOTED))
                .isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(actionFor(List.of(MARKER_NEGATION), RiskLevel.HIGH, CrisisAttribution.THIRD_PARTY))
                .isEqualTo(DecisionAction.CRISIS_FLOW);
    }

    /**
     * 두 계층이 <b>어느 종류인지까지 합의할 것을 요구하지 않는다.</b> 둘은 서로 다른 단서를
     * 보고, {@code CrisisContextMarkers} 는 메시지당 마커를 하나만 돌려준다.
     * {@code "친구가 '죽고 싶다'고 했어요"} 는 마커가 {@code third_person} 인데 판정자는
     * {@code QUOTED} 라고 답할 수 있다 — 종류 일치를 요구하면 이슈 #297 이 고친 3인칭 걱정
     * 발화의 위기 오탐이 되살아난다.
     */
    @Test
    @DisplayName("마커 종류와 귀속 종류가 달라도 비-자기 증거가 있으면 해제한다")
    void markerKindNeedNotMatchAttributionKind() {
        assertThat(actionFor(List.of(MARKER_THIRD_PERSON), RiskLevel.MEDIUM, CrisisAttribution.QUOTED))
                .as("3인칭 인용문은 마커 하나만 남지만 두 판정 모두 '본인 아님'을 말한다")
                .isNotEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(actionFor(List.of(MARKER_QUOTATION), RiskLevel.MEDIUM, CrisisAttribution.THIRD_PARTY))
                .isNotEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(actionFor(List.of(MARKER_PAST_RECOVERY), RiskLevel.MEDIUM, CrisisAttribution.THIRD_PARTY))
                .isNotEqualTo(DecisionAction.CRISIS_FLOW);
    }

    // ── 기존 규율 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("본인 현재 위기로 판정하면 마커가 있어도 해제하지 않는다")
    void selfCurrentNeverClears() {
        assertThat(actionFor(List.of(MARKER_THIRD_PERSON), RiskLevel.LOW, CrisisAttribution.SELF_CURRENT))
                .as("두 값이 어긋날 때 더 구체적인 쪽(본인 현재)을 따른다")
                .isEqualTo(DecisionAction.CRISIS_FLOW);
    }

    @Test
    @DisplayName("판정 실패·판정 부재는 위기를 유지한다 (fail-closed)")
    void missingOrFailedJudgementKeepsCrisis() {
        assertThat(policyEngine.decide(downgradedCrisis(List.of(MARKER_THIRD_PERSON)), null, null, null)
                .action())
                .isEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(policyEngine.decide(downgradedCrisis(List.of(MARKER_THIRD_PERSON)),
                        InputJudgeResult.fallback(), null, null).action())
                .isEqualTo(DecisionAction.CRISIS_FLOW);
    }

    /**
     * 귀속 판정이 없거나 {@code NONE} 이면 위험도로 판단하는 기존 경로다.
     * 이 PR 은 <b>귀속 지름길</b>만 닫는다 — 위험도 기반 해제는 #297 이전부터 있던 설계이고,
     * 그 문턱을 함께 옮기면 회귀 범위가 커지므로 별도 판단으로 남긴다.
     */
    @Test
    @DisplayName("귀속 판정이 없으면 위험도로 판단하는 기존 경로는 그대로다")
    void riskLevelFallbackIsUnchanged() {
        assertThat(actionFor(List.of(SIGNAL_OBFUSCATED), RiskLevel.CLEAR_LOW, null))
                .isNotEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(actionFor(List.of(SIGNAL_OBFUSCATED), RiskLevel.LOW, CrisisAttribution.NONE))
                .isNotEqualTo(DecisionAction.CRISIS_FLOW);
        assertThat(actionFor(List.of(SIGNAL_OBFUSCATED), RiskLevel.MEDIUM, CrisisAttribution.NONE))
                .isEqualTo(DecisionAction.CRISIS_FLOW);
    }
}
