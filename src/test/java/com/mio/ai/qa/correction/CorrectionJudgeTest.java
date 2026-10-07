package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[QA] 정정 대응 채점기 (네트워크 없음)")
class CorrectionJudgeTest {

    private static CorrectionEvalCase correctionCase(String type) {
        return new CorrectionEvalCase("C-1", type, "strong", true, List.of(
                new CorrectionEvalCase.Turn("USER", "요즘 너무 바빠요"),
                new CorrectionEvalCase.Turn("ASSISTANT", "쉬는 게 사치처럼 느껴지시나 봐요"),
                new CorrectionEvalCase.Turn("USER", "그게 아니라 시간이 아예 없어요")));
    }

    private static CorrectionEvalCase controlCase(boolean adviceRequested) {
        return new CorrectionEvalCase("N-1", "control", null, false, adviceRequested, List.of(
                new CorrectionEvalCase.Turn("USER", "잠이 안 와요. 뭘 해보면 좋을까요?")));
    }

    private static final class FakeClient implements LlmClient {
        final List<LlmRequest> requests = new ArrayList<>();
        private final String json;
        private final RuntimeException failure;

        FakeClient(String json) {
            this.json = json;
            this.failure = null;
        }

        FakeClient(RuntimeException failure) {
            this.json = null;
            this.failure = failure;
        }

        @Override
        public String completeJson(LlmRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return json;
        }

        @Override
        public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String completeText(LlmRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static String correctionJson(boolean acknowledged, boolean reusedFrame, boolean repeatedAdvice,
                                         boolean newAdvice, boolean restated, boolean invented) {
        return """
                {"acknowledged": %s, "reused_frame": %s, "repeated_advice": %s,
                 "new_generic_advice": %s, "restated": %s, "invented_facts": %s,
                 "evidence": {"new_generic_advice": "이동시간에 호흡을"}}"""
                .formatted(acknowledged, reusedFrame, repeatedAdvice, newAdvice, restated, invented);
    }

    @Test
    @DisplayName("인정·재진술이 있고 조언·프레임 재사용·지어냄이 없으면 통과한다")
    void passesGoodCorrectionResponse() {
        CorrectionJudge judge = new CorrectionJudge(
                new FakeClient(correctionJson(true, false, false, false, true, false)), "m");

        CorrectionVerdict verdict = judge.judge(correctionCase("fact"), "제가 잘못 짚었네요. 쉴 틈이 없으셨군요.");

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.judgeFailed()).isFalse();
    }

    @Test
    @DisplayName("새 일반 조언이 있으면 통과하지 못하고, 근거 인용이 남는다")
    void failsWhenGenericAdviceGiven() {
        CorrectionJudge judge = new CorrectionJudge(
                new FakeClient(correctionJson(false, false, false, true, false, false)), "m");

        CorrectionVerdict verdict = judge.judge(correctionCase("fact"), "이동시간에 호흡을 골라 보세요.");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.evidence()).containsEntry("new_generic_advice", "이동시간에 호흡을");
    }

    @Test
    @DisplayName("재진술이 없으면 인정만 있어도 통과하지 못한다")
    void failsWithoutRestatement() {
        CorrectionJudge judge = new CorrectionJudge(
                new FakeClient(correctionJson(true, false, false, false, false, false)), "m");

        assertThat(judge.judge(correctionCase("fact"), "죄송해요.").passed()).isFalse();
    }

    @Test
    @DisplayName("지어낸 내용이 있으면 통과하지 못한다")
    void failsWhenFactsInvented() {
        CorrectionJudge judge = new CorrectionJudge(
                new FakeClient(correctionJson(true, false, false, false, true, true)), "m");

        assertThat(judge.judge(correctionCase("fact"), "회사 상사 때문이군요.").passed()).isFalse();
    }

    @Test
    @DisplayName("조언 거절 종류는 프레임 재사용 항목을 통과 조건에서 뺀다")
    void adviceRefusalIgnoresReusedFrame() {
        String json = correctionJson(true, true, false, false, true, false);

        assertThat(new CorrectionJudge(new FakeClient(json), "m")
                .judge(correctionCase("advice_refusal"), "이미 해보셨군요.").passed()).isTrue();
        assertThat(new CorrectionJudge(new FakeClient(json), "m")
                .judge(correctionCase("fact"), "이미 해보셨군요.").passed()).isFalse();
    }

    @Test
    @DisplayName("대조군: 불필요한 인정이나 어색한 재진술이 있으면 통과하지 못한다")
    void controlFailsOnSideEffects() {
        String spurious = """
                {"spurious_acknowledgment": true, "unnatural_restatement": false,
                 "avoided_requested_advice": false, "evidence": {}}""";

        assertThat(new CorrectionJudge(new FakeClient(spurious), "m")
                .judge(controlCase(false), "제가 잘못 짚었네요.").passed()).isFalse();
    }

    @Test
    @DisplayName("대조군: 조언 회피는 조언을 요청한 케이스에서만 실패로 센다")
    void controlAdviceAvoidanceCountsOnlyWhenAdviceRequested() {
        String avoided = """
                {"spurious_acknowledgment": false, "unnatural_restatement": false,
                 "avoided_requested_advice": true, "evidence": {}}""";

        assertThat(new CorrectionJudge(new FakeClient(avoided), "m")
                .judge(controlCase(true), "잠이 안 오셔서 힘드시군요.").passed()).isFalse();
        assertThat(new CorrectionJudge(new FakeClient(avoided), "m")
                .judge(controlCase(false), "잠이 안 오셔서 힘드시군요.").passed()).isTrue();
    }

    @Test
    @DisplayName("채점 호출 실패·깨진 JSON·빠진 항목·빈 응답은 품질 실패가 아니라 채점 실패로 표시한다")
    void marksJudgeFailuresSeparately() {
        CorrectionEvalCase evalCase = correctionCase("fact");

        assertThat(new CorrectionJudge(new FakeClient(new RuntimeException("timeout")), "m")
                .judge(evalCase, "응답").judgeFailed()).isTrue();
        assertThat(new CorrectionJudge(new FakeClient("not json"), "m")
                .judge(evalCase, "응답").judgeFailed()).isTrue();
        assertThat(new CorrectionJudge(new FakeClient("{\"acknowledged\": true}"), "m")
                .judge(evalCase, "응답").judgeFailed()).isTrue();
        FakeClient unused = new FakeClient(correctionJson(true, false, false, false, true, false));
        assertThat(new CorrectionJudge(unused, "m").judge(evalCase, "  ").judgeFailed()).isTrue();
        assertThat(unused.requests).as("빈 응답은 채점 호출을 하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("채점 요청은 설정한 모델로 나가고, 라벨·대화·응답을 담되 팔·모드 정보는 없다")
    void requestUsesConfiguredModelAndCarriesNoArmInformation() {
        FakeClient client = new FakeClient(correctionJson(true, false, false, false, true, false));
        CorrectionJudge judge = new CorrectionJudge(client, "judge-model-x");

        judge.judge(correctionCase("fact"), "제가 잘못 짚었네요.");

        LlmRequest request = client.requests.get(0);
        assertThat(request.model()).isEqualTo("judge-model-x");
        assertThat(request.maxCompletionTokens()).isEqualTo(CorrectionJudge.MAX_COMPLETION_TOKENS);
        String user = request.messages().get(1).content();
        assertThat(user).contains("type: fact", "ASSISTANT: 쉬는 게 사치처럼 느껴지시나 봐요",
                "[평가할 응답]\n제가 잘못 짚었네요.");
        assertThat(user).doesNotContainIgnoringCase("baseline").doesNotContain("WITH_CORRECTION_BLOCK");
    }

    @Test
    @DisplayName("채점 모델 기본값은 gpt-4o-mini 이고 시스템 속성으로 바꿀 수 있다")
    void judgeModelIsConfigurable() {
        String previous = System.getProperty(CorrectionJudge.MODEL_PROPERTY);
        try {
            System.clearProperty(CorrectionJudge.MODEL_PROPERTY);
            assertThat(CorrectionJudge.configuredModel()).isEqualTo("gpt-4o-mini");

            System.setProperty(CorrectionJudge.MODEL_PROPERTY, " gpt-4.1 ");
            assertThat(CorrectionJudge.configuredModel()).isEqualTo("gpt-4.1");
        } finally {
            if (previous == null) {
                System.clearProperty(CorrectionJudge.MODEL_PROPERTY);
            } else {
                System.setProperty(CorrectionJudge.MODEL_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("객관 지표: 물음표·문장 수를 프로덕션 규칙으로 센다")
    void shapeMetricsUseProductionCounting() {
        CorrectionResponseShape shape = CorrectionResponseShape.of(
                "제가 잘못 짚었네요. 쉴 틈이 없으셨군요. 많이 지치셨겠어요.");

        assertThat(shape.questions()).isZero();
        assertThat(shape.sentences()).isEqualTo(3);
        assertThat(shape.withinPersonaLength()).isTrue();
        assertThat(CorrectionResponseShape.of("어떠세요?").questions()).isEqualTo(1);
    }
}
