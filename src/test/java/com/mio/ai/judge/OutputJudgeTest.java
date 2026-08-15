package com.mio.ai.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Output Judge 의 판정과 판정 실패 구분 (이슈 #364, 로드맵 §12 P0-2).
 *
 * <p>여기서 확인하는 것은 <b>동작</b>이 아니라 <b>기록</b>이다. 실패는 이전에도 지금도
 * {@code REPLACE} 로 처리된다. 달라진 것은 그것이 판정이 아니었다는 사실이 남는다는 점이다.
 */
@DisplayName("OutputJudge — 판정 실패와 REPLACE 판정의 구분")
class OutputJudgeTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("모델이 REPLACE 로 판정하면 failed 가 아니다")
    void replaceVerdictIsNotAFailure() {
        OutputJudge judge = judgeReturning(() -> "{\"action\":\"REPLACE\",\"rewritten_content\":null}");

        OutputJudgeResult result = judge.judge("위험한 응답", failingPreFilter(), null, null);

        assertThat(result.action()).isEqualTo(OutputJudgeAction.REPLACE);
        assertThat(result.failed()).isFalse();
        assertThat(counter("succeeded")).isEqualTo(1.0);
        assertThat(counter("failed")).isZero();
    }

    @Test
    @DisplayName("호출이 예외로 끝나면 같은 REPLACE 라도 failed 로 남는다")
    void exceptionYieldsFailedFlagWithSameReplaceAction() {
        OutputJudge judge = judgeReturning(() -> {
            throw new RuntimeException("upstream timeout");
        });

        OutputJudgeResult result = judge.judge("아무 응답", failingPreFilter(), null, null);

        // 동작은 이전과 같다 — 가장 보수적인 REPLACE.
        assertThat(result.action()).isEqualTo(OutputJudgeAction.REPLACE);
        // 하지만 판정을 받은 것이 아니라는 사실이 남는다. 이 값이 없으면 판정 실패율이
        // 올라가도 "안전 판정이 늘었다" 로 보인다.
        assertThat(result.failed()).isTrue();
        assertThat(counter("failed")).isEqualTo(1.0);
        assertThat(counter("succeeded")).isZero();
    }

    @Test
    @DisplayName("깨진 JSON 도 판정 실패다")
    void malformedJsonIsAFailure() {
        OutputJudge judge = judgeReturning(() -> "{\"action\": ");

        OutputJudgeResult result = judge.judge("아무 응답", failingPreFilter(), null, null);

        assertThat(result.action()).isEqualTo(OutputJudgeAction.REPLACE);
        assertThat(result.failed()).isTrue();
        assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("action 이 누락된 응답은 REPLACE 판정이 아니라 판정 실패다")
    void missingActionIsAFailure() {
        OutputJudge judge = judgeReturning(() -> "{\"rewritten_content\":null}");

        OutputJudgeResult result = judge.judge("아무 응답", failingPreFilter(), null, null);

        assertThat(result.action()).isEqualTo(OutputJudgeAction.REPLACE);
        assertThat(result.failed()).isTrue();
        assertThat(counter("failed")).isEqualTo(1.0);
        assertThat(counter("succeeded")).isZero();
    }

    @Test
    @DisplayName("스키마에 없는 action 은 REPLACE 판정이 아니라 판정 실패다")
    void unknownActionIsAFailure() {
        OutputJudge judge = judgeReturning(() -> "{\"action\":\"UNKNOWN\"}");

        OutputJudgeResult result = judge.judge("아무 응답", failingPreFilter(), null, null);

        assertThat(result.action()).isEqualTo(OutputJudgeAction.REPLACE);
        assertThat(result.failed()).isTrue();
        assertThat(counter("failed")).isEqualTo(1.0);
        assertThat(counter("succeeded")).isZero();
    }

    @Test
    @DisplayName("REWRITE 에 본문이 없으면 판정 실패다")
    void rewriteWithoutContentIsAFailure() {
        OutputJudge judge = judgeReturning(() -> "{\"action\":\"REWRITE\"}");

        OutputJudgeResult result = judge.judge("아무 응답", failingPreFilter(), null, null);

        assertThat(result.action()).isEqualTo(OutputJudgeAction.REPLACE);
        assertThat(result.failed()).isTrue();
        assertThat(counter("failed")).isEqualTo(1.0);
        assertThat(counter("succeeded")).isZero();
    }

    @Test
    @DisplayName("SEND 판정은 성공으로 센다")
    void sendVerdictCountsAsSucceeded() {
        OutputJudge judge = judgeReturning(() -> "{\"action\":\"SEND\",\"rewritten_content\":null}");

        OutputJudgeResult result = judge.judge("안전한 응답", failingPreFilter(), null, null);

        assertThat(result.action()).isEqualTo(OutputJudgeAction.SEND);
        assertThat(result.failed()).isFalse();
        assertThat(counter("succeeded")).isEqualTo(1.0);
    }

    private double counter(String outcome) {
        return meterRegistry.counter("mio.judge.output", "outcome", outcome).count();
    }

    private OutputPreFilterResult failingPreFilter() {
        return OutputPreFilterResult.fail(List.of("role_deviation"));
    }

    private OutputJudge judgeReturning(Supplier<String> responseJson) {
        LlmClient llmClient = new LlmClient() {
            @Override
            public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String completeText(LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String completeJson(LlmRequest request) {
                return responseJson.get();
            }
        };
        return new OutputJudge(llmClient, new ObjectMapper(), meterRegistry);
    }
}
