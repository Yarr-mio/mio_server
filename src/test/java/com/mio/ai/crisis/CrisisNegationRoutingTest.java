package com.mio.ai.crisis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    private CrisisFlowTransition route(CrisisFlowStage stage, String answer) {
        return machine.next(stage, parser.parse(answer));
    }

    /** {@code IMMEDIATE_SUPPORT} 질문: "지금 곁에 있거나 바로 연락할 수 있는 믿을 만한 사람이 있나요?" */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            // 어간 + 부정 보조용언
            "있지 않아요",
            "믿을 만한 사람이 있지 않아요",
            "그런 사람은 있지 않습니다",
            "딱히 있지 않네요",
            "지금은 있지 않아요",
            "연락할 사람이 있지가 않아요",
            "있지는 않습니다",
            "있진 않아요",
            "있지를 않아요",
            // 어간 + 양보 연결어미. 부정 증거가 사라져 뒤따르는 긍정 마커에 뒤집히던 형태다.
            "믿을 만한 사람은 없지만 그래도 괜찮아요",
            "그런 사람 없지만 준비는 됐어요",
            "아무도 없지만 맞아요 견딜 수 있어요",
            "있지만 연락은 안 해요",
            // 경어 (이슈 #516). 어휘 추가로 `계세` 가 긍정 마커가 됐으므로, 전치 부정이
            // 함께 매칭되지 않으면 이 축이 통째로 COMPLETED 로 역전된다.
            "안 계세요",
            "안 계십니다",
            "아무도 안 계세요",
            "연락드릴 분이 안 계세요",
            "부모님도 안 계세요",
            "계시지 않아요",
            "계시지 못해요",
            "없으세요",
            "없으십니다",
            "곁에 계신 분이 없어요",
            // 표준 부정
            "없어요",
            "아니요",
            "아무도 없어요"})
    @DisplayName("지원 인물이 없다는 답변은 어떤 표현이든 COMPLETED로 종결되지 않는다")
    void absentSupportNeverCompletesTheFlow(String answer) {
        CrisisFlowTransition transition = route(CrisisFlowStage.IMMEDIATE_SUPPORT, answer);

        assertThat(transition.status())
                .as("'%s' 로 플로우가 종결되면 곁에 아무도 없는 사용자에게 "
                        + "'그 사람에게 연락하라'고 안내하게 된다", answer)
                .isNotEqualTo(CrisisFlowStatus.COMPLETED);
        assertThat(transition.nextStage())
                .as("'%s' 는 핫라인 우선 연결(HANDOFF)로 닫혀야 한다", answer)
                .isEqualTo(CrisisFlowStage.HANDOFF);
    }

    /**
     * 경어 긍정이 실제로 종결에 도달하는지 (이슈 #516).
     *
     * <p>경어 어휘 추가의 이득은 전부 이 방향이다 — 부정 쪽은 NO 와 UNKNOWN 의 도착지가 같아서
     * ({@code handoff()}) 변화가 없고, 긍정 쪽만 {@code HANDOFF} 에서 {@code COMPLETED} 로
     * 옮겨온다. 곁에 사람이 있다고 답한 사용자가 그 답을 읽히지 못해 핫라인으로 닫히던 경로다.
     *
     * <p>{@link CrisisAnswerParserTest} 는 파서 반환값을 고정하고, 이 테스트는 그 값이 상태기계를
     * 지나 도착하는 곳을 고정한다 — 안전 속성은 반환값이 아니라 도착지에 있다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "계세요",
            "계십니다",
            "옆에 계세요",
            "어머니가 계세요",
            "언니가 계셔서 괜찮아요",
            "있으세요",
            "있으십니다"})
    @DisplayName("경어 긍정은 핫라인이 아니라 종결로 도착한다")
    void honorificAffirmativeReachesCompletion(String answer) {
        CrisisFlowTransition transition = route(CrisisFlowStage.IMMEDIATE_SUPPORT, answer);

        assertThat(transition.status())
                .as("'%s' 가 HANDOFF 로 닫히면 곁에 사람이 있다고 답한 사용자의 답이 "
                        + "읽히지 않은 것이다", answer)
                .isEqualTo(CrisisFlowStatus.COMPLETED);
    }

    @Test
    @DisplayName("현재성·계획·수단 단계의 부정 답변은 상세 질문으로 이어지지 않는다")
    void negationInEarlyStagesDoesNotEscalateIntoDetailQuestions() {
        assertThat(route(CrisisFlowStage.CURRENT_INTENT, "그런 생각이 있지 않아요").nextStage())
                .as("자·타해 생각이 없다는 답변이 계획 질문으로 이어지면 안 된다")
                .isEqualTo(CrisisFlowStage.HANDOFF);
        assertThat(route(CrisisFlowStage.PLAN, "계획이 있지 않아요").nextStage())
                .as("계획이 없다는 답변이 수단 질문으로 이어지면 안 된다")
                .isEqualTo(CrisisFlowStage.HANDOFF);
        assertThat(route(CrisisFlowStage.MEANS, "정해둔 게 있지 않아요").nextStage())
                .as("수단을 정하지 않았다는 답변이 접근성 질문으로 이어지면 안 된다")
                .isEqualTo(CrisisFlowStage.HANDOFF);
    }

    /**
     * {@code MEANS_ACCESS} 는 YES·NO 도착지가 같다({@code IMMEDIATE_SUPPORT}). UNKNOWN 만
     * {@code HANDOFF} 다. 즉 원래 버그는 이 단계에서 아무 영향이 없었지만, <b>수정은 영향을
     * 만든다</b> — 수단 접근성에 답한 사용자가 지원 인물 확인 질문을 받지 못하고 종결된다.
     *
     * <p>핫라인은 나가므로 안전 방향 역전은 아니지만 손실이 0인 것도 아니다. 이 축을 고정해
     * 두는 이유는 그 손실이 의도된 것임을 다음 사람이 알 수 있게 하기 위해서다.
     */
    @Test
    @DisplayName("수단 접근성 단계에서는 UNKNOWN이 지원 인물 확인을 건너뛴다 — 의도된 손실")
    void meansAccessLosesSupportCheckOnUnknown() {
        assertThat(machine.next(CrisisFlowStage.MEANS_ACCESS, CrisisAnswer.YES).nextStage())
                .isEqualTo(CrisisFlowStage.IMMEDIATE_SUPPORT);
        assertThat(machine.next(CrisisFlowStage.MEANS_ACCESS, CrisisAnswer.NO).nextStage())
                .as("이 단계는 YES·NO 도착지가 같다 — 원래 버그의 영향이 없던 자리다")
                .isEqualTo(CrisisFlowStage.IMMEDIATE_SUPPORT);
        assertThat(route(CrisisFlowStage.MEANS_ACCESS, "가지고 있지 않아요").nextStage())
                .as("수정으로 이 단계는 handoff 로 닫힌다 — 지원 인물 확인을 잃는다")
                .isEqualTo(CrisisFlowStage.HANDOFF);
    }

    @Test
    @DisplayName("긍정 답변의 triage 경로는 그대로 유지된다")
    void affirmativePathIsUnchanged() {
        assertThat(route(CrisisFlowStage.CURRENT_INTENT, "네 있어요").nextStage())
                .isEqualTo(CrisisFlowStage.PLAN);
        assertThat(route(CrisisFlowStage.PLAN, "이미 정했어요").nextStage())
                .isEqualTo(CrisisFlowStage.MEANS);
        assertThat(route(CrisisFlowStage.MEANS, "도구는 벌써 구했어").nextStage())
                .isEqualTo(CrisisFlowStage.MEANS_ACCESS);
        assertThat(route(CrisisFlowStage.IMMEDIATE_SUPPORT, "한 명 있어요").status())
                .as("지원 인물이 있다고 답하면 종결까지 가야 한다 — 과잉 handoff 는 경험 퇴행이다")
                .isEqualTo(CrisisFlowStatus.COMPLETED);
        assertThat(route(CrisisFlowStage.IMMEDIATE_SUPPORT, "있지").status())
                .as("종결형 '있지' 는 평범한 구어 긍정이다")
                .isEqualTo(CrisisFlowStatus.COMPLETED);
    }

    /**
     * 다른 서술어가 부정되고 준비 완료 진술이 따라오는 문장. 차단을 존재 서술어 어간으로
     * 한정했기 때문에 이 진술이 살아남는다 — 명시적 준비 완료를 {@code ambiguous_answer} 로
     * 지우면 그 사용자의 위험 기록 자체가 사라진다.
     */
    @Test
    @DisplayName("다른 서술어의 부정은 수단 확보 진술을 지우지 않는다")
    void negationOfOtherPredicatesKeepsMeansSecuredAnswer() {
        assertThat(route(CrisisFlowStage.MEANS, "계획은 세우지 않았지만 도구는 구했어요").nextStage())
                .isEqualTo(CrisisFlowStage.MEANS_ACCESS);
        assertThat(route(CrisisFlowStage.MEANS, "아직 정하지 않았지만 준비는 했어요").nextStage())
                .isEqualTo(CrisisFlowStage.MEANS_ACCESS);
    }
}
