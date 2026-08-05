package com.mio.ai.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRequestTest {

    @Test
    @DisplayName("기본 팩토리는 상한을 지정하지 않는다")
    void factoryLeavesMaxCompletionTokensUnset() {
        assertThat(LlmRequest.of("gpt-4o", "system", "user").maxCompletionTokens()).isNull();
    }

    @Test
    @DisplayName("withMaxCompletionTokens는 원본을 바꾸지 않고 복사본을 만든다")
    void witherDoesNotMutateOriginal() {
        LlmRequest original = LlmRequest.of("gpt-4o", "system", "user");

        LlmRequest capped = original.withMaxCompletionTokens(400);

        assertThat(capped.maxCompletionTokens()).isEqualTo(400);
        assertThat(original.maxCompletionTokens()).isNull();
        assertThat(capped.messages()).isEqualTo(original.messages());
        assertThat(capped.model()).isEqualTo(original.model());
    }

    @Test
    @DisplayName("0 이하 상한은 거부한다 — 조용히 빈 응답을 만드느니 즉시 실패한다")
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> LlmRequest.of("gpt-4o", "s", "u").withMaxCompletionTokens(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmRequest("gpt-4o", java.util.List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
