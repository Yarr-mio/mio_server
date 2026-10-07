package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.qa.correction.CorrectionEvalRunner.ScoredSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[QA] 저장된 응답 재채점 (네트워크 없음)")
class CorrectionRejudgeTest {

    private static final CorrectionEvalCase FACT = new CorrectionEvalCase("D-F-01", "fact", "strong", true, List.of(
            new CorrectionEvalCase.Turn("USER", "a"), new CorrectionEvalCase.Turn("ASSISTANT", "b"),
            new CorrectionEvalCase.Turn("USER", "c")));
    private static final CorrectionEvalSet SET = new CorrectionEvalSet("correction-dev-v1", List.of(FACT));

    private static final class FixedJudgeClient implements LlmClient {
        private final String json;
        int calls;

        FixedJudgeClient(String json) {
            this.json = json;
        }

        @Override
        public String completeJson(LlmRequest request) {
            calls++;
            return json;
        }

        @Override
        public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
            throw new UnsupportedOperationException("재채점은 응답을 다시 생성하지 않는다");
        }

        @Override
        public String completeText(LlmRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static Path archiveWithOldFailingVerdict(Path dir, String setVersion) {
        CorrectionVerdict old = new CorrectionVerdict("D-F-01", true, Map.of(
                CorrectionVerdict.ACKNOWLEDGED, true, CorrectionVerdict.REUSED_FRAME, false,
                CorrectionVerdict.REPEATED_ADVICE, true, CorrectionVerdict.NEW_GENERIC_ADVICE, false,
                CorrectionVerdict.RESTATED, true, CorrectionVerdict.INVENTED_FACTS, false), Map.of(), false, false);
        CorrectionRunIdentity identity = CorrectionRunIdentity.stamp(setVersion, "sha", 1, List.of("NORMAL"),
                List.of("WITH_CORRECTION_BLOCK"), "gpt-4o", "gpt-4o-mini");
        return CorrectionRunArchive.write(new CorrectionEvalRunner.Result(identity, SET, List.of(new ScoredSample(
                "NORMAL", new CorrectionGenerationRunner.Sample("D-F-01", CorrectionPromptArm.WITH_CORRECTION_BLOCK, 0,
                        "제가 잘못 짚었네요. 쉴 틈이 없으셨군요.", false, false), old,
                CorrectionResponseShape.of("제가 잘못 짚었네요.")))), dir);
    }

    @Test
    @DisplayName("아카이브를 읽어 같은 응답을 새 판정으로 다시 채점하고, 바뀐 항목과 통과 여부를 알려 준다")
    void rejudgesSameResponsesAndReportsChanges(@TempDir Path dir) {
        Path file = archiveWithOldFailingVerdict(dir, "correction-dev-v1");
        CorrectionRejudge.Loaded loaded = CorrectionRejudge.load(file);
        FixedJudgeClient client = new FixedJudgeClient("""
                {"acknowledged": true, "reused_frame": false, "repeated_advice": false,
                 "new_generic_advice": false, "restated": true, "invented_facts": false, "evidence": {}}""");

        List<ScoredSample> rescored = CorrectionRejudge.rejudge(loaded, SET,
                new CorrectionJudge(client, "m"), () -> false);
        String changes = CorrectionRejudge.describeChanges(loaded, rescored);

        assertThat(client.calls).isEqualTo(1);
        assertThat(rescored.get(0).verdict().passed()).isTrue();
        assertThat(changes).contains("항목 판정이 바뀐 것 1건", "통과 여부가 바뀐 표본 1개",
                "repeated_advice 예→아니오", "D-F-01 · NORMAL · WITH_CORRECTION_BLOCK: 통과 아니오→예");
    }

    @Test
    @DisplayName("예산을 넘으면 남은 표본은 채점하지 않는다")
    void skipsSamplesOnceBudgetIsExceeded(@TempDir Path dir) {
        CorrectionRejudge.Loaded loaded = CorrectionRejudge.load(archiveWithOldFailingVerdict(dir, "correction-dev-v1"));
        FixedJudgeClient client = new FixedJudgeClient("{}");

        List<ScoredSample> rescored = CorrectionRejudge.rejudge(loaded, SET, new CorrectionJudge(client, "m"), () -> true);

        assertThat(client.calls).isZero();
        assertThat(rescored.get(0).verdict()).isNull();
    }

    @Test
    @DisplayName("튜닝용이 아닌 세트의 결과는 기준표 조정에 쓰지 않는다")
    void refusesNonDevSetResults(@TempDir Path dir) {
        CorrectionRejudge.Loaded loaded = CorrectionRejudge.load(archiveWithOldFailingVerdict(dir, "correction-eval-v1"));

        assertThatThrownBy(() -> CorrectionRejudge.requireRejudgeable(loaded.identity(), SET, "sha"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("평가용");
    }

    @Test
    @DisplayName("세트 파일이 실행 당시와 다르면(해시 불일치) 거부한다")
    void refusesChangedSetFile(@TempDir Path dir) {
        CorrectionRejudge.Loaded loaded = CorrectionRejudge.load(archiveWithOldFailingVerdict(dir, "correction-dev-v1"));

        assertThatThrownBy(() -> CorrectionRejudge.requireRejudgeable(loaded.identity(), SET, "different"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("해시 불일치");
        CorrectionRejudge.requireRejudgeable(loaded.identity(), SET, "sha");
    }
}
