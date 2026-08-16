package com.mio.ai.delivery;

import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.moderation.ModerationStatus;
import com.mio.ai.plan.GenerationFreedom;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.plan.ResponseContractValidator;
import com.mio.ai.plan.ResponsePlanner;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버가 먼저 보내는 검토된 첫 문장의 적용 조건과 문구 제약 (P0-4, 로드맵 §5.6).
 *
 * <p>이 클래스가 잘못되면 <b>사용자가 보는 첫 문장이 잘못된 턴에 나간다.</b> 위기·보안 거절
 * 처럼 서버 고정 문구가 이미 나가는 경로에 문장이 하나 더 얹히는 것이 가장 나쁜 실패다.
 */
class SafePrefixCatalogTest {

    private final SafePrefixCatalog catalog = new SafePrefixCatalog();
    private final ResponsePlanner planner = new ResponsePlanner();
    private final OutputPreFilter outputPreFilter = new OutputPreFilter();
    private final ResponseContractValidator contractValidator = new ResponseContractValidator();

    private PolicyDecision decision(DecisionAction action, GenerationMode mode,
                                    DeliveryMode delivery, RiskLevel risk,
                                    SecurityLevel security, JudgeStatus judgeStatus,
                                    ModerationStatus moderationStatus) {
        PolicyDecision base = new PolicyDecision(
                "pd_prefix_test", action, mode, delivery, security,
                action == DecisionAction.GENERATE, true, true,
                InterventionHints.empty(), "test", risk, null, judgeStatus, moderationStatus,
                ResponsePlan.unplanned());
        return base.withResponsePlan(planner.plan(base));
    }

    private PolicyDecision plannedTurn(GenerationMode mode, RiskLevel risk) {
        return decision(DecisionAction.GENERATE, mode, DeliveryMode.CAUTIOUS_SPECULATIVE, risk,
                SecurityLevel.CLEAN, JudgeStatus.SUCCEEDED, ModerationStatus.RESOLVED);
    }

    @Test
    @DisplayName("MEDIUM 감정 확인 턴은 검토된 첫 문장을 받는다")
    void emotionCheckTurnGetsAPrefix() {
        PolicyDecision turn = plannedTurn(GenerationMode.SUPPORTIVE, RiskLevel.MEDIUM);

        assertThat(turn.responsePlan().responseAct()).isEqualTo(ResponseAct.EMOTION_CHECK);
        assertThat(catalog.select(turn)).contains("지금 마음이 많이 무거우실 것 같아요.");
    }

    @Test
    @DisplayName("룰이 승격한 맥락 확인 턴도 검토된 첫 문장을 받는다")
    void clarifyContextTurnGetsAPrefix() {
        PolicyDecision turn = plannedTurn(GenerationMode.SUPPORTIVE, RiskLevel.LOW);

        assertThat(turn.responsePlan().responseAct()).isEqualTo(ResponseAct.CLARIFY_CONTEXT);
        assertThat(catalog.select(turn)).isPresent();
    }

    @Test
    @DisplayName("위기 고정 플로우와 보안 거절에는 아무것도 덧붙이지 않는다")
    void fixedServerCopyPathsNeverGetAPrefix() {
        PolicyDecision crisis = decision(DecisionAction.CRISIS_FLOW, GenerationMode.CRISIS,
                DeliveryMode.CRISIS_FLOW, RiskLevel.HARD_CRISIS, SecurityLevel.CLEAN,
                JudgeStatus.SUCCEEDED, ModerationStatus.RESOLVED);
        PolicyDecision refusal = decision(DecisionAction.SECURITY_REFUSAL, GenerationMode.CRISIS,
                DeliveryMode.SECURITY_REFUSAL, RiskLevel.ATTACK, SecurityLevel.ATTACK,
                JudgeStatus.SKIPPED, ModerationStatus.RESOLVED);
        PolicyDecision fallback = decision(DecisionAction.FALLBACK, GenerationMode.CRISIS,
                DeliveryMode.BUFFER, RiskLevel.MEDIUM, SecurityLevel.CLEAN,
                JudgeStatus.SUCCEEDED, ModerationStatus.RESOLVED);

        assertThat(catalog.select(crisis)).isEmpty();
        assertThat(catalog.select(refusal)).isEmpty();
        assertThat(catalog.select(fallback)).isEmpty();
    }

    @Test
    @DisplayName("HIGH 위험 턴에는 붙이지 않는다 — 위기 승격 시 화면에 남는다")
    void highRiskTurnsNeverGetAPrefix() {
        PolicyDecision high = decision(DecisionAction.GENERATE, GenerationMode.GUARDED,
                DeliveryMode.BUFFER, RiskLevel.HIGH, SecurityLevel.CLEAN,
                JudgeStatus.SUCCEEDED, ModerationStatus.RESOLVED);

        assertThat(high.responsePlan().responseAct()).isEqualTo(ResponseAct.EMPATHIC_REFLECTION);
        assertThat(catalog.select(high)).isEmpty();
    }

    @Test
    @DisplayName("판정을 받지 못한 보수 경로에는 붙이지 않는다")
    void conservativePathsWithoutAVerdictNeverGetAPrefix() {
        PolicyDecision judgeFailed = decision(DecisionAction.GENERATE, GenerationMode.GUARDED,
                DeliveryMode.BUFFER, RiskLevel.MEDIUM, SecurityLevel.CLEAN,
                JudgeStatus.FAILED, ModerationStatus.RESOLVED);
        PolicyDecision moderationUnresolved = decision(DecisionAction.GENERATE,
                GenerationMode.SUPPORTIVE, DeliveryMode.CAUTIOUS_SPECULATIVE, RiskLevel.MEDIUM,
                SecurityLevel.CLEAN, JudgeStatus.SUCCEEDED, ModerationStatus.UNRESOLVED);

        assertThat(catalog.select(judgeFailed)).isEmpty();
        assertThat(catalog.select(moderationUnresolved)).isEmpty();
    }

    @Test
    @DisplayName("조작 의심 턴과 계약 없는 턴에는 붙이지 않는다")
    void suspiciousAndUnplannedTurnsNeverGetAPrefix() {
        PolicyDecision suspicious = decision(DecisionAction.GENERATE, GenerationMode.GUARDED,
                DeliveryMode.CAUTIOUS_SPECULATIVE, RiskLevel.LOW, SecurityLevel.SUSPICIOUS,
                JudgeStatus.SUCCEEDED, ModerationStatus.RESOLVED);
        PolicyDecision unplanned = decision(DecisionAction.GENERATE, GenerationMode.NORMAL,
                DeliveryMode.CAUTIOUS_SPECULATIVE, RiskLevel.CLEAR_LOW, SecurityLevel.CLEAN,
                JudgeStatus.SUCCEEDED, ModerationStatus.RESOLVED);

        assertThat(catalog.select(suspicious)).isEmpty();
        assertThat(unplanned.responsePlan().responseAct()).isEqualTo(ResponseAct.UNPLANNED);
        assertThat(catalog.select(unplanned)).isEmpty();
    }

    @Test
    @DisplayName("즉시 스트리밍 턴에는 메울 대기가 없으므로 붙이지 않는다")
    void immediateStreamingTurnsNeverGetAPrefix() {
        PolicyDecision speculative = decision(DecisionAction.GENERATE, GenerationMode.SUPPORTIVE,
                DeliveryMode.SPECULATIVE, RiskLevel.LOW, SecurityLevel.CLEAN,
                JudgeStatus.SUCCEEDED, ModerationStatus.RESOLVED);

        assertThat(catalog.select(speculative)).isEmpty();
    }

    @Test
    @DisplayName("선택이 실패해도 턴은 실패하지 않는다")
    void selectionFailureFallsBackToNoPrefix() {
        assertThat(catalog.select(null)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("검토된 문구는 질문 없는 한 문장이고 금지 표현을 포함하지 않는다")
    void reviewedCopyObeysTheCopyConstraints() {
        assertThat(catalog.reviewedCopy()).isNotEmpty();
        catalog.reviewedCopy().forEach((act, copy) -> {
            assertThat(copy).as("%s 문구는 질문을 포함하지 않는다 — 계약이 허용한 질문은 모델 몫이다", act)
                    .doesNotContain("?").doesNotContain("？");
            assertThat(copy.split("[.!?。！？]")).as("%s 문구는 한 문장이다", act)
                    .hasSize(1);
            // 서버 문구라도 출력 사전 필터 기준을 통과해야 한다. 위기 맥락에서도 가벼운
            // 응답으로 분류되면 안 된다 — 그 문구가 위기 턴의 첫 화면이 될 수 있다.
            assertThat(outputPreFilter.checkWithCrisisContext(copy, true).passed())
                    .as("%s 문구가 출력 사전 필터에 걸린다", act)
                    .isTrue();
            // 진단·단정·결과 보장 금지는 모델 출력에만 적용되는 규칙이 아니다. 서버가 쓴
            // 문장이 같은 기준을 어기면 검토됐다는 말이 의미를 잃는다.
            assertThat(contractValidator.validate(
                    new ResponsePlan(act, GenerationFreedom.CONSTRAINED, 0, 1,
                            ResponsePlan.BASE_FORBIDDEN),
                    copy).violations())
                    .as("%s 문구가 계약 금지 표현에 걸린다", act)
                    .isEmpty();
        });
    }
}
