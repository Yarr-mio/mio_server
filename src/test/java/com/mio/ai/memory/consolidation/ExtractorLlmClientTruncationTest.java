package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 절단된 추출 응답이 부분 결과로 저장되지 않는지 확인한다.
 *
 * <p>"파싱이 실패할 테니 괜찮다"에 기댈 수 없다는 것이 핵심이다.
 * {@code ObjectMapper.readTree} 는 {@code FAIL_ON_TRAILING_TOKENS} 가 기본 off 라서
 * 선행 JSON 객체만 읽고 뒤에 붙은 잘린 내용을 조용히 버린다. 그래서 절단된 응답이
 * <b>파싱에 성공하고</b> 누락 필드가 기본값으로 채워질 수 있다.
 */
class ExtractorLlmClientTruncationTest {

    /** 잘렸지만 선행 객체가 완결돼 파싱은 성공하는 응답. 실측으로 확인된 형태다. */
    private static final String TRUNCATED_BUT_PARSEABLE =
            "{\"dominantEmotion\":\"sadness\"} 그리고 이 세션에서 사용자는 반복적으로";

    @Test
    @DisplayName("절단된 응답은 파싱 가능해도 비운다 — 누락 필드가 기본값으로 굳는 것을 막는다")
    void truncatedResponseYieldsEmptyResult() {
        ExtractorLlmClient client = new ExtractorLlmClient(
                stubClient(TRUNCATED_BUT_PARSEABLE, true), new ObjectMapper());

        ExtractorResult result = client.extract("세션 요약 본문", null, null);

        assertThat(result.dominantEmotion())
                .as("잘린 응답에서 읽은 감정을 저장하면 부분 분석 결과가 정본이 된다")
                .isNull();
        assertThat(result.thoughts()).isEmpty();
        assertThat(result.triggerTags()).isEmpty();
    }

    @Test
    @DisplayName("같은 응답이 절단되지 않았다면 그대로 파싱한다 — 절단 여부만이 차이다")
    void sameResponseIsParsedWhenNotTruncated() {
        ExtractorLlmClient client = new ExtractorLlmClient(
                stubClient(TRUNCATED_BUT_PARSEABLE, false), new ObjectMapper());

        ExtractorResult result = client.extract("세션 요약 본문", null, null);

        assertThat(result.dominantEmotion())
                .as("절단이 아니면 기존 동작을 바꾸지 않는다 — 이 값이 null 이면 회귀다")
                .isEqualTo("sadness");
    }

    private LlmClient stubClient(String response, boolean truncated) {
        return new LlmClient() {
            @Override
            public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
                chunkHandler.accept(response);
                return new LlmStreamResult(10L, LlmUsage.unresolved(request.model()), truncated);
            }

            @Override
            public String completeText(LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String completeJson(LlmRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
