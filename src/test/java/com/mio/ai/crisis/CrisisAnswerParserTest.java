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
}
