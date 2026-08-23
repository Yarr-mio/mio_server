package com.mio.ai.crisis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
     * 부정 보조용언 {@code 않다} 를 이끄는 연결어미 {@code -지} 의 커버리지 축 (이슈 #504).
     *
     * <p>이 케이스들은 마커 목록에서 복사한 것이 아니라 <b>목록이 놓치는 형태</b>다.
     * 한국어 부정은 어미에 후치하므로({@code -지 않다}) 위험 어휘와 부정 표지의 순서·거리가
     * 판별 정보를 가진다. {@code 있지} 를 종결형 긍정 마커로 읽으면
     * {@code "있지 않아요"}(= 없다)가 YES 로 확정되고, {@code IMMEDIATE_SUPPORT} 단계에서
     * <b>곁에 아무도 없다고 답한 사용자가 COMPLETED 로 종결</b>된다.
     *
     * <p>확정 대신 UNKNOWN 인 이유: 이중부정({@code "없지 않아요"} = 있다)까지 정확히 풀려면
     * 선행 서술어를 읽어야 하는데 그 판단은 임상·언어 검토 대상이다. UNKNOWN 은 handoff 로
     * fail-closed 되고, {@code IMMEDIATE_SUPPORT} 에서는 NO 와 도착지가 같다.
     */
    @Test
    @DisplayName("부정 보조용언 '-지 않다'가 붙은 답변을 긍정으로 확정하지 않는다")
    void negationAuxiliaryIsNeverReadAsAffirmative() {
        for (String answer : new String[]{
                "있지 않아요",
                "믿을 만한 사람이 있지 않아요",
                "그런 사람은 있지 않습니다",
                "딱히 있지 않네요",
                "지금은 있지 않아요",
                "연락할 사람이 있지가 않아요",
                "있지는 않습니다",
                "있진 않아요",
                "계획은 세우지 않았어요",
                "정해두지 않았어요",
                "떠오르지 않아요"}) {
            assertThat(parser.parse(answer))
                    .as("'%s' 는 부정이다 — 긍정으로 확정하면 위기 플로우가 잘못 종결된다", answer)
                    .isNotEqualTo(CrisisAnswer.YES);
        }
    }

    /**
     * 이중부정은 의미상 긍정이지만 선행 서술어를 읽어야 풀린다. 현재는 확정하지 않는 쪽이
     * 안전하다 — {@code IMMEDIATE_SUPPORT} 에서 UNKNOWN 은 handoff 로 닫히고,
     * 지원 인물이 있는 사용자를 핫라인으로 한 번 더 안내하는 것은 되돌릴 수 있는 비용이다.
     */
    @Test
    @DisplayName("이중부정은 긍정으로 확정하지 않는다")
    void doubleNegationIsNotResolvedToAffirmative() {
        assertThat(parser.parse("연락할 사람이 없지 않아요")).isNotEqualTo(CrisisAnswer.YES);
    }

    /** 부정 표지 검사가 정상 긍정·부정 답변을 삼키지 않아야 한다 (회귀 방지). */
    @Test
    @DisplayName("부정 표지가 없는 평범한 답변은 그대로 확정한다")
    void plainAnswersAreUnaffectedByNegationHandling() {
        assertThat(parser.parse("네 있어요")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("한 명 있어요")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("이미 정했어요")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("도구는 벌써 구했어")).isEqualTo(CrisisAnswer.YES);
        assertThat(parser.parse("아무도 없어요")).isEqualTo(CrisisAnswer.NO);
        assertThat(parser.parse("아니에요")).isEqualTo(CrisisAnswer.NO);
    }
}
