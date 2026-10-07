package com.mio.ai.qa.correction;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[QA] 저장된 판정 항목으로 통과 여부 재집계 (LLM 호출 없음)")
class CorrectionReaggregateTest {

    private static final CorrectionEvalCase CONTROL = new CorrectionEvalCase("D-N-01", "control", null, false,
            List.of(new CorrectionEvalCase.Turn("USER", "아니 진짜 힘들어요")));
    private static final CorrectionEvalSet SET = new CorrectionEvalSet("correction-dev-v1", List.of(CONTROL));

    private static CorrectionEvalRunner.ScoredSample sample(boolean unnatural, boolean spurious, int repeat) {
        Map<String, Boolean> items = Map.of(CorrectionVerdict.SPURIOUS_ACKNOWLEDGMENT, spurious,
                CorrectionVerdict.UNNATURAL_RESTATEMENT, unnatural, CorrectionVerdict.AVOIDED_REQUESTED_ADVICE, false);
        // 저장된 통과 여부는 옛 규칙(어색한 재진술도 통과 조건)으로 계산된 값이다
        CorrectionVerdict old = new CorrectionVerdict("D-N-01", false, items, Map.of(), !unnatural && !spurious, false);
        return new CorrectionEvalRunner.ScoredSample("NORMAL", new CorrectionGenerationRunner.Sample("D-N-01",
                CorrectionPromptArm.WITH_CORRECTION_BLOCK, repeat, "오늘 정말 힘드셨군요.", false, false), old,
                CorrectionResponseShape.of("오늘 정말 힘드셨군요."));
    }

    @Test
    @DisplayName("대조군은 어색한 재진술만으로 실패하지 않고, 불필요한 인정은 여전히 실패시킨다")
    void unnaturalRestatementAloneDoesNotFailAControl(@TempDir Path dir) {
        CorrectionRunIdentity identity = CorrectionRunIdentity.stamp("correction-dev-v1", "sha", 2,
                List.of("NORMAL"), List.of("WITH_CORRECTION_BLOCK"), "gpt-4o", "gpt-4o-mini");
        Path archive = CorrectionRunArchive.write(new CorrectionEvalRunner.Result(identity, SET,
                List.of(sample(true, false, 0), sample(false, true, 1))), dir);

        List<CorrectionEvalRunner.ScoredSample> rescored = CorrectionRejudge.reaggregate(
                CorrectionRejudge.load(archive), SET);

        assertThat(rescored.get(0).verdict().passed()).as("어색한 재진술만 있는 경우").isTrue();
        assertThat(rescored.get(0).verdict().items()).containsEntry(CorrectionVerdict.UNNATURAL_RESTATEMENT, true);
        assertThat(rescored.get(1).verdict().passed()).as("불필요한 인정이 있는 경우").isFalse();
    }

    @Test
    @DisplayName("[진입점] 실행 결과를 현재 통과 규칙으로 다시 집계해 리포트를 출력한다")
    void reaggregateFromEnvironment() throws Exception {
        String archive = System.getenv(CorrectionRejudgeLlmTest.ARCHIVE_FILE_ENV);
        String setPath = System.getenv(CorrectionRunConfig.SET_PATH);
        Assumptions.assumeTrue(archive != null && setPath != null && System.getenv("MIO_EVAL_CORRECTION_REAGGREGATE") != null,
                "재집계 환경 변수가 없다 — 건너뛴다");
        CorrectionEvalSet set = CorrectionEvalSet.fromPath(Path.of(setPath));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of(setPath))));
        CorrectionRejudge.Loaded loaded = CorrectionRejudge.load(Path.of(archive));
        CorrectionRejudge.requireRejudgeable(loaded.identity(), set, sha);

        CorrectionRunIdentity old = loaded.identity();
        CorrectionRunIdentity identity = new CorrectionRunIdentity(UUID.randomUUID().toString(), Instant.now(),
                old.setVersion(), old.setSha256(), old.repeats(), old.modes(), old.arms(), old.generationModel(),
                old.judgeModel(), CorrectionRubric.VERSION);
        System.out.printf("%n[correction-reaggregate] 원본 %s (기준표 %s) → 통과 규칙 %s%n", old.runId(),
                old.rubricVersion(), CorrectionRubric.VERSION);
        System.out.print(CorrectionReport.render(new CorrectionEvalRunner.Result(identity, set,
                CorrectionRejudge.reaggregate(loaded, set)), null, CorrectionReport.Detail.SUMMARY));
    }
}
