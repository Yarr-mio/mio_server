package com.mio.ai.crisis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CrisisAnswerParserTest {

    private final CrisisAnswerParser parser = new CrisisAnswerParser();

    @Test
    @DisplayName("명시적인 예·아니오만 구조화하고 설명이나 충돌 답변은 UNKNOWN으로 둔다")
    void parsesOnlyUnambiguousBoundedAnswers() {
        assertThat(parser.parse("네")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("응, 있어")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("아니요")).isEqualTo(CrisisAnswer.NO);
        assertThat(parser.parse("없어요")).isEqualTo(CrisisAnswer.NO);

        assertThat(parser.parse("잘 모르겠어요")).isEqualTo(CrisisAnswer.UNKNOWN);
        assertThat(parser.parse("있기도 하고 없기도 해요")).isEqualTo(CrisisAnswer.UNKNOWN);
        assertThat(parser.parse(null)).isEqualTo(CrisisAnswer.UNKNOWN);
    }

    /**
     * 서두 인정어와 본문 내용이 반대 방향인 답변. 서두만 읽으면 "네, 없어요"가 YES 로,
     * "아니, 이미 정했어"가 NO 로 확정된다 — 위기 triage 에서 후자는 실제 위험을 지운다.
     * 양방향 마커가 함께 있으면 확정하지 않고 UNKNOWN 으로 fail-closed 한다.
     */
    @Test
    @DisplayName("긍정·부정 마커가 섞인 답변은 어느 한쪽으로도 확정하지 않는다")
    void mixedMarkersFailClosedToUnknown() {
        assertThat(parser.parse("네, 없어요"))
                .as("서두 '네'가 본문 '없어요'를 이기면 안 된다")
                .isNotEqualTo(CrisisAnswer.YES)
                .isEqualTo(CrisisAnswer.UNKNOWN);
        assertThat(parser.parse("아니, 이미 정했어"))
                .as("서두 '아니'가 준비 완료 진술을 지우면 안 된다")
                .isNotEqualTo(CrisisAnswer.NO)
                .isEqualTo(CrisisAnswer.UNKNOWN);
    }

    @Test
    @DisplayName("문장 어디에 있든 준비·확보 동사와 부정어를 마커로 잡는다")
    void detectsMarkersAnywhereInSentence() {
        assertThat(parser.parse("응, 있어")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("이미 정했어요")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("도구는 벌써 구했어")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("아니요 없어요")).isEqualTo(CrisisAnswer.NO);
        assertThat(parser.parse("그런 건 없어요")).isEqualTo(CrisisAnswer.NO);
    }

    /**
     * "있"/"없" 은 한국어에서 너무 흔한 음절이라, 어간만 보면 답이 아닌 발화까지 확정
     * 답변이 된다. CURRENT_INTENT 의 잘못된 NO 는 자·타해 의도 확인을 건너뛰고 다음
     * 단계로 내려보내므로, 확정하지 못할 바에는 UNKNOWN(→ handoff)이 맞다.
     */
    @Test
    @DisplayName("답변이 아닌 문장의 '있'·'없' 음절은 확정 답변으로 읽지 않는다")
    void bareStemSyllablesDoNotForceAnAnswer() {
        assertThat(parser.parse("있잖아요 그게")).isEqualTo(CrisisAnswer.UNKNOWN);
        assertThat(parser.parse("상관없이 그냥 힘들어요")).isEqualTo(CrisisAnswer.UNKNOWN);
        assertThat(parser.parse("어이없는 하루였어요")).isEqualTo(CrisisAnswer.UNKNOWN);
    }

    @Test
    @DisplayName("실제로 쓰이는 예·아니오 답변은 그대로 확정한다")
    void ordinaryAnswersStillResolve() {
        assertThat(parser.parse("있어요")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("있습니다")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("없어요")).isEqualTo(CrisisAnswer.NO);
        assertThat(parser.parse("아니요")).isEqualTo(CrisisAnswer.NO);
        assertThat(parser.parse("아닙니다")).isEqualTo(CrisisAnswer.NO);
        // 답을 피하는 표현은 확정하지 않는다 — UNKNOWN 은 handoff 로 fail-closed 된다.
        assertThat(parser.parse("몰라요")).isEqualTo(CrisisAnswer.UNKNOWN);
        assertThat(parser.parse("글쎄요")).isEqualTo(CrisisAnswer.UNKNOWN);
    }

    @Test
    @DisplayName("문장 중간의 '아니'는 확정 부정으로 읽지 않는다")
    void midSentenceNegationParticleIsNotAnAnswer() {
        // 서두의 "아니"만 부정으로 인정한다. 문장 중간 사용은 답변이 아니라 정정·부연이 많다.
        assertThat(parser.parse("그건 아니고 그냥 지쳤어요")).isEqualTo(CrisisAnswer.UNKNOWN);
    }

    /**
     * 존재 서술어 어간 + {@code -지} 계열 어미의 커버리지 축 (이슈 #504).
     *
     * <p>이 케이스들은 마커 목록에서 복사한 것이 아니라 <b>목록이 놓치는 형태</b>다.
     * 한국어 부정은 어미에 후치하므로({@code -지 않다}) 어간만 읽으면 {@code "있지 않아요"}
     * (= 없다)가 긍정으로 확정되고, {@code IMMEDIATE_SUPPORT} 단계에서 곁에 아무도 없다고 답한
     * 사용자가 {@code COMPLETED} 로 종결된다.
     *
     * <p>단정을 {@code UNKNOWN} 으로 고정한다. {@code isNotEqualTo(YES)} 로 두면 구현이 실수로
     * {@code NO} 를 반환해도 통과하는데, {@code NO} 는 이중부정({@code "없지 않아요"} = 있다)을
     * 잘못 확정하는 값이다 — 이 클래스가 피하려는 바로 그 오판독이다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "있지 않아요",
            "믿을 만한 사람이 있지 않아요",
            "그런 사람은 있지 않습니다",
            "딱히 있지 않네요",
            "지금은 있지 않아요",
            "연락할 사람이 있지가 않아요",
            "있지는 않습니다",
            "있진 않아요",
            "있지도 않아요",
            "있질 않아요",
            "있지를 않아요",
            "있지 아니합니다",
            "없지 않아요",
            "없지는 않습니다"})
    @DisplayName("존재 서술어에 붙은 '-지 않다'는 어느 쪽으로도 확정하지 않는다")
    void negatedExistenceIsNeverResolved(String answer) {
        assertThat(parser.parse(answer))
                .as("'%s' 를 확정하면 위기 플로우가 잘못 라우팅된다", answer)
                .isEqualTo(CrisisAnswer.UNKNOWN);
    }

    /**
     * 오타·조사 삽입으로 차단을 비껴가는 형태 (이슈 #512).
     *
     * <p>{@code 않}→{@code 안} 은 한국어에서 가장 흔한 표기 오류 중 하나이고,
     * {@code 있지도 아니해요} 처럼 조사가 끼면 {@code 있지아니} 연속 매칭이 깨진다.
     * 그러면 {@code 있지} 가 긍정 마커로 걸려 {@code IMMEDIATE_SUPPORT} 에서
     * {@code COMPLETED} 로 종결된다 — 곁에 아무도 없다고 답한 사용자다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "옆에 아무도 있지 안아요",
            "있지는 안아요",
            "있지 안습니다",
            "있지도 아니해요",
            "있지가 아니라",
            "없지 안아요",
            "있지를 안아요"})
    @DisplayName("'않→안' 오타와 조사 삽입도 확정하지 않는다")
    void typoAndParticleVariantsAreNotResolved(String answer) {
        assertThat(parser.parse(answer))
                .as("'%s' 는 부정이다 — 오타 때문에 긍정으로 확정되면 위기 플로우가 잘못 닫힌다", answer)
                .isEqualTo(CrisisAnswer.UNKNOWN);
    }

    /**
     * 양보 연결어미 {@code -지만} 도 같은 자리다. 부정 보조용언은 아니지만 결과가 같다 —
     * {@code "없지만 …"} 은 뜻이 부정인데 뒤에 긍정 마커가 오면 YES 로 뒤집히고,
     * {@code "있지만 연락은 안 해요"} 는 뜻이 부정인데 {@code 있지} 때문에 YES 가 된다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "믿을 만한 사람은 없지만 그래도 괜찮아요",
            "그런 사람 없지만 준비는 됐어요",
            "아무도 없지만 맞아요 견딜 수 있어요",
            "있지만 연락은 안 해요",
            "있지만 지금은 어려워요"})
    @DisplayName("존재 서술어에 붙은 '-지만'도 확정하지 않는다")
    void concessiveExistenceIsNeverResolved(String answer) {
        assertThat(parser.parse(answer)).isEqualTo(CrisisAnswer.UNKNOWN);
    }

    /**
     * 차단은 <b>존재 서술어 어간에 붙은 형태만</b> 본다. {@code -지 않} 전체를 차단하면
     * 다른 서술어가 부정되고 준비 완료 진술이 따라오는 문장까지 삼켜, 이 클래스가 명시한
     * 규율(준비 행동 동사는 부정 서두 뒤에 나와도 긍정 증거다)이 깨진다.
     *
     * <p>위기 triage 에서 이 손실은 안전 문제가 아니라 <b>기록</b> 문제다 —
     * {@code handoff} 는 핫라인을 주지만 {@code "도구는 구했어요"} 라고 명확히 말한 턴이
     * {@code ambiguous_answer} 로 영구 기록되고 후속 단계 평가 기회가 사라진다.
     */
    @Test
    @DisplayName("다른 서술어의 부정은 준비 완료 진술을 지우지 않는다")
    void negationOfOtherPredicatesDoesNotEraseReadinessStatement() {
        assertThat(parser.parse("계획은 세우지 않았지만 도구는 구했어요"))
                .isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("아직 정하지 않았지만 준비는 했어요"))
                .isEqualTo(CrisisAnswer.YES);
    }

    /** 평범한 구어 긍정. 어간+{@code -지} 를 마커에서 빼면 이것들이 UNKNOWN 으로 떨어진다. */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"있지", "있지요", "그런 사람 있지", "한 명 있지"})
    @DisplayName("종결형 '있지'는 평범한 긍정으로 확정한다")
    void bareExistenceEndingStillResolvesToYes(String answer) {
        assertThat(parser.parse(answer)).isEqualTo(CrisisAnswer.YES);
    }

    /** 부정 표지 검사가 정상 긍정·부정 답변을 삼키지 않아야 한다 (회귀 방지). */
    @Test
    @DisplayName("차단 형태가 없는 평범한 답변은 그대로 확정한다")
    void plainAnswersAreUnaffectedByBlockers() {
        assertThat(parser.parse("네 있어요")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("한 명 있어요")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("이미 정했어요")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("도구는 벌써 구했어")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("아무도 없어요")).isEqualTo(CrisisAnswer.NO);
        assertThat(parser.parse("아니에요")).isEqualTo(CrisisAnswer.NO);
    }

    /**
     * 차단 목록 밖의 부정 형태 — 현재 동작을 고정한다. 전부 마커가 없어 UNKNOWN 이지만,
     * 그건 목록이 덮어서가 아니라 <b>매칭할 마커가 우연히 없어서</b>다. 마커 목록이 넓어지면
     * 이 축이 먼저 깨지므로 회귀 감지 지점으로 남겨 둔다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "계시지 않아요",
            "안 계세요",
            "없으십니다",
            "그런 생각 안 들어요",
            "떠오르지 않아요",
            "계획은 세우지 않았어요",
            "정해두지 않았어요"})
    @DisplayName("목록 밖 부정 형태는 현재 확정되지 않는다 — 회귀 감지 지점")
    void outOfListNegationsCurrentlyResolveToUnknown(String answer) {
        assertThat(parser.parse(answer)).isEqualTo(CrisisAnswer.UNKNOWN);
    }
}
