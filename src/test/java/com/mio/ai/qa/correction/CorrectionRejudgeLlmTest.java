package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 저장된 실행 결과를 <b>채점만 다시 한다</b> (이슈 #555). 채점 호출만 하므로 저렴하다(파일럿 80건 약 $0.013).
 * 기준표를 고친 뒤 "같은 응답에서 판정이 어떻게 바뀌었는지"를 볼 때 쓴다. 튜닝용 세트의 결과에만 쓴다.
 *
 * <pre>{@code
 * OPENAI_API_KEY=sk-... \
 * MIO_EVAL_CORRECTION_ARCHIVE_FILE=eval-private/runs/<run_id>.json \
 * MIO_EVAL_CORRECTION_SET_PATH=src/test/resources/eval/correction/correction-dev-v1.json \
 * MIO_EVAL_CORRECTION_MAX_KRW=100000 \
 *   ./gradlew test -PllmTests --tests "com.mio.ai.qa.correction.CorrectionRejudgeLlmTest" -i
 * }</pre>
 */
@Tag("llm-integration")
@DisplayName("[QA] 저장된 응답 재채점 (실 LLM, 채점 호출만)")
class CorrectionRejudgeLlmTest {

    static final String ARCHIVE_FILE_ENV = "MIO_EVAL_CORRECTION_ARCHIVE_FILE";

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    @DisplayName("같은 응답을 현재 기준표로 다시 채점하고 이전 판정과의 차이를 출력한다")
    void rejudge() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && apiKey.startsWith("sk-"), "OPENAI_API_KEY 가 없다 — 재채점을 건너뛴다");
        Assumptions.assumeTrue(System.getenv(ARCHIVE_FILE_ENV) != null, ARCHIVE_FILE_ENV + " 가 없다 — 재채점을 건너뛴다");
        Assumptions.assumeTrue(System.getenv(CorrectionRunConfig.SET_PATH) != null,
                CorrectionRunConfig.SET_PATH + " 가 없다 — 재채점을 건너뛴다");

        CorrectionRunConfig config = CorrectionRunConfig.fromEnv(System::getenv);
        Path setPath = config.setPath();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(setPath)));
        CorrectionEvalSet set = CorrectionEvalSet.fromPath(setPath);
        CorrectionRejudge.Loaded loaded = CorrectionRejudge.load(Path.of(System.getenv(ARCHIVE_FILE_ENV)));
        CorrectionRejudge.requireRejudgeable(loaded.identity(), set, sha256);

        CorrectionCostLedger ledger = new CorrectionCostLedger();
        LlmClient client = CorrectionClients.real(apiKey, ledger);
        String judgeModel = CorrectionJudge.configuredModel();
        int votes = CorrectionJudge.configuredVotes();
        CorrectionJudge judge = new CorrectionJudge(client, judgeModel, CorrectionEvalRunner.JUDGE_COMPONENT, votes)
                .withRetries(3, 1500);

        List<CorrectionEvalRunner.ScoredSample> rescored = CorrectionRejudge.rejudge(loaded, set, judge,
                () -> config.budget().exceeded(ledger));

        CorrectionRunIdentity old = loaded.identity();
        CorrectionRunIdentity identity = new CorrectionRunIdentity(UUID.randomUUID().toString(), Instant.now(),
                old.setVersion(), old.setSha256(), old.repeats(), old.modes(), old.arms(), old.generationModel(),
                CorrectionMeasurementLlmTest.judgeLabel(judgeModel, votes), CorrectionRubric.VERSION);
        CorrectionEvalRunner.Result result = new CorrectionEvalRunner.Result(identity, set, rescored);

        System.out.printf("%n[correction-rejudge] 원본 %s (기준표 %s) → 현재 기준표 %s · 채점 모델 %s%n",
                old.runId(), old.rubricVersion(), CorrectionRubric.VERSION, judgeModel);
        System.out.print(CorrectionRejudge.describeChanges(loaded, rescored));
        System.out.print(CorrectionReport.render(result, ledger, CorrectionReport.Detail.SUMMARY));
        Path archived = CorrectionRunArchive.write(result, config.archiveDir());
        System.out.printf("%n[correction-rejudge] 재채점 결과 저장(로컬, 커밋 금지): %s%n", archived);
    }
}
