package com.mio.ai.plan;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 이슈 #303 — 정책 결정에서 응답 계약을 만드는 결정론 규칙. */
class ResponsePlannerTest {

    private final ResponsePlanner planner = new ResponsePlanner();

    {
        // 이슈 #545 CBT 질문 게이트는 운영 기본 OFF다 — 이 테스트는 게이트가 켜진 동작을 검증한다.
        ReflectionTestUtils.setField(planner, "cbtQuestionGateEnabled", true);
    }

    private PolicyDecision decision(DecisionAction action, GenerationMode mode, RiskLevel risk) {
        return decision(action, mode, risk, InterventionHints.empty());
    }

    private PolicyDecision decision(DecisionAction action, GenerationMode mode, RiskLevel risk,
                                    InterventionHints hints) {
        return new PolicyDecision(
                "pd_test", action, mode,
                action == DecisionAction.GENERATE ? DeliveryMode.CAUTIOUS_SPECULATIVE : DeliveryMode.CRISIS_FLOW,
                SecurityLevel.CLEAN, action == DecisionAction.GENERATE, true, true,
                hints, "test", risk, null, JudgeStatus.SUCCEEDED);
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
    @DisplayName("폴백 응답도 서버 문구이므로 계약 검사 대상이 아니다")
    void fallbackPathIsNotContractEnforced() {
        ResponsePlan plan = planner.plan(
                decision(DecisionAction.FALLBACK, GenerationMode.NORMAL, RiskLevel.CLEAR_LOW));

        assertThat(plan.generationFreedom()).isEqualTo(GenerationFreedom.TEMPLATE_ONLY);
        assertThat(plan.isContractEnforced()).isFalse();
    }

    // ── 이슈 #545 STEP 4 — MIO-CBT-010/011: 왜곡 감지 이력이 있는 세션의 게이트 닫힌 턴 ──

    @Test
    @DisplayName("왜곡이 감지된 적 있는 세션에서 이번 턴 게이트가 닫혀 있으면 질문 0개로 계약을 건다")
    void cbtRelevantSessionWithClosedGate_getsZeroQuestionContract() {
        SessionDelta sessionDelta = new SessionDelta(
                0, "none", Map.of("catastrophizing", 1), 0, new HashSet<>(), new HashSet<>());

        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.NORMAL, RiskLevel.CLEAR_LOW,
                        InterventionHints.empty()),
                sessionDelta);

        assertThat(plan.isContractEnforced()).isTrue();
        assertThat(plan.maxQuestions()).isZero();
        assertThat(plan.forbiddenElements()).contains("cbt_intervention");
    }

    @Test
    @DisplayName("왜곡이 한 번도 감지된 적 없는 진짜 잡담은 계획 범위 밖에 그대로 둔다")
    void unrelatedCasualChat_staysUnplanned() {
        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.NORMAL, RiskLevel.CLEAR_LOW,
                        InterventionHints.empty()),
                SessionDelta.empty());

        assertThat(plan.responseAct()).isEqualTo(ResponseAct.UNPLANNED);
        assertThat(plan.isContractEnforced()).isFalse();
    }

    @Test
    @DisplayName("왜곡 게이트가 열려 질문이 허용된 턴은 0개 제약을 걸지 않는다")
    void cbtRelevantSessionWithOpenGate_doesNotForceZeroQuestions() {
        SessionDelta sessionDelta = new SessionDelta(
                0, "none", Map.of("catastrophizing", 2), 0, new HashSet<>(), new HashSet<>());
        InterventionHints openHints = new InterventionHints(
                java.util.List.of("socratic_questioning"), java.util.List.of(), "catastrophizing");

        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.NORMAL, RiskLevel.CLEAR_LOW, openHints),
                sessionDelta);

        assertThat(plan.responseAct())
                .as("힌트가 이미 질문을 허용하므로 STEP 4 게이트가 관여할 필요가 없다")
                .isEqualTo(ResponseAct.UNPLANNED);
    }

    /**
     * 리뷰 발견(STEP 4 자체 리뷰) — 왜곡 게이트는 열렸지만(2회 이상) 세션 소크라테스 상한에
     * 도달해 OntologyInterventionFilter가 socratic_questioning만 걸러내고 다른 코드
     * (breathing_exercise)가 남아있는 경우. 힌트가 비어있지 않다고 "질문 허용"으로 착각하면
     * 이슈 #545가 고치려던 상한 무력화가 이 경로로 재발한다.
     */
    @Test
    @DisplayName("세션 소크라테스 상한 도달로 질문 코드만 빠지고 다른 코드가 남아도 질문 0개 계약을 건다")
    void socraticLimitReached_withOtherCodeRemaining_stillGetsZeroQuestionContract() {
        SessionDelta sessionDelta = new SessionDelta(
                2, "none", Map.of("catastrophizing", 2), 0, new HashSet<>(), new HashSet<>());
        InterventionHints hintsWithoutSocratic = new InterventionHints(
                java.util.List.of("breathing_exercise"), java.util.List.of(), "catastrophizing");

        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.NORMAL, RiskLevel.CLEAR_LOW,
                        hintsWithoutSocratic),
                sessionDelta);

        assertThat(plan.isContractEnforced())
                .as("socratic_questioning이 힌트에 없으면 다른 코드가 남아있어도 질문은 금지 상태다")
                .isTrue();
        assertThat(plan.maxQuestions()).isZero();
    }

    @Test
    @DisplayName("sessionDelta 없이 호출하는 기존 방식은 이 게이트를 적용하지 않는다")
    void legacyOverloadWithoutSessionDelta_skipsGate() {
        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.NORMAL, RiskLevel.CLEAR_LOW));

        assertThat(plan.responseAct()).isEqualTo(ResponseAct.UNPLANNED);
    }

    @Test
    @DisplayName("보안 SUSPICIOUS 로 가드된 턴은 아직 계획 범위가 아니다")
    void suspiciousGuardedTurnStaysUnplanned() {
        ResponsePlan plan = planner.plan(
                decision(DecisionAction.GENERATE, GenerationMode.GUARDED, RiskLevel.LOW));

        assertThat(plan.responseAct())
                .as("조작 시도에 대한 응답은 정서 코칭 행위와 성격이 다르다 — 별도 계약이 필요하다")
                .isEqualTo(ResponseAct.UNPLANNED);
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
