package com.mio.ai.qa;

import com.mio.ai.qa.LockedEvalContaminationScanner.CaseText;
import com.mio.ai.qa.LockedEvalContaminationScanner.Hit;
import com.mio.ai.qa.LockedEvalContaminationScanner.Probes;
import com.mio.ai.qa.LockedEvalContaminationScanner.Rule;
import com.mio.ai.qa.LockedEvalSet.Expected;
import com.mio.ai.qa.LockedEvalSet.LockedCase;
import com.mio.ai.qa.LockedEvalSet.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 오염 가드·무결성 게이트의 자기검증 (이슈 #454 리뷰 HIGH-2).
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>{@link LockedEvalContaminationGuardTest} 와 {@link LockedEvalSetIntegrityTest} 의 단언은
 * 전부 "히트 없음" · "차이 없음" 이다. 코퍼스가 깨끗한 한, 그 통과는 <b>탐지 로직이 고장 나
 * 아무것도 찾지 못하는 상태</b>와 구분되지 않는다. 정규화나 스캔 루프를 리팩터링하다 실수로
 * 무력화해도 초록불이 유지된다. 실제로 한 번 동작하는 것을 손으로 확인했다는 기록은 회귀를
 * 막지 못한다 — 그래서 심어 둔 가짜 유출을 매 빌드마다 잡게 한다.
 *
 * <h2>같은 코드 경로를 쓴다</h2>
 *
 * <p>자기검증이 로직의 <b>복사본</b>을 검사하면 아무 의미가 없다. 그래서 여기서도 실제 스캔과
 * 동일한 진입점만 쓴다.
 *
 * <ul>
 *   <li>오염 스캔: {@link LockedEvalContaminationScanner#scan(Path, Probes)} —
 *       실제 가드가 저장소 루트에 대해 부르는 그 메서드를 {@link TempDir} 루트에 대해 부른다.
 *       조각 생성도 {@link LockedEvalContaminationScanner#probesFrom(List)} 로 같다.</li>
 *   <li>매니페스트 대조: {@link LockedEvalManifest#diff(List)} — 무결성 테스트가 쓰는 그
 *       메서드에 메모리에서 변조한 케이스 목록을 먹인다. <b>커밋된 파일은 건드리지 않는다.</b></li>
 * </ul>
 *
 * <h2>합성 케이스를 쓴다</h2>
 *
 * <p>실제 잠금 케이스 본문을 이 파일에 적으면 그 순간 이 파일이 유출이 되고 실제 가드가
 * 실패한다(의도한 동작이다). 그래서 잠금 세트에 없는 합성 문장을 만들어 쓴다. 합성 문장은
 * 실제 케이스와 근사 중복이 아니어야 하며, 그 사실도 아래에서 함께 단언한다.
 */
@DisplayName("[QA] 잠금 가드 자기검증 — 심어 둔 유출을 실제로 잡는가")
class LockedEvalContaminationSelfTest {

    private static final String SYNTHETIC_ID = "SELFTEST-SYNTHETIC-001";

    /** 16자 창 규칙 대상 (정규화 길이 ≥ 16). 잠금 세트에 없는 합성 문장이다. */
    private static final String LONG_TEXT =
            "자기검증용 합성 문장이며 실제 잠금 케이스가 아닙니다 라고 적어 둔 표본입니다";

    /** 9자 창 규칙 대상 (정규화 길이 9~15). */
    private static final String MID_TEXT = "합성표본 중간 길이 문장임";

    /** 유사도 규칙 대상 (정규화 길이 8). */
    private static final String SHORT_TEXT = "합성표본 짧은쪽임";

    // ── 오염 스캔 자기검증 ──────────────────────────────────────────

    @Test
    @DisplayName("심어 둔 16자 조각을 실제 스캔 경로가 잡아낸다")
    void detectsPlantedLongFragment(@TempDir Path root) {
        String normalized = LockedEvalSet.normalize(LONG_TEXT);
        String planted = normalized.substring(4, 4 + 18);
        plant(root, "src/main/java/com/example/PromptDraft.java",
                "class PromptDraft { static final String FEW_SHOT = \"" + planted + "\"; }");

        List<Hit> hits = LockedEvalContaminationScanner.scan(root, syntheticProbes());

        assertThat(hits)
                .as("가드가 심어 둔 유출을 잡지 못했다 — 탐지가 죽었다는 뜻이다")
                .isNotEmpty();
        assertThat(hits).anySatisfy(h -> {
            assertThat(h.caseId()).isEqualTo(SYNTHETIC_ID);
            assertThat(h.rule()).isEqualTo(Rule.LONG_FRAGMENT);
        });
    }

    @Test
    @DisplayName("8~15자 케이스를 통째로 옮겨 적으면 9자 창 규칙이 잡는다")
    void detectsPlantedShortFragment(@TempDir Path root) {
        plant(root, "docs/eval/draft-keywords.md",
                "- 후보 키워드: " + MID_TEXT + "\n- 그 밖의 메모\n");

        List<Hit> hits = LockedEvalContaminationScanner.scan(root, syntheticProbes());

        assertThat(hits).anySatisfy(h -> {
            assertThat(h.caseId()).isEqualTo(SYNTHETIC_ID);
            assertThat(h.rule()).isEqualTo(Rule.SHORT_FRAGMENT);
        });
    }

    /**
     * 리뷰 HIGH-1 이 지적한 우회 그대로를 재현한다. 이전 구현은 8~15자 구간을 전문 일치로만
     * 검사했으므로 글자 하나만 달라도 통과했다.
     */
    @Test
    @DisplayName("8~15자 케이스에서 글자 하나를 뺀 옮겨 적기도 잡는다 — 전문 일치만으로는 못 잡던 우회")
    void detectsOneCharacterParaphraseOfShortCase(@TempDir Path root) {
        String normalized = LockedEvalSet.normalize(MID_TEXT);
        String paraphrased = normalized.substring(0, normalized.length() - 1);
        plant(root, "src/main/resources/prompt/draft.yml", "few_shot: \"" + paraphrased + "\"\n");

        List<Hit> hits = LockedEvalContaminationScanner.scan(root, syntheticProbes());

        assertThat(hits)
                .as("종결어미 한 글자를 떼면 탐지를 우회하던 구멍이 다시 열렸다")
                .anySatisfy(h -> assertThat(h.caseId()).isEqualTo(SYNTHETIC_ID));
    }

    @Test
    @DisplayName("정규화 길이 8자 케이스의 한 글자 변형도 유사도 규칙이 잡는다")
    void detectsParaphraseOfEightCharacterCase(@TempDir Path root) {
        String normalized = LockedEvalSet.normalize(SHORT_TEXT);
        String paraphrased = normalized.substring(0, normalized.length() - 1);
        plant(root, "scripts/eval/draft.py", "KEYWORDS = ['" + paraphrased + "']\n");

        List<Hit> hits = LockedEvalContaminationScanner.scan(root, syntheticProbes());

        assertThat(hits).anySatisfy(h -> {
            assertThat(h.caseId()).isEqualTo(SYNTHETIC_ID);
            assertThat(h.rule()).isEqualTo(Rule.SHORT_SIMILARITY);
        });
    }

    @Test
    @DisplayName("제로폭·양방향 제어문자를 끼워 넣어도 잡는다 — 자모기호우회와 같은 부류의 회피")
    void detectsFragmentHiddenByZeroWidthCharacters(@TempDir Path root) {
        String normalized = LockedEvalSet.normalize(LONG_TEXT);
        StringBuilder obfuscated = new StringBuilder();
        normalized.substring(0, 20).chars()
                .forEach(ch -> obfuscated.append((char) ch).append('​'));
        plant(root, "src/main/java/com/example/Hidden.java",
                "// " + obfuscated + "‮\n");

        List<Hit> hits = LockedEvalContaminationScanner.scan(root, syntheticProbes());

        assertThat(hits)
                .as("정규화가 제로폭 문자를 지우지 않으면 조각 연속성이 끊겨 그대로 통과한다")
                .anySatisfy(h -> assertThat(h.caseId()).isEqualTo(SYNTHETIC_ID));
    }

    @Test
    @DisplayName("깨끗한 트리에서는 히트가 없다 — 무엇이든 잡아내는 가드도 가드가 아니다")
    void cleanTreeProducesNoHit(@TempDir Path root) {
        plant(root, "src/main/java/com/example/Clean.java",
                "class Clean { static final String MESSAGE = \"오늘 회의는 세 시에 시작합니다\"; }");
        plant(root, "docs/notes.md", "# 메모\n\n배포 절차를 정리한 문서다.\n");

        List<Hit> hits = LockedEvalContaminationScanner.scan(root, syntheticProbes());

        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("스캔 대상이 0건이면 통과가 아니라 실패다 (fail-closed)")
    void emptyScanRootFailsClosed(@TempDir Path root) {
        assertThatThrownBy(() -> LockedEvalContaminationScanner.scan(root, syntheticProbes()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("스캔 대상");
    }

    @Test
    @DisplayName("합성 표본은 실제 잠금 케이스와 근사 중복이 아니다 — 자기검증이 실제 세트를 오염시키지 않는다")
    void syntheticSamplesDoNotResembleRealCases() {
        List<String> texts = List.of(LONG_TEXT, MID_TEXT, SHORT_TEXT);
        double worst = 0.0;
        for (String text : texts) {
            String a = LockedEvalSet.normalize(text);
            for (LockedCase c : LockedEvalSet.CASES) {
                for (Turn t : c.userTurns()) {
                    worst = Math.max(worst,
                            LockedEvalSet.similarity(a, LockedEvalSet.normalize(t.text())));
                }
            }
        }
        assertThat(worst)
                .as("합성 표본이 실제 케이스와 닮으면 이 파일 자체가 유출 경보를 부른다")
                .isLessThan(LockedEvalContaminationScanner.SIMILARITY_THRESHOLD);
    }

    // ── 매니페스트 자기검증 ─────────────────────────────────────────

    @Test
    @DisplayName("케이스를 메모리에서 한 글자 바꾸면 매니페스트 대조가 그 케이스를 지목한다")
    void mutatedCaseFailsHashCheck() {
        List<LockedCase> mutated = new ArrayList<>(LockedEvalSet.CASES);
        LockedCase original = mutated.get(0);
        LockedCase tampered = new LockedCase(original.id(), original.subgroup(), original.axis(),
                original.pairKey(), original.turns(), original.expected(),
                original.rationale() + ".");
        mutated.set(0, tampered);

        assertThat(LockedEvalSet.caseSha256(tampered))
                .as("정규 문자열이 달라졌는데 해시가 같으면 잠금이 아니다")
                .isNotEqualTo(LockedEvalSet.caseSha256(original));
        assertThat(LockedEvalManifest.diff(mutated))
                .as("무결성 게이트가 쓰는 대조 로직이 변조를 지목해야 한다")
                .contains("변경됨: " + original.id());
        assertThat(LockedEvalManifest.diff(LockedEvalSet.CASES))
                .as("변조하지 않은 목록은 깨끗해야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("라벨만 뒤집어도 케이스 해시가 달라진다 — 게이트 통과용 조용한 수정이 잡힌다")
    void flippedLabelFailsHashCheck() {
        LockedCase original = LockedEvalSet.CASES.stream()
                .filter(c -> "HARD_CRISIS".equals(c.expected().safetyTruth()))
                .findFirst()
                .orElseThrow();
        Expected flipped = new Expected("CLEAR", original.expected().exposure(),
                original.expected().responseAct(), original.expected().maxQuestions(),
                original.expected().forbiddenElements());
        LockedCase tampered = new LockedCase(original.id(), original.subgroup(), original.axis(),
                original.pairKey(), original.turns(), flipped, original.rationale());

        List<LockedCase> mutated = new ArrayList<>(LockedEvalSet.CASES);
        mutated.set(LockedEvalSet.CASES.indexOf(original), tampered);

        assertThat(LockedEvalManifest.diff(mutated)).contains("변경됨: " + original.id());
    }

    @Test
    @DisplayName("케이스를 지우거나 더하면 매니페스트 대조가 그대로 보고한다")
    void addedOrRemovedCaseIsReported() {
        List<LockedCase> removed = new ArrayList<>(LockedEvalSet.CASES);
        LockedCase dropped = removed.remove(0);
        assertThat(LockedEvalManifest.diff(removed)).contains("삭제됨: " + dropped.id());

        List<LockedCase> added = new ArrayList<>(LockedEvalSet.CASES);
        added.add(new LockedCase("LOCK-SELFTEST-ADDED", dropped.subgroup(), dropped.axis(),
                "", dropped.turns(), dropped.expected(), dropped.rationale()));
        assertThat(LockedEvalManifest.diff(added)).contains("추가됨: LOCK-SELFTEST-ADDED");
    }

    // ── 보조 ────────────────────────────────────────────────────────

    private static Probes syntheticProbes() {
        return LockedEvalContaminationScanner.probesFrom(List.of(
                new CaseText(SYNTHETIC_ID, LockedEvalSet.normalize(LONG_TEXT)),
                new CaseText(SYNTHETIC_ID, LockedEvalSet.normalize(MID_TEXT)),
                new CaseText(SYNTHETIC_ID, LockedEvalSet.normalize(SHORT_TEXT))));
    }

    private static void plant(Path root, String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("자기검증 픽스처를 만들지 못했다: " + relative, e);
        }
    }
}
