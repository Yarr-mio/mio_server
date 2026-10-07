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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[QA] 채점 다수결 (네트워크 없음)")
class CorrectionJudgeVotesTest {

    private static final CorrectionEvalCase CASE = new CorrectionEvalCase("E-1", "fact", "strong", true, List.of(
            new CorrectionEvalCase.Turn("USER", "a"), new CorrectionEvalCase.Turn("ASSISTANT", "b"),
            new CorrectionEvalCase.Turn("USER", "c")));

    private static String json(boolean acknowledged, boolean newAdvice, boolean restated) {
        return """
                {"acknowledged": %s, "reused_frame": false, "repeated_advice": false,
                 "new_generic_advice": %s, "restated": %s, "invented_facts": false,
                 "evidence": {"new_generic_advice": "해 보세요"}}""".formatted(acknowledged, newAdvice, restated);
    }

    /** 호출 순서대로 미리 정해 둔 응답을 돌려주고, "FAIL" 이면 예외를 던진다. */
    private static final class Sequenced implements LlmClient {
        private final List<String> responses;
        int calls;

        Sequenced(String... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public String completeJson(LlmRequest request) {
            String next = responses.get(calls++ % responses.size());
            if ("FAIL".equals(next)) {
                throw new RuntimeException("timeout");
            }
            return next;
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

    @Test
    @DisplayName("항목마다 다수결로 정하고 통과 여부는 다수결 항목에서 다시 계산한다")
    void majorityDecidesEachItem() {
        // 세 번 중 두 번이 "새 조언 없음·재진술 있음" → 통과. 흔들린 한 번(새 조언 있음)은 무시된다.
        Sequenced client = new Sequenced(json(true, false, true), json(true, true, true), json(true, false, true));

        CorrectionVerdict verdict = new CorrectionJudge(client, "m", null, 3).judge(CASE, "응답");

        assertThat(client.calls).isEqualTo(3);
        assertThat(verdict.items()).containsEntry(CorrectionVerdict.NEW_GENERIC_ADVICE, false);
        assertThat(verdict.passed()).isTrue();
    }

    @Test
    @DisplayName("다수가 '예'면 그 항목은 예이고, 근거 인용은 '예'를 낸 투표에서 가져온다")
    void majorityYesKeepsEvidence() {
        Sequenced client = new Sequenced(json(true, true, true), json(true, true, true), json(true, false, true));

        CorrectionVerdict verdict = new CorrectionJudge(client, "m", null, 3).judge(CASE, "응답");

        assertThat(verdict.items()).containsEntry(CorrectionVerdict.NEW_GENERIC_ADVICE, true);
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.evidence()).containsEntry(CorrectionVerdict.NEW_GENERIC_ADVICE, "해 보세요");
    }

    @Test
    @DisplayName("실패한 투표는 세지 않고, 하나라도 성공하면 그 결과를 쓰며, 전부 실패하면 채점 실패다")
    void failedVotesAreIgnored() {
        assertThat(new CorrectionJudge(new Sequenced("FAIL", json(true, false, true), "FAIL"), "m", null, 3)
                .judge(CASE, "응답").passed()).isTrue();
        assertThat(new CorrectionJudge(new Sequenced("FAIL"), "m", null, 3).judge(CASE, "응답").judgeFailed()).isTrue();
    }

    @Test
    @DisplayName("성공한 투표가 짝수여서 동수가 되면 '아니오'로 둔다")
    void tieIsNo() {
        Sequenced client = new Sequenced(json(true, true, true), json(true, false, true), "FAIL");

        CorrectionVerdict verdict = new CorrectionJudge(client, "m", null, 3).judge(CASE, "응답");

        assertThat(verdict.items()).containsEntry(CorrectionVerdict.NEW_GENERIC_ADVICE, false);
    }

    @Test
    @DisplayName("채점 호출이 일시적으로 실패하면 재시도하고, 실패 사유를 기록하며, 시도를 다 쓰면 채점 실패다")
    void retriesTransientFailuresAndRecordsReasons() {
        Sequenced flaky = new Sequenced("FAIL", "FAIL", json(true, false, true));
        CorrectionJudge retrying = new CorrectionJudge(flaky, "m", null, 1).withRetries(3, 0);

        assertThat(retrying.judge(CASE, "응답").passed()).isTrue();
        assertThat(flaky.calls).isEqualTo(3);
        assertThat(retrying.failureReasons()).containsEntry("RuntimeException: timeout", 2);

        Sequenced alwaysFailing = new Sequenced("FAIL");
        CorrectionJudge exhausted = new CorrectionJudge(alwaysFailing, "m", null, 1).withRetries(2, 0);
        assertThat(exhausted.judge(CASE, "응답").judgeFailed()).isTrue();
        assertThat(alwaysFailing.calls).isEqualTo(2);
        assertThat(new CorrectionJudge(new Sequenced("FAIL"), "m", null, 1).judge(CASE, "응답").judgeFailed()).isTrue();
    }

    @Test
    @DisplayName("실패 사유에 API 키처럼 보이는 문자열이 있으면 가린다")
    void failureReasonsMaskKeyLikeStrings() {
        LlmClient failing = new LlmClient() {
            @Override
            public String completeJson(LlmRequest request) {
                throw new RuntimeException("Incorrect API key provided: sk-proj-abcdEFGH1234");
            }

            @Override
            public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String completeText(LlmRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        CorrectionJudge judge = new CorrectionJudge(failing, "m", null, 1);

        judge.judge(CASE, "응답");

        assertThat(judge.failureReasons().keySet()).allSatisfy(reason -> assertThat(reason).doesNotContain("abcdEFGH1234"));
        assertThat(judge.failureReasons().keySet()).anyMatch(reason -> reason.contains("sk-***"));
    }

    @Test
    @DisplayName("투표 수는 1~9 사이의 홀수만 허용하고, 환경 변수 기본값은 3이다")
    void votesValidationAndConfig() {
        assertThatThrownBy(() -> new CorrectionJudge(null, "m", null, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorrectionJudge(null, "m", null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorrectionJudge(null, "m", null, 11)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CorrectionJudge.configuredVotes(k -> null)).isEqualTo(3);
        assertThat(CorrectionJudge.configuredVotes(k -> " 5 ")).isEqualTo(5);
        assertThatThrownBy(() -> CorrectionJudge.configuredVotes(k -> "4")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorrectionJudge.configuredVotes(k -> "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("후보 블록에는 정정이 아닌 경우 적용하지 않는다는 범위 문장과 조언·격려·질문 금지가 있고, 지문은 8자다")
    void candidateBlockScopeAndFingerprint() {
        assertThat(CorrectionPromptArm.CORRECTION_BLOCK).contains("이 지시를 적용하지 말고", "사과나 인정을 하지 마세요", "질문", "마무리는 전부 셋째 문장");
        assertThat(CorrectionPromptArm.blockFingerprint()).hasSize(8);
    }
}
