package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.policy.GenerationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[QA] 정정 측정 실행기 (네트워크 없음)")
class CorrectionEvalRunnerTest {

    private static final String PASSING_CORRECTION_JSON = """
            {"acknowledged": true, "reused_frame": false, "repeated_advice": false,
             "new_generic_advice": false, "restated": true, "invented_facts": false, "evidence": {}}""";
    private static final String PASSING_CONTROL_JSON = """
            {"spurious_acknowledgment": false, "unnatural_restatement": false,
             "avoided_requested_advice": false, "evidence": {}}""";

    private static final CorrectionEvalSet SET = new CorrectionEvalSet("v", List.of(
            new CorrectionEvalCase("E-1", "fact", "strong", true, List.of(
                    new CorrectionEvalCase.Turn("USER", "바빠요"),
                    new CorrectionEvalCase.Turn("ASSISTANT", "사치 같으신가 봐요"),
                    new CorrectionEvalCase.Turn("USER", "시간이 없다고요"))),
            new CorrectionEvalCase("N-1", "control", null, false, List.of(
                    new CorrectionEvalCase.Turn("USER", "아니 진짜 힘들어")))));

    /** 생성 호출(stream)과 채점 호출(completeJson)을 한 클라이언트로 받아 기록한다. */
    private static final class Scripted implements LlmClient {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        boolean failGeneration;
        boolean failJudge;

        @Override
        public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
            events.add("generate:" + request.model());
            if (failGeneration) {
                throw new RuntimeException("rate limited");
            }
            chunkHandler.accept("제가 잘못 짚었네요. 쉴 틈이 없으셨군요.");
            return new LlmStreamResult(1, LlmUsage.unresolved(request.model()), false);
        }

        @Override
        public String completeJson(LlmRequest request) {
            events.add("judge:" + request.model());
            if (failJudge) {
                throw new RuntimeException("timeout");
            }
            boolean control = request.messages().get(0).content().contains("spurious_acknowledgment");
            return control ? PASSING_CONTROL_JSON : PASSING_CORRECTION_JSON;
        }

        @Override
        public String completeText(LlmRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static CorrectionRunIdentity identity() {
        return CorrectionRunIdentity.stamp("v", "sha", 2, List.of("NORMAL", "SUPPORTIVE"),
                List.of("BASELINE", "WITH_CORRECTION_BLOCK"), "gpt-4o", "gpt-4o-mini");
    }

    private CorrectionEvalRunner.Result run(Scripted client, int parallelism) {
        CorrectionJudge judge = new CorrectionJudge(client, "gpt-4o-mini", CorrectionEvalRunner.JUDGE_COMPONENT);
        return new CorrectionEvalRunner(client, judge, CorrectionEvalRunner.GENERATION_COMPONENT).run(SET,
                List.of(GenerationMode.NORMAL, GenerationMode.SUPPORTIVE),
                List.of(CorrectionPromptArm.BASELINE, CorrectionPromptArm.WITH_CORRECTION_BLOCK),
                2, parallelism, identity());
    }

    @Test
    @DisplayName("모드 × 팔 × 케이스 × 반복만큼 표본을 만들고 정해진 순서로 돌려준다")
    void producesFullMatrixInDeterministicOrder() {
        CorrectionEvalRunner.Result result = run(new Scripted(), 4);

        assertThat(result.samples()).hasSize(2 * 2 * 2 * 2);
        CorrectionEvalRunner.ScoredSample first = result.samples().get(0);
        assertThat(first.mode()).isEqualTo("NORMAL");
        assertThat(first.sample().arm()).isEqualTo(CorrectionPromptArm.BASELINE);
        assertThat(first.sample().caseId()).isEqualTo("E-1");
        assertThat(first.sample().repeat()).isZero();
        // 모드가 가장 바깥, 그다음 팔, 케이스, 반복 순서
        assertThat(result.samples().subList(0, 4)).extracting(s -> s.sample().caseId() + "#" + s.sample().repeat())
                .containsExactly("E-1#0", "E-1#1", "N-1#0", "N-1#1");
        assertThat(result.samples().get(8).mode()).isEqualTo("SUPPORTIVE");
        assertThat(result.samples().get(4).sample().arm()).isEqualTo(CorrectionPromptArm.WITH_CORRECTION_BLOCK);
    }

    @Test
    @DisplayName("병렬도와 무관하게 같은 순서의 같은 결과가 나온다")
    void parallelismDoesNotChangeOrdering() {
        List<String> sequential = ids(run(new Scripted(), 1));
        List<String> parallel = ids(run(new Scripted(), 8));

        assertThat(parallel).isEqualTo(sequential);
    }

    private static List<String> ids(CorrectionEvalRunner.Result result) {
        return result.samples().stream()
                .map(s -> s.mode() + "/" + s.sample().arm() + "/" + s.sample().caseId() + "#" + s.sample().repeat())
                .toList();
    }

    @Test
    @DisplayName("생성된 표본은 채점하고, 채점 결과를 코드 조건으로 통과 판정한다")
    void scoresGeneratedSamples() {
        Scripted client = new Scripted();

        CorrectionEvalRunner.Result result = run(client, 2);

        assertThat(result.samples()).allSatisfy(s -> {
            assertThat(s.verdict()).isNotNull();
            assertThat(s.verdict().judgeFailed()).isFalse();
            assertThat(s.verdict().passed()).isTrue();
            assertThat(s.shape()).isNotNull();
        });
        assertThat(client.events.stream().filter(e -> e.startsWith("generate:")).count()).isEqualTo(16);
        assertThat(client.events.stream().filter(e -> e.startsWith("judge:")).count()).isEqualTo(16);
    }

    @Test
    @DisplayName("생성이 실패한 표본은 채점하지 않고 실패로 표시하며 실행은 계속된다")
    void generationFailureSkipsJudgeButKeepsRunning() {
        Scripted client = new Scripted();
        client.failGeneration = true;

        CorrectionEvalRunner.Result result = run(client, 2);

        assertThat(result.samples()).hasSize(16).allSatisfy(s -> {
            assertThat(s.sample().failed()).isTrue();
            assertThat(s.verdict()).isNull();
        });
        assertThat(client.events).noneMatch(e -> e.startsWith("judge:"));
        assertThat(CorrectionReport.generationFailureShare(result)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("채점이 실패한 표본은 채점 실패로 표시한다 (품질 실패와 구분)")
    void judgeFailureIsMarkedSeparately() {
        Scripted client = new Scripted();
        client.failJudge = true;

        CorrectionEvalRunner.Result result = run(client, 2);

        assertThat(result.samples()).allSatisfy(s -> {
            assertThat(s.sample().failed()).isFalse();
            assertThat(s.verdict().judgeFailed()).isTrue();
        });
        assertThat(CorrectionReport.judgeFailureShare(result)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("잘못된 반복·병렬 설정은 거부한다")
    void rejectsInvalidSettings() {
        Scripted client = new Scripted();
        CorrectionEvalRunner runner = new CorrectionEvalRunner(client,
                new CorrectionJudge(client, "m"), null);

        assertThatThrownBy(() -> runner.run(SET, List.of(GenerationMode.NORMAL),
                List.of(CorrectionPromptArm.BASELINE), 0, 1, identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.run(SET, List.of(GenerationMode.NORMAL),
                List.of(CorrectionPromptArm.BASELINE), 1, 0, identity()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
