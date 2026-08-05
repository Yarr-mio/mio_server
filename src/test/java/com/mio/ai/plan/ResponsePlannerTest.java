package com.mio.ai.plan;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 이슈 #303 — 정책 결정에서 응답 계약을 만드는 결정론 규칙. */
class ResponsePlannerTest {

    private final ResponsePlanner planner = new ResponsePlanner();

    private PolicyDecision decision(DecisionAction action, GenerationMode mode, RiskLevel risk) {
        return new PolicyDecision(
                "pd_test", action, mode,
                action == DecisionAction.GENERATE ? DeliveryMode.CAUTIOUS_SPECULATIVE : DeliveryMode.CRISIS_FLOW,
                SecurityLevel.CLEAN, action == DecisionAction.GENERATE, true, true,
                InterventionHints.empty(), "test", risk, null, JudgeStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("HIGH 는 감정 인정과 확인 질문 하나로 제한한다")
    void highRiskPlansEmpathicReflection() {
        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.GUARDED, RiskLevel.HIGH));

        assertThat(plan.responseAct()).isEqualTo(ResponseAct.EMPATHIC_REFLECTION);
        assertThat(plan.maxQuestions()).isEqualTo(1);
        assertThat(plan.forbiddenElements())
                .as("HIGH 에서는 현재 안전 확인이 CBT 개입보다 우선한다")
                .contains("cbt_intervention", "advice");
    }

    @Test
    @DisplayName("MEDIUM 은 감정 확인으로 계획한다")
    void mediumRiskPlansEmotionCheck() {
        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.SUPPORTIVE, RiskLevel.MEDIUM));

        assertThat(plan.responseAct()).isEqualTo(ResponseAct.EMOTION_CHECK);
        assertThat(plan.generationFreedom()).isEqualTo(GenerationFreedom.CONSTRAINED);
        assertThat(plan.maxQuestions()).isEqualTo(1);
    }

    @Test
    @DisplayName("룰이 승격했지만 Judge 가 내린 턴은 맥락 확인으로 계획한다")
    void ruleEscalatedLowRiskPlansClarifyContext() {
        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.SUPPORTIVE, RiskLevel.LOW));

        assertThat(plan.responseAct()).isEqualTo(ResponseAct.CLARIFY_CONTEXT);
        assertThat(plan.maxQuestions()).isEqualTo(1);
    }

    @Test
    @DisplayName("일반 대화는 아직 계획 범위가 아니며 그 사실이 값에 남는다")
    void normalTurnStaysUnplanned() {
        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.NORMAL, RiskLevel.CLEAR_LOW));

        assertThat(plan.responseAct())
                .as("계획되지 않은 턴을 계획됨으로 세면 도입 효과를 과대평가한다")
                .isEqualTo(ResponseAct.UNPLANNED);
        assertThat(plan.isContractEnforced()).isFalse();
    }

    @Test
    @DisplayName("위기·보안 경로는 서버 고정 문구이므로 계약 검사 대상이 아니다")
    void fixedResponsePathsAreNotContractEnforced() {
        ResponsePlan crisis = planner.plan(
                decision(DecisionAction.CRISIS_FLOW, GenerationMode.CRISIS, RiskLevel.HARD_CRISIS));
        ResponsePlan refusal = planner.plan(
                decision(DecisionAction.SECURITY_REFUSAL, GenerationMode.CRISIS, RiskLevel.ATTACK));

        assertThat(crisis.responseAct()).isEqualTo(ResponseAct.CRISIS_ASSESSMENT);
        assertThat(refusal.responseAct()).isEqualTo(ResponseAct.SECURITY_REFUSAL);
        assertThat(crisis.generationFreedom()).isEqualTo(GenerationFreedom.TEMPLATE_ONLY);
        assertThat(crisis.isContractEnforced()).isFalse();
    }

    @Test
    @DisplayName("계획은 정책 결정의 위험 등급이나 전달 방식을 바꾸지 않는다")
    void planNeverAltersPolicyDecision() {
        PolicyDecision original =
                decision(DecisionAction.GENERATE, GenerationMode.GUARDED, RiskLevel.HIGH);

        PolicyDecision planned = original.withResponsePlan(planner.plan(original));

        assertThat(planned)
                .extracting(PolicyDecision::riskLevel, PolicyDecision::deliveryMode,
                        PolicyDecision::action, PolicyDecision::requireOutputGuard)
                .containsExactly(original.riskLevel(), original.deliveryMode(),
                        original.action(), original.requireOutputGuard());
    }
}
