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
}
