package com.mio.ai.qa.correction;

import com.mio.ai.qa.correction.CorrectionEvalRunner.ScoredSample;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[QA] 채점기 사람 검증 도구 (네트워크 없음)")
class CorrectionCalibrationTest {

    private static final CorrectionEvalCase FACT = new CorrectionEvalCase("D-F-01", "fact", "strong", true, List.of(
            new CorrectionEvalCase.Turn("USER", "바빠요"), new CorrectionEvalCase.Turn("ASSISTANT", "사치 같으신가 봐요"),
            new CorrectionEvalCase.Turn("USER", "시간이 없어요")));
    private static final CorrectionEvalCase CONTROL = new CorrectionEvalCase("D-N-01", "control", null, false,
            List.of(new CorrectionEvalCase.Turn("USER", "아니 진짜 힘들어요, 오늘")));
    private static final CorrectionEvalSet SET = new CorrectionEvalSet("correction-dev-v1", List.of(FACT, CONTROL));

    private static ScoredSample sample(CorrectionEvalCase evalCase, int repeat, boolean flagged, String response) {
        Map<String, Boolean> items = new LinkedHashMap<>();
        if (evalCase.correction()) {
            items.put(CorrectionVerdict.ACKNOWLEDGED, true);
            items.put(CorrectionVerdict.REUSED_FRAME, false);
            items.put(CorrectionVerdict.REPEATED_ADVICE, false);
            items.put(CorrectionVerdict.NEW_GENERIC_ADVICE, flagged);
            items.put(CorrectionVerdict.RESTATED, true);
            items.put(CorrectionVerdict.INVENTED_FACTS, false);
        } else {
            items.put(CorrectionVerdict.SPURIOUS_ACKNOWLEDGMENT, false);
            items.put(CorrectionVerdict.UNNATURAL_RESTATEMENT, flagged);
            items.put(CorrectionVerdict.AVOIDED_REQUESTED_ADVICE, false);
        }
        CorrectionVerdict verdict = new CorrectionVerdict(evalCase.id(), evalCase.correction(), items, Map.of(),
                !flagged, false);
        return new ScoredSample("NORMAL", new CorrectionGenerationRunner.Sample(evalCase.id(),
                CorrectionPromptArm.WITH_CORRECTION_BLOCK, repeat, response, false, false), verdict,
                CorrectionResponseShape.of("응답."));
    }

    private static Path archive(Path dir, String setVersion) {
        List<ScoredSample> samples = new ArrayList<>();
        for (int r = 0; r < 6; r++) {
            samples.add(sample(FACT, r, r % 2 == 0, "정정 응답 " + r + ", 쉼표 포함"));
            samples.add(sample(CONTROL, r, r % 2 == 0, "대조군 응답 " + r));
        }
        CorrectionRunIdentity identity = CorrectionRunIdentity.stamp(setVersion, "sha", 6, List.of("NORMAL"),
                List.of("WITH_CORRECTION_BLOCK"), "gpt-4o", "gpt-4o-mini");
        return CorrectionRunArchive.write(new CorrectionEvalRunner.Result(identity, SET, samples), dir);
    }

    @Test
    @DisplayName("채점표에는 채점기 판정·팔·모드가 없고, 열쇠에만 있다")
    void sheetHidesJudgeVerdictsAndKeyHoldsThem(@TempDir Path dir) throws IOException {
        CorrectionCalibration.Exported exported = CorrectionCalibration.export(archive(dir, "correction-dev-v1"),
                SET, 8, dir.resolve("out"));

        String sheet = Files.readString(exported.sheet(), StandardCharsets.UTF_8);
        String key = Files.readString(exported.key(), StandardCharsets.UTF_8);
        assertThat(exported.size()).isEqualTo(8);
        assertThat(sheet).startsWith("﻿").contains("sheet_id", "구분", "대화", "응답", "어색한재진술");
        assertThat(sheet).doesNotContain("NORMAL", "WITH_CORRECTION_BLOCK", "BASELINE", "judgeItems", "SUPPORTIVE");
        assertThat(key).contains("judgeItems", "WITH_CORRECTION_BLOCK", "NORMAL", "\"S-01\"");
        assertThat(Files.readString(exported.guide(), StandardCharsets.UTF_8)).contains("어색한재진술", "요청된조언회피");
    }

    @Test
    @DisplayName("표본은 채점기의 예/아니오 판정을 모두 포함하고, 같은 입력이면 항상 같다")
    void sampleCoversBothJudgeOutcomesDeterministically(@TempDir Path dir) {
        Path archive = archive(dir, "correction-dev-v1");
        CorrectionRejudge.Loaded loaded = CorrectionRejudge.load(archive);
        Map<String, CorrectionEvalCase> cases = Map.of(FACT.id(), FACT, CONTROL.id(), CONTROL);

        List<CorrectionRejudge.Archived> first = CorrectionCalibration.select(loaded.samples(), cases, 8, "s");
        List<CorrectionRejudge.Archived> second = CorrectionCalibration.select(loaded.samples(), cases, 8, "s");

        assertThat(first).hasSize(8);
        assertThat(first.stream().map(a -> a.sample().caseId() + "#" + a.sample().repeat()).toList())
                .isEqualTo(second.stream().map(a -> a.sample().caseId() + "#" + a.sample().repeat()).toList());
        assertThat(first).anyMatch(a -> Boolean.TRUE.equals(a.oldItems().get(CorrectionVerdict.UNNATURAL_RESTATEMENT)));
        assertThat(first).anyMatch(a -> Boolean.FALSE.equals(a.oldItems().get(CorrectionVerdict.UNNATURAL_RESTATEMENT)));
        assertThat(first).anyMatch(a -> Boolean.TRUE.equals(a.oldItems().get(CorrectionVerdict.NEW_GENERIC_ADVICE)));
        assertThat(first).anyMatch(a -> Boolean.FALSE.equals(a.oldItems().get(CorrectionVerdict.NEW_GENERIC_ADVICE)));
    }

    @Test
    @DisplayName("대화가 충분하면 같은 대화가 두 번 나오지 않는다 (첫 채점표에서 4개 대화가 겹쳤던 문제)")
    void selectedSamplesAreDistinctConversationsWhenPossible(@TempDir Path dir) {
        List<CorrectionEvalCase> cases = new ArrayList<>();
        List<ScoredSample> samples = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            CorrectionEvalCase fact = new CorrectionEvalCase("E-" + i, "fact", "strong", true, List.of(
                    new CorrectionEvalCase.Turn("USER", "a" + i), new CorrectionEvalCase.Turn("ASSISTANT", "b" + i),
                    new CorrectionEvalCase.Turn("USER", "c" + i)));
            CorrectionEvalCase control = new CorrectionEvalCase("N-" + i, "control", null, false,
                    List.of(new CorrectionEvalCase.Turn("USER", "d" + i)));
            cases.add(fact);
            cases.add(control);
            for (int r = 0; r < 4; r++) {
                samples.add(sample(fact, r, (i + r) % 2 == 0, "정정 응답 " + i + "-" + r));
                samples.add(sample(control, r, (i + r) % 2 == 0, "대조군 응답 " + i + "-" + r));
            }
        }
        CorrectionEvalSet set = new CorrectionEvalSet("correction-dev-v1", cases);
        CorrectionRunIdentity identity = CorrectionRunIdentity.stamp("correction-dev-v1", "sha", 4, List.of("NORMAL"),
                List.of("WITH_CORRECTION_BLOCK"), "gpt-4o", "gpt-4o-mini");
        Path archive = CorrectionRunArchive.write(new CorrectionEvalRunner.Result(identity, set, samples), dir);
        Map<String, CorrectionEvalCase> byId = new LinkedHashMap<>();
        cases.forEach(c -> byId.put(c.id(), c));

        List<CorrectionRejudge.Archived> picked = CorrectionCalibration.select(
                CorrectionRejudge.load(archive).samples(), byId, 12, "salt");

        assertThat(picked).hasSize(12);
        assertThat(picked.stream().map(a -> a.sample().caseId()).distinct().count()).isEqualTo(12);
    }

    @Test
    @DisplayName("평가용 세트의 결과로는 내보내지 않는다 (채점기 조정 근거로 쓰지 않는다)")
    void refusesEvaluationSetResults(@TempDir Path dir) {
        Path archive = archive(dir, "correction-eval-v1");

        assertThatThrownBy(() -> CorrectionCalibration.export(archive, SET, 8, dir.resolve("out")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("튜닝용");
    }

    @Test
    @DisplayName("사람이 채운 채점표와 열쇠를 비교해 항목별 일치율·채점기 과잉/놓침을 센다")
    void comparesHumanSheetWithJudge(@TempDir Path dir) throws IOException {
        CorrectionCalibration.Exported exported = CorrectionCalibration.export(archive(dir, "correction-dev-v1"),
                SET, 8, dir.resolve("out"));
        List<List<String>> rows = CorrectionCalibration.parseCsv(Files.readString(exported.sheet(), StandardCharsets.UTF_8));
        List<String> header = rows.get(0);
        int correctionCol = header.indexOf("새조언");
        int controlCol = header.indexOf("어색한재진술");
        StringBuilder filled = new StringBuilder("﻿").append(CorrectionCalibration.csvRow(header));
        for (List<String> row : rows.subList(1, rows.size())) {
            List<String> r = new ArrayList<>(row);
            // 사람은 항상 "아니오" 로 채점한다 → 채점기가 "예"라고 한 것은 전부 '채점기 과잉'이 된다
            if (row.get(1).equals("정정")) {
                r.set(correctionCol, "N");
            } else {
                r.set(controlCol, "n");
            }
            filled.append(CorrectionCalibration.csvRow(r));
        }
        Path filledFile = dir.resolve("filled.csv");
        Files.writeString(filledFile, filled.toString(), StandardCharsets.UTF_8);

        String report = CorrectionCalibration.compare(filledFile, exported.key());

        assertThat(report).contains("채점된 표본 8개", "새조언:", "어색한재진술:", "채점기 과잉");
        assertThat(report).contains("채점기 아니오").doesNotContain("Infinity");
    }

    @Test
    @DisplayName("Y/N 을 읽고, 알 수 없는 값·열쇠에 없는 id 는 거부한다")
    void parsesYesNoAndRejectsBadValues(@TempDir Path dir) throws IOException {
        assertThat(CorrectionCalibration.parseYesNo(" Y ")).isTrue();
        assertThat(CorrectionCalibration.parseYesNo("예")).isTrue();
        assertThat(CorrectionCalibration.parseYesNo("아니오")).isFalse();
        assertThat(CorrectionCalibration.parseYesNo("x")).isFalse();
        assertThat(CorrectionCalibration.parseYesNo("글쎄")).isNull();

        CorrectionCalibration.Exported exported = CorrectionCalibration.export(archive(dir, "correction-dev-v1"),
                SET, 4, dir.resolve("out"));
        Path bad = dir.resolve("bad.csv");
        // 정정 행이면 새조언, 대조군 행이면 어색한재진술 칸이 해당하므로, S-01 이 어느 쪽이든 두 칸을 다 채운다
        Files.writeString(bad, "sheet_id,새조언,어색한재진술\r\nS-01,글쎄,글쎄\r\n", StandardCharsets.UTF_8);
        Path unknown = dir.resolve("unknown.csv");
        Files.writeString(unknown, "sheet_id,새조언\r\nS-99,Y\r\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CorrectionCalibration.compare(bad, exported.key()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorrectionCalibration.compare(unknown, exported.key()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("S-99");
    }

    @Test
    @DisplayName("CSV 는 쉼표·따옴표·줄바꿈이 든 칸을 왕복해도 그대로다")
    void csvRoundTrip() {
        List<String> cells = List.of("a,b", "따옴표 \"안\"", "두 줄\n입니다", "");

        List<List<String>> parsed = CorrectionCalibration.parseCsv(CorrectionCalibration.csvRow(cells));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0)).containsExactlyElementsOf(cells);
    }

    // ── 실제 파일용 진입점 (환경 변수가 없으면 건너뜀, 네트워크 없음) ──

    @Test
    @DisplayName("[진입점] 실행 결과에서 채점표를 내보낸다 (환경 변수: MIO_EVAL_CORRECTION_ARCHIVE_FILE, _SET_PATH, _CALIBRATION_SIZE)")
    void exportFromEnvironment() {
        String archive = System.getenv("MIO_EVAL_CORRECTION_ARCHIVE_FILE");
        String setPath = System.getenv(CorrectionRunConfig.SET_PATH);
        String exportDir = System.getenv("MIO_EVAL_CORRECTION_CALIBRATION_EXPORT");
        Assumptions.assumeTrue(archive != null && setPath != null && exportDir != null,
                "채점표 내보내기 환경 변수가 없다 — 건너뛴다");
        String size = System.getenv("MIO_EVAL_CORRECTION_CALIBRATION_SIZE");

        CorrectionCalibration.Exported exported = CorrectionCalibration.export(Path.of(archive),
                CorrectionEvalSet.fromPath(Path.of(setPath)), size == null ? 30 : Integer.parseInt(size.trim()),
                Path.of(exportDir));

        System.out.printf("%n[correction-calibration] 채점표 %d건 저장%n  채점표: %s%n  열쇠(채점 전에 열지 말 것): %s%n  안내: %s%n",
                exported.size(), exported.sheet(), exported.key(), exported.guide());
    }

    @Test
    @DisplayName("[진입점] 사람이 채운 채점표를 열쇠와 비교한다 (환경 변수: MIO_EVAL_CORRECTION_CALIBRATION_SHEET, _KEY)")
    void compareFromEnvironment() {
        String sheet = System.getenv("MIO_EVAL_CORRECTION_CALIBRATION_SHEET");
        String key = System.getenv("MIO_EVAL_CORRECTION_CALIBRATION_KEY");
        Assumptions.assumeTrue(sheet != null && key != null, "채점표 비교 환경 변수가 없다 — 건너뛴다");

        System.out.print(CorrectionCalibration.compare(Path.of(sheet), Path.of(key)));
    }
}
