package com.mio.ai.crisis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부정 답변이 위기 플로우를 잘못 종결시키지 않는지 검증한다 (이슈 #504).
 *
 * <p>{@link CrisisAnswerParserTest} 는 판독 결과만 본다. 그런데 실제로 지켜야 하는 성질은
 * 판독값이 아니라 <b>라우팅</b>이다 — 같은 오판독이 단계에 따라 전혀 다른 결과를 낸다.
 * {@code IMMEDIATE_SUPPORT} 에서 부정을 긍정으로 읽으면
 * {@code COMPLETED} + {@code "지금 바로 그 사람에게 연락하고…"} 가 나가는데,
 * 이건 곁에 아무도 없다고 답한 사용자에게 그 사람에게 연락하라고 말하는 것이다.
 * 올바른 도착지는 {@code HANDOFF} (핫라인 우선 연결)다.
 *
 * <p>그래서 두 단위를 함께 통과시키는 축을 따로 둔다. 파서만 고치고 라우팅을 확인하지 않으면
 * 다음 사람이 마커를 다시 넣었을 때 이 성질이 조용히 깨진다.
 */
class CrisisNegationRoutingTest {

    private final CrisisAnswerParser parser = new CrisisAnswerParser();
    private final CrisisFlowStateMachine machine = new CrisisFlowStateMachine();

    /** {@code IMMEDIATE_SUPPORT} 질문: "지금 곁에 있거나 바로 연락할 수 있는 믿을 만한 사람이 있나요?" */
    private static final String[] NO_SUPPORT_ANSWERS = {
            "있지 않아요",
            "믿을 만한 사람이 있지 않아요",
            "그런 사람은 있지 않습니다",
            "딱히 있지 않네요",
            "지금은 있지 않아요",
            "연락할 사람이 있지가 않아요",
            "있지는 않습니다",
            "있진 않아요",
            "없어요",
            "아니요",
            "아무도 없어요"
    };

    @Test
    @DisplayName("지원 인물이 없다는 답변은 어떤 표현이든 COMPLETED로 종결되지 않는다")
    void absentSupportNeverCompletesTheFlow() {
        for (String answer : NO_SUPPORT_ANSWERS) {
            CrisisFlowTransition transition =
                    machine.next(CrisisFlowStage.IMMEDIATE_SUPPORT, parser.parse(answer));

            assertThat(transition.status())
                    .as("'%s' 로 플로우가 종결되면 곁에 아무도 없는 사용자에게 "
                            + "'그 사람에게 연락하라'고 안내하게 된다", answer)
                    .isNotEqualTo(CrisisFlowStatus.COMPLETED);
            assertThat(transition.nextStage())
                    .as("'%s' 는 핫라인 우선 연결(HANDOFF)로 닫혀야 한다", answer)
                    .isEqualTo(CrisisFlowStage.HANDOFF);
        }
    }

    @Test
    @DisplayName("현재성·계획·수단 단계의 부정 답변도 위험을 지우지 않는다")
    void negationInEarlyStagesDoesNotEscalateIntoDetailQuestions() {
        // 부정을 긍정으로 읽으면 실제로는 계획·수단이 없는 사용자에게 수단 질문이 이어진다.
        // 확정하지 못하면 handoff 로 닫는 것이 이 상태기계의 기존 규율이다.
        assertThat(machine.next(CrisisFlowStage.CURRENT_INTENT, parser.parse("죽고 싶은 생각이 있지 않아요"))
                .nextStage())
                .as("자·타해 생각이 없다는 답변이 계획 질문으로 이어지면 안 된다")
                .isNotEqualTo(CrisisFlowStage.PLAN);
        assertThat(machine.next(CrisisFlowStage.PLAN, parser.parse("계획은 세우지 않았어요"))
                .nextStage())
                .as("계획이 없다는 답변이 수단 질문으로 이어지면 안 된다")
                .isNotEqualTo(CrisisFlowStage.MEANS);
        assertThat(machine.next(CrisisFlowStage.MEANS, parser.parse("정해두지 않았어요"))
                .nextStage())
                .as("수단을 정하지 않았다는 답변이 접근성 질문으로 이어지면 안 된다")
                .isNotEqualTo(CrisisFlowStage.MEANS_ACCESS);
    }

    @Test
    @DisplayName("긍정 답변의 triage 경로는 그대로 유지된다")
    void affirmativePathIsUnchanged() {
        assertThat(machine.next(CrisisFlowStage.CURRENT_INTENT, parser.parse("네 있어요")).nextStage())
                .isEqualTo(CrisisFlowStage.PLAN);
        assertThat(machine.next(CrisisFlowStage.PLAN, parser.parse("이미 정했어요")).nextStage())
                .isEqualTo(CrisisFlowStage.MEANS);
        assertThat(machine.next(CrisisFlowStage.MEANS, parser.parse("도구는 벌써 구했어")).nextStage())
                .isEqualTo(CrisisFlowStage.MEANS_ACCESS);
        assertThat(machine.next(CrisisFlowStage.IMMEDIATE_SUPPORT, parser.parse("한 명 있어요")).status())
                .as("지원 인물이 있다고 답하면 종결까지 가야 한다 — 과잉 handoff 는 경험 퇴행이다")
                .isEqualTo(CrisisFlowStatus.COMPLETED);
    }
}
