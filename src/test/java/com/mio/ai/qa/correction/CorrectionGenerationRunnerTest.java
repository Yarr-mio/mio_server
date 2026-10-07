package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.orchestrator.ConversationOrchestrator;
import com.mio.ai.policy.GenerationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[QA] 정정 시험 생성 하네스 (네트워크 없음)")
class CorrectionGenerationRunnerTest {

    private static final CorrectionEvalCase CASE = new CorrectionEvalCase(
            "C-1", "fact", "strong", true, List.of(
            new CorrectionEvalCase.Turn("USER", "요즘 너무 바빠요"),
            new CorrectionEvalCase.Turn("ASSISTANT", "쉬는 게 사치처럼 느껴지시나 봐요"),
            new CorrectionEvalCase.Turn("USER", "그게 아니라 시간이 아예 없어요")));

    private static final CorrectionEvalCase OTHER = new CorrectionEvalCase(
            "N-1", "control", null, false, List.of(
            new CorrectionEvalCase.Turn("USER", "아니 진짜 너무 힘들어")));

    private static final class RecordingClient implements LlmClient {
        final List<LlmRequest> requests = new ArrayList<>();
        private final Function<LlmRequest, String> responder;
        private final boolean truncated;

        RecordingClient(Function<LlmRequest, String> responder) {
            this(responder, false);
        }

        RecordingClient(Function<LlmRequest, String> responder, boolean truncated) {
            this.responder = responder;
            this.truncated = truncated;
        }

        @Override
        public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
            requests.add(request);
            chunkHandler.accept(responder.apply(request));
            return new LlmStreamResult(1, LlmUsage.unresolved(request.model()), truncated);
        }

        @Override
        public String completeText(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String completeJson(LlmRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    @DisplayName("AI 직전 발화를 이력에 순서대로 싣고, 마지막 사용자 발화를 입력으로 보낸다")
    void carriesAssistantTurnsInHistory() {
        RecordingClient client = new RecordingClient(r -> "응답");
        CorrectionGenerationRunner runner = CorrectionGenerationRunner.production(client, GenerationMode.NORMAL);

        runner.run(List.of(CASE), CorrectionPromptArm.BASELINE, 1);

        List<LlmRequest.Message> messages = client.requests.get(0).messages();
        assertThat(messages).extracting(LlmRequest.Message::role)
                .containsExactly("system", "user", "assistant", "user");
        assertThat(messages.get(2).content()).isEqualTo("쉬는 게 사치처럼 느껴지시나 봐요");
        assertThat(messages.get(3).content()).isEqualTo("그게 아니라 시간이 아예 없어요");
    }

    @Test
    @DisplayName("기준선 팔은 이제 프로덕션 프롬프트(정정 대응 블록 포함) 그대로이고, "
            + "후보 팔은 같은 블록을 중복으로 한 번 더 붙인다")
    void armsDifferOnlyByCorrectionBlock() {
        // 이슈 #558로 CORRECTION_BLOCK이 PromptBuilder에 반영된 뒤로는 BASELINE도
        // 프로덕션 그대로라 [정정 대응]이 이미 포함된다. WITH_CORRECTION_BLOCK은 같은
        // 블록을 한 번 더 붙이므로(회귀 비교용으로만 남김) 둘의 차이는 그 중복 한 벌이다.
        RecordingClient client = new RecordingClient(r -> "응답");
        CorrectionGenerationRunner runner = CorrectionGenerationRunner.production(client, GenerationMode.NORMAL);

        runner.run(List.of(CASE), CorrectionPromptArm.BASELINE, 1);
        runner.run(List.of(CASE), CorrectionPromptArm.WITH_CORRECTION_BLOCK, 1);

        String baseline = client.requests.get(0).messages().get(0).content();
        String candidate = client.requests.get(1).messages().get(0).content();
        assertThat(baseline).contains("당신은 미오입니다").contains("[정정 대응]");
        assertThat(candidate).isEqualTo(baseline + "\n\n" + CorrectionPromptArm.CORRECTION_BLOCK);
    }

    @Test
    @DisplayName("케이스마다 repeats 번 생성하고 순서를 유지한다")
    void repeatsEachCaseInOrder() {
        RecordingClient client = new RecordingClient(r -> "응답");
        CorrectionGenerationRunner runner = CorrectionGenerationRunner.production(client, GenerationMode.NORMAL);

        List<CorrectionGenerationRunner.Sample> samples =
                runner.run(List.of(CASE, OTHER), CorrectionPromptArm.BASELINE, 3);

        assertThat(samples).hasSize(6);
        assertThat(samples).extracting(CorrectionGenerationRunner.Sample::caseId)
                .containsExactly("C-1", "C-1", "C-1", "N-1", "N-1", "N-1");
        assertThat(samples).extracting(CorrectionGenerationRunner.Sample::repeat)
                .containsExactly(0, 1, 2, 0, 1, 2);
        assertThatThrownBy(() -> runner.run(List.of(CASE), CorrectionPromptArm.BASELINE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한 호출이 실패해도 나머지는 계속하고, 실패·빈 본문·잘림을 따로 표시한다")
    void marksFailuresWithoutAbortingTheRun() {
        int[] calls = {0};
        RecordingClient client = new RecordingClient(r -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new RuntimeException("rate limited");
            }
            return calls[0] == 2 ? "  " : "정상 응답";
        });
        CorrectionGenerationRunner runner = CorrectionGenerationRunner.production(client, GenerationMode.NORMAL);

        List<CorrectionGenerationRunner.Sample> samples =
                runner.run(List.of(CASE), CorrectionPromptArm.BASELINE, 3);

        assertThat(samples).extracting(CorrectionGenerationRunner.Sample::failed)
                .containsExactly(true, true, false);
        assertThat(samples.get(2).response()).isEqualTo("정상 응답");
    }

    @Test
    @DisplayName("출력 토큰 상한에 걸린 응답은 truncated 로 표시한다")
    void marksTruncatedResponses() {
        RecordingClient client = new RecordingClient(r -> "잘린 응", true);
        CorrectionGenerationRunner runner = CorrectionGenerationRunner.production(client, GenerationMode.NORMAL);

        List<CorrectionGenerationRunner.Sample> samples =
                runner.run(List.of(CASE), CorrectionPromptArm.BASELINE, 1);

        assertThat(samples.get(0).truncated()).isTrue();
        assertThat(samples.get(0).failed()).isFalse();
    }

    @Test
    @DisplayName("프로덕션 기본 생성 모델과 출력 토큰 상한을 그대로 쓴다")
    void usesProductionModelAndTokenCap() throws Exception {
        RecordingClient client = new RecordingClient(r -> "응답");
        CorrectionGenerationRunner.production(client, GenerationMode.NORMAL)
                .run(List.of(CASE), CorrectionPromptArm.BASELINE, 1);

        LlmRequest request = client.requests.get(0);
        assertThat(request.model()).isEqualTo(CorrectionGenerationRunner.productionModel());
        assertThat(request.maxCompletionTokens()).isEqualTo(CorrectionGenerationRunner.PRODUCTION_MAX_COMPLETION_TOKENS);

        Field field = ConversationOrchestrator.class.getDeclaredField("LLM_MAX_COMPLETION_TOKENS");
        field.setAccessible(true);
        assertThat(CorrectionGenerationRunner.PRODUCTION_MAX_COMPLETION_TOKENS)
                .as("프로덕션 출력 토큰 상한이 바뀌었다 — 하네스 복제값을 같이 고쳐야 한다")
                .isEqualTo(field.getInt(null));
    }

    @Test
    @DisplayName("비용 귀속 태그를 붙이지 않아 ai_cost_events 에 기록되지 않는다")
    void doesNotAttributeCost() {
        RecordingClient client = new RecordingClient(r -> "응답");
        CorrectionGenerationRunner.production(client, GenerationMode.NORMAL)
                .run(List.of(CASE), CorrectionPromptArm.BASELINE, 1);

        assertThat(client.requests.get(0).component()).isNull();
    }
}
