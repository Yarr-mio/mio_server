package com.mio.ai.crisis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrisisFlowStateMachineTest {

    private final CrisisFlowStateMachine machine = new CrisisFlowStateMachine();

    @Test
    @DisplayName("YES 경로는 현재성 → 계획 → 수단 → 접근성 → 즉시 지원만 허용한다")
    void yesPathUsesOnlyAllowedTransitions() {
        assertTransition(CrisisFlowStage.CURRENT_INTENT, CrisisAnswer.YES,
                CrisisFlowStage.PLAN, CrisisFlowStatus.ACTIVE);
        assertTransition(CrisisFlowStage.PLAN, CrisisAnswer.YES,
                CrisisFlowStage.MEANS, CrisisFlowStatus.ACTIVE);
        assertTransition(CrisisFlowStage.MEANS, CrisisAnswer.YES,
                CrisisFlowStage.MEANS_ACCESS, CrisisFlowStatus.ACTIVE);
        assertTransition(CrisisFlowStage.MEANS_ACCESS, CrisisAnswer.YES,
                CrisisFlowStage.IMMEDIATE_SUPPORT, CrisisFlowStatus.ACTIVE);
        assertTransition(CrisisFlowStage.IMMEDIATE_SUPPORT, CrisisAnswer.YES,
                CrisisFlowStage.COMPLETED, CrisisFlowStatus.COMPLETED);
    }

    @Test
    @DisplayName("NO는 불필요한 수단 질문을 건너뛰되 즉시 지원 연결은 생략하지 않는다")
    void noSkipsDetailStagesButKeepsSupportConnection() {
        assertTransition(CrisisFlowStage.CURRENT_INTENT, CrisisAnswer.NO,
                CrisisFlowStage.IMMEDIATE_SUPPORT, CrisisFlowStatus.ACTIVE);
        assertTransition(CrisisFlowStage.PLAN, CrisisAnswer.NO,
                CrisisFlowStage.IMMEDIATE_SUPPORT, CrisisFlowStatus.ACTIVE);
        assertTransition(CrisisFlowStage.MEANS, CrisisAnswer.NO,
                CrisisFlowStage.IMMEDIATE_SUPPORT, CrisisFlowStatus.ACTIVE);
        assertTransition(CrisisFlowStage.MEANS_ACCESS, CrisisAnswer.NO,
                CrisisFlowStage.IMMEDIATE_SUPPORT, CrisisFlowStatus.ACTIVE);
        assertTransition(CrisisFlowStage.IMMEDIATE_SUPPORT, CrisisAnswer.NO,
                CrisisFlowStage.HANDOFF, CrisisFlowStatus.HANDOFF);
    }

    @Test
    @DisplayName("모호한 답변은 어느 단계에서도 일반 생성으로 복귀하지 않고 고정 handoff로 종결한다")
    void unknownAlwaysFailsClosed() {
        for (CrisisFlowStage stage : CrisisFlowStage.activeStages()) {
            CrisisFlowTransition transition = machine.next(stage, CrisisAnswer.UNKNOWN);

            assertThat(transition.nextStage()).isEqualTo(CrisisFlowStage.HANDOFF);
            assertThat(transition.status()).isEqualTo(CrisisFlowStatus.HANDOFF);
            assertThat(transition.fixedResponse()).contains("109");
        }
    }

    @Test
    @DisplayName("고정 응답은 수단 설명을 요구하지 않고 국내 지원 자원을 유지한다")
    void fixedResponsesDoNotSolicitMeansDetails() {
        assertThat(machine.initialResponse())
                .contains("예/아니오")
                .contains("109")
                .doesNotContain("구체적으로", "어떤 방법");
        assertThat(machine.next(CrisisFlowStage.PLAN, CrisisAnswer.YES).fixedResponse())
                .contains("구체적인 내용은 말하지 말고")
                .contains("109");
        assertThat(machine.next(CrisisFlowStage.MEANS, CrisisAnswer.YES).fixedResponse())
                .contains("위치나 방법은 설명하지 말고")
                .contains("109");
    }

    @Test
    @DisplayName("terminal 상태를 다시 전이시키지 않는다")
    void terminalStagesCannotAdvance() {
        assertThatThrownBy(() -> machine.next(CrisisFlowStage.COMPLETED, CrisisAnswer.YES))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> machine.next(CrisisFlowStage.HANDOFF, CrisisAnswer.NO))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertTransition(CrisisFlowStage from,
                                  CrisisAnswer answer,
                                  CrisisFlowStage expectedStage,
                                  CrisisFlowStatus expectedStatus) {
        CrisisFlowTransition transition = machine.next(from, answer);
        assertThat(transition.nextStage()).isEqualTo(expectedStage);
        assertThat(transition.status()).isEqualTo(expectedStatus);
        assertThat(transition.fixedResponse()).contains("109");
    }
}
