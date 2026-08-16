package com.mio.ai.qa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manifest 로 남긴 실행 기록이 실제 파일에 무엇으로 찍히는지 확인한다 (로드맵 §10.5 / P0-8).
 *
 * <p>{@link EvalRunManifestTest} 는 manifest 객체까지만 본다. 그런데 A~E 셀 비교에서 실제로
 * 읽히는 것은 객체가 아니라 {@code docs/eval/runs/} 에 남은 파일이다. 검증된 manifest 와
 * 기록물이 어긋나는 결함은 이 구간에서만 드러나므로, 종단으로 한 번 더 본다.
 */
class EvalRunArchiveTest {

    private static final String ARCHIVE_DIR_PROPERTY = "mio.eval.archiveDir";

    @AfterEach
    void clearArchiveDir() {
        System.clearProperty(ARCHIVE_DIR_PROPERTY);
    }

    @Test
    @DisplayName("manifest 의 모든 출처 항목이 기록 파일에 그대로 남는다")
    void manifestProvenanceReachesTheFile(@TempDir Path archiveDir) throws IOException {
        System.setProperty(ARCHIVE_DIR_PROPERTY, archiveDir.toString());

        Path file = EvalRunArchive.write("archive-seam", manifest(), "본문 리포트");

        assertThat(file).exists();
        assertThat(file.getFileName().toString()).endsWith("-archive-seam.md");

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content)
                .contains("| `run_at` |")
                .contains("| `code_commit` |")
                .contains("| `scope` | rule+routing |")
                .contains("| `dataset_split` | dev_gold |")
                .contains("| `model.input_judge` | gpt-4o-mini |")
                .contains("| `prompt_version` | " + EvalRunManifest.UNVERSIONED + " |")
                .contains("| `pricing_as_of` | " + EvalRunManifest.PRICING_DATE_UNRECORDED + " |")
                .contains("| `random_seed` | " + EvalRunManifest.NO_SEED + " |")
                .contains("| `gate_false_negative_rate` | <= 20.0% |")
                .contains("| `judge_calls` | 112 |")
                .contains("본문 리포트");
    }

    /**
     * 사람이 여러 실행 기록을 나란히 놓고 읽으려면 같은 항목이 같은 자리에 있어야 한다.
     * 재현 명령은 복사해 가는 값이라 항상 마지막 행이다.
     */
    @Test
    @DisplayName("기록 파일의 행 순서는 manifest 가 정한 순서를 따른다")
    void archiveRowOrderFollowsTheManifest(@TempDir Path archiveDir) throws IOException {
        System.setProperty(ARCHIVE_DIR_PROPERTY, archiveDir.toString());

        Path file = EvalRunArchive.write("archive-order", manifest(), "본문");

        List<String> keys = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("| `"))
                .map(line -> line.substring(3, line.indexOf('`', 3)))
                .toList();

        assertThat(keys).startsWith("run_at", "code_commit", "scope", "cell", "dataset");
        assertThat(keys).endsWith("command");
        assertThat(keys.indexOf("gate_false_negative_rate")).isLessThan(keys.indexOf("judge_calls"));
    }

    private EvalRunManifest manifest() {
        return new EvalRunManifest(
                "rule+routing",
                EvalRunManifest.BASELINE_CELL,
                "crisis-corpus-v1",
                "dev_gold",
                172,
                "docs/eval/crisis-corpus-labeling-guide.md",
                Map.of("input_judge", "gpt-4o-mini"),
                EvalRunManifest.UNVERSIONED,
                "v2.0-phase2",
                EvalRunManifest.PRICING_DATE_UNRECORDED,
                EvalRunManifest.NO_SEED,
                "./gradlew test --tests \"com.mio.ai.qa.CrisisDetectionCorpusQaTest\"",
                Map.of("false_negative_rate", "<= 20.0%"),
                Map.of("judge_calls", "112"));
    }
}
