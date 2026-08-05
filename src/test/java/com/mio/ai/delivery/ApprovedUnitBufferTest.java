package com.mio.ai.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 이슈 #306 — 스트림을 검사 가능한 단위로 자른다. */
class ApprovedUnitBufferTest {

    @Test
    @DisplayName("문장 종결 부호에서 단위를 자른다")
    void cutsAtSentenceBoundary() {
        ApprovedUnitBuffer buffer = new ApprovedUnitBuffer();

        assertThat(buffer.offer("괜찮아요")).isEmpty();
        assertThat(buffer.offer(". 지금은요?")).containsExactly("괜찮아요. ", "지금은요?");
    }

    @Test
    @DisplayName("단위를 이어 붙이면 입력과 정확히 같다")
    void unitsReconstructTheInputExactly() {
        ApprovedUnitBuffer buffer = new ApprovedUnitBuffer();
        List<String> chunks = List.of("많이 ", "힘드셨겠어요! ", "그럴 때가 ", "있죠... ", "지금은 어떠세요?", " 남은 조각");

        List<String> units = new ArrayList<>();
        chunks.forEach(chunk -> units.addAll(buffer.offer(chunk)));
        units.add(buffer.drain());

        assertThat(String.join("", units))
                .as("전달 과정에서 글자가 사라지거나 순서가 바뀌면 사용자가 읽는 문장이 깨진다")
                .isEqualTo(String.join("", chunks));
    }

    @Test
    @DisplayName("종결 부호 뒤 공백·따옴표는 같은 단위에 붙인다")
    void trailingWhitespaceStaysWithTheUnit() {
        ApprovedUnitBuffer buffer = new ApprovedUnitBuffer();

        assertThat(buffer.offer("\"괜찮아요.\" 다음"))
                .containsExactly("\"괜찮아요.\" ");
    }

    @Test
    @DisplayName("종결 부호가 오지 않아도 상한에서 끊는다")
    void cutsAtMaxUnitLengthWithoutTerminator() {
        ApprovedUnitBuffer buffer = new ApprovedUnitBuffer(10);

        assertThat(buffer.offer("가나다라마바사아자차카타"))
                .as("무한정 모으면 문단 하나가 끝날 때까지 아무것도 못 본다")
                .containsExactly("가나다라마바사아자차");
        assertThat(buffer.drain()).isEqualTo("카타");
    }

    @Test
    @DisplayName("줄바꿈도 단위 경계다")
    void newlineIsBoundary() {
        ApprovedUnitBuffer buffer = new ApprovedUnitBuffer();

        assertThat(buffer.offer("첫 줄\n둘째 줄")).containsExactly("첫 줄\n");
    }

    @Test
    @DisplayName("드레인 후 버퍼는 비어 있다")
    void drainClearsBuffer() {
        ApprovedUnitBuffer buffer = new ApprovedUnitBuffer();
        buffer.offer("남은 텍스트");

        assertThat(buffer.drain()).isEqualTo("남은 텍스트");
        assertThat(buffer.drain()).isEmpty();
    }
}
