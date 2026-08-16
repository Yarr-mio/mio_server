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
}
