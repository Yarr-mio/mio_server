package com.mio.ai.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버가 문장 일부를 먼저 전달한 계획의 상한 계산 (P0-4, 로드맵 §5.6).
 *
 * <p>운영 경로는 {@code 4 - 1 = 3} 한 가지만 실행한다. 상한이 1인 계획, 예약 0·음수, 계획
 * 범위 밖 턴은 통합 테스트로는 절대 실행되지 않으므로 여기서 고정한다 — 상한이 0이 되면
 * 계약 검사가 모든 응답을 위반으로 세고, 그 위반은 Judge 승격으로 이어진다.
 */
class ResponsePlanTest {

    private ResponsePlan constrained(int maxSentences) {
        return new ResponsePlan(ResponseAct.EMOTION_CHECK, GenerationFreedom.CONSTRAINED,
                1, maxSentences, ResponsePlan.BASE_FORBIDDEN);
    }

    @Test
    @DisplayName("전달한 문장 수만큼 상한을 줄인다")
    void reservesTheDeliveredSentence() {
        assertThat(constrained(4).reservingSentences(1).maxSentences()).isEqualTo(3);
    }

    @Test
    @DisplayName("상한이 1인 계획도 0으로 내려가지 않는다")
    void neverFallsBelowOneSentence() {
        ResponsePlan reserved = constrained(1).reservingSentences(1);

        // 0이면 모델이 무엇을 쓰든 계약 위반이 된다 — 지킬 수 없는 계약은 계약이 아니다.
        assertThat(reserved.maxSentences()).isEqualTo(1);
    }

    @Test
    @DisplayName("예약이 0이거나 음수면 계획을 바꾸지 않는다")
    void nonPositiveReservationIsANoOp() {
        ResponsePlan plan = constrained(4);

        assertThat(plan.reservingSentences(0)).isSameAs(plan);
        assertThat(plan.reservingSentences(-2)).isSameAs(plan);
    }

    @Test
    @DisplayName("계획 범위 밖 턴은 상한을 만들지 않는다")
    void unplannedTurnKeepsItsUnboundedLimit() {
        ResponsePlan unplanned = ResponsePlan.unplanned();

        // 상한이 없다는 것과 상한이 큰 것은 다르다. 여기서 1을 빼면 계획하지 않은 턴에
        // 계약이 생긴 것처럼 보이고, 준수율 통계가 그 턴까지 세기 시작한다.
        assertThat(unplanned.reservingSentences(1)).isSameAs(unplanned);
        assertThat(unplanned.reservingSentences(1).maxSentences()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("줄인 계획은 행위·자유도·질문 수·금지 요소를 그대로 유지한다")
    void reservationOnlyChangesTheSentenceLimit() {
        ResponsePlan original = constrained(4);
        ResponsePlan reserved = original.reservingSentences(1);

        assertThat(reserved.responseAct()).isEqualTo(original.responseAct());
        assertThat(reserved.generationFreedom()).isEqualTo(original.generationFreedom());
        assertThat(reserved.maxQuestions()).isEqualTo(original.maxQuestions());
        assertThat(reserved.forbiddenElements()).isEqualTo(original.forbiddenElements());
    }
}
