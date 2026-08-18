package com.mio.ai.plan;

import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.policy.PolicyEngine;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.safety.SafetySignalCombiner;
import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.EffectiveSecurityResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약이 걸린 턴에는 반드시 검사 지점이 있어야 한다 (이슈 #369, 로드맵 §5.7).
 *
 * <p>{@code ResponseContractResult.UNCHECKED} 는 "계약은 있는데 검사할 자리가 없었다" 는
 * 뜻이다. 지금은 계약이 걸리는 모든 경로가 검사 분기({@code BUFFER} /
 * {@code CAUTIOUS_SPECULATIVE})로 가기 때문에 실제로 발생하지 않는다. 문제는 <b>그 사실이
 * 어디에도 고정돼 있지 않다는 것</b>이다. 정책을 한 줄 고쳐 계약 있는 턴이
 * {@code SPECULATIVE} 로 새면, 그 턴은 조용히 미검사로 기록되고 계약 준수율은 실제보다
 * 좋아 보인다. 준수율은 이슈 {@code #305} 의 입력이므로 그쪽 결과까지 함께 틀어진다.
 *
 * <p>그래서 정책이 만들 수 있는 결정을 훑어 이 불변식을 직접 건다.
 */
@DisplayName("응답 계약 불변식 — 계약이 있으면 검사 지점도 있다")
class ResponseContractInvariantTest {

    private final PolicyEngine policyEngine = new PolicyEngine(new EffectiveSecurityResolver());
    private final ResponsePlanner planner = new ResponsePlanner();
    private final SafetySignalCombiner signalCombiner = new SafetySignalCombiner();

    @Test
    @DisplayName("계약이 걸린 결정은 결코 SPECULATIVE 로 전달되지 않는다")
    void contractEnforcedDecisionsAlwaysHaveACheckpoint() {
        List<String> violations = new ArrayList<>();

        for (PolicyDecision decision : decisionMatrix()) {
            ResponsePlan plan = planner.plan(decision);
            if (!plan.isContractEnforced()) {
                continue;
            }
            // SPECULATIVE 경로에는 계약 검사 호출이 없다 — 그 턴은 UNCHECKED 로 남는다.
            if (decision.deliveryMode() == DeliveryMode.SPECULATIVE) {
                violations.add("%s → act=%s delivery=%s risk=%s mode=%s".formatted(
                        decision.decisionId(), plan.responseAct(), decision.deliveryMode(),
                        decision.riskLevel(), decision.generationMode()));
            }
        }

        assertThat(violations)
                .as("계약이 걸린 턴이 검사 지점 없는 경로로 나가면 UNCHECKED 가 조용히 늘어난다")
                .isEmpty();
    }

    @Test
    @DisplayName("계약이 걸리는 결정이 실제로 존재한다")
    void theMatrixActuallyProducesContractEnforcedDecisions() {
        long enforced = decisionMatrix().stream()
                .map(planner::plan)
                .filter(ResponsePlan::isContractEnforced)
                .count();

        // 이 단언이 없으면 위 테스트는 "계약이 하나도 안 걸려서" 통과할 수 있다.
        assertThat(enforced)
                .as("행렬이 계약 경로를 하나도 만들지 못하면 불변식 테스트는 공허하다")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("계약 없는 턴은 상한이 열려 있어 검사 대상이 아니다")
    void unplannedTurnsAreNotContractEnforced() {
        ResponsePlan unplanned = ResponsePlan.unplanned();

        assertThat(unplanned.isContractEnforced()).isFalse();
        assertThat(unplanned.responseAct()).isEqualTo(ResponseAct.UNPLANNED);
    }

    /**
     * 정책이 만들 수 있는 결정을 폭넓게 훑는다.
     *
     * <p>{@code CombinedSignal} 을 직접 조립하지 않고 <b>실제 {@link SafetySignalCombiner} 로
     * 만든다.</b> 손으로 조립하면 {@code requiresJudge} 를 프로덕션과 다르게 넣기 쉽고,
     * 그러면 실제로는 나올 수 없는 상태에서 불변식이 깨졌다고 보고하게 된다. 결합기가
     * {@code requiresJudge} 를 정하는 규칙(약신호 단독도 Judge 를 부른다)이 이 불변식의
     * 전제이므로, 그 규칙이 바뀌면 이 테스트도 함께 움직여야 한다.
     */
    private List<PolicyDecision> decisionMatrix() {
        List<PolicyDecision> decisions = new ArrayList<>();
        List<RiskLevel> judgeRisks = new ArrayList<>();
        judgeRisks.add(null);
        judgeRisks.addAll(List.of(RiskLevel.CLEAR_LOW, RiskLevel.LOW, RiskLevel.MEDIUM,
                RiskLevel.HIGH, RiskLevel.HARD_CRISIS));

        for (SecurityAssessment security : List.of(SecurityAssessment.clean(),
                SecurityAssessment.suspicious(List.of("roleplay")))) {
            for (boolean riskCandidate : List.of(false, true)) {
                for (boolean emotionSpike : List.of(false, true)) {
                    for (boolean repetitiveNegative : List.of(false, true)) {
                        for (boolean dependencyHint : List.of(false, true)) {
                            for (ModerationResult moderation : List.of(
                                    ModerationResult.clear(), ModerationResult.failOpen())) {
                                SafetyL1Result l1 = new SafetyL1Result(
                                        false, riskCandidate, emotionSpike, repetitiveNegative,
                                        dependencyHint, moderation.flagged(), List.of(), 0.0);
                                CombinedSignal combined =
                                        signalCombiner.combine(security, l1, moderation);
                                for (RiskLevel judgeRisk : judgeRisks) {
                                    decisions.add(policyEngine.decide(
                                            combined, judge(judgeRisk), null, null));
                                }
                            }
                        }
                    }
                }
            }
        }
        return decisions;
    }

    private InputJudgeResult judge(RiskLevel riskLevel) {
        if (riskLevel == null) {
            return null;
        }
        return new InputJudgeResult(
                SecurityVerdict.clean(),
                new RiskVerdict(riskLevel, List.of(), GenerationMode.NORMAL,
                        DeliveryMode.SPECULATIVE, false),
                0.8);
    }
}
