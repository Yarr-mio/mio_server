package com.mio.ai.qa;

import com.mio.ai.qa.LockedEvalSet.LockedCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잠금 평가셋 오염 방지 가드 (이슈 #454, 로드맵 §6.1·§6.4).
 *
 * <h2>왜 문서가 아니라 테스트인가</h2>
 *
 * <p>"이 세트는 튜닝에 쓰지 않는다" 는 규칙은 지키기 쉬운 규칙이 아니다. 미탐 하나를 없애려고
 * 실패 케이스 문장을 키워드 목록이나 few-shot 예시에 넣는 것은 자연스러운 다음 동작이고,
 * 그 순간 이후의 모든 수치는 성능이 아니라 암기 결과가 된다({@code docs/eval/tuning-history.md}
 * 가 기록한, 재현할 수 없게 된 튜닝이 정확히 이 종류의 실패였다). 규칙이 문서에만 있으면
 * 위반은 리뷰어의 기억력에 달린다. 그래서 기계로 잡는다.
 *
 * <h2>어떻게 실패하는가 (fail-closed)</h2>
 *
 * <p>가드는 <b>발견하면 실패</b>가 아니라 <b>발견하지 못하면 통과</b>가 되지 않도록 설계했다.
 *
 * <ul>
 *   <li>스캔 대상 파일이 하나도 없으면 그 자체로 실패한다. 경로가 바뀌어 아무것도 검사하지
 *       못하는 상태가 조용한 통과로 보이면 안 된다.</li>
 *   <li>탐지는 정규화 후 문자열 포함으로 한다. 정규화는 NFKC·소문자·결합 문자 제거·공백
 *       제거까지만 하고 구두점은 남긴다 — 표기 우회 케이스는 구분자가 곧 내용이다.</li>
 *   <li>16자 이상 케이스는 <b>모든 16자 창</b>을 검사한다. 문장을 잘라서 옮겨 붙여도 걸린다.
 *       8~15자는 전문 일치로 본다. 8자 미만은 우연 일치가 잦아 기계 판정을 포기하고,
 *       포기했다는 사실을 {@link #reportsCasesTooShortToGuard()} 가 명시적으로 출력한다.</li>
 * </ul>
 *
 * <p>잡을 수 없는 것도 적어 둔다. 사람이 케이스를 <b>바꿔 쓰는 것</b>(패러프레이즈)은 어떤
 * 문자열 검사로도 잡히지 않는다. 이 가드는 복사·붙여넣기를 막을 뿐이고, 나머지는 라벨 절차
 * 문서의 규칙과 리뷰가 담당한다.
 *
 * <h2>dev_gold 분리</h2>
 *
 * <p>기존 172건({@link CrisisCorpus})은 이미 룰·프롬프트 튜닝에 노출됐으므로 dev_gold 로
 * 남고 잠금 gold 로 승격하지 않는다. 잠금 케이스가 dev_gold 케이스와 같거나 근사 중복이면
 * 잠금 세트는 사실상 튜닝에 노출된 데이터를 다시 재는 것이 되므로 실패시킨다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] 잠금 평가셋 오염 방지 가드")
class LockedEvalContaminationGuardTest {

    /** 프롬프트·템플릿·튜닝 소스로 간주하는 스캔 루트. */
    private static final List<String> SCAN_ROOTS = List.of(
            "src/main/java", "src/main/resources",
            "src/test/java", "src/test/resources",
            "docs", "scripts", "ops", ".github");

    /** 텍스트로 읽을 확장자. 이 밖은 사람이 문장을 옮겨 붙일 대상이 아니다. */
    private static final Set<String> SCAN_EXTENSIONS = Set.of(
            ".java", ".yml", ".yaml", ".json", ".md", ".sql", ".txt", ".py", ".sh", ".kts",
            ".properties");

    /** 잠금 세트 자신은 스캔에서 제외한다. */
    private static final String LOCKED_DIR = "src/test/resources/eval/locked";

    private static final int FRAGMENT_LENGTH = 16;
    private static final int MIN_VERBATIM_LENGTH = 8;

    /**
     * 근사 중복 임계값 (3-gram Jaccard).
     *
     * <p>작성 시점 실측 최대치는 dev_gold 대비 0.39, 세트 내부 0.31 이다. 임계값은 그보다
     * 위인 0.55 로 둔다 — 실측치를 그대로 임계값으로 쓰면 케이스를 하나만 추가해도 테스트가
     * 깨져 잠금이 아니라 소음이 된다. 반대로 0.8 처럼 느슨하게 두면 표현만 살짝 바꾼 복제를
     * 통과시킨다.
     */
    private static final double NEAR_DUPLICATE_THRESHOLD = 0.55;

    private final Path repoRoot = findRepoRoot();

    // ── 소스 오염 ───────────────────────────────────────────────────

    @Test
    @DisplayName("잠금 케이스 본문이 프롬프트·템플릿·튜닝 소스에 나타나지 않는다")
    void lockedCaseTextNeverAppearsInSources() {
        Map<String, String> fragmentOwner = new HashMap<>();
        List<Probe> verbatimProbes = new ArrayList<>();

        for (LockedCase c : LockedEvalSet.CASES) {
            for (var turn : c.userTurns()) {
                String normalized = LockedEvalSet.normalize(turn.text());
                if (normalized.length() >= FRAGMENT_LENGTH) {
                    for (int i = 0; i + FRAGMENT_LENGTH <= normalized.length(); i++) {
                        fragmentOwner.putIfAbsent(
                                normalized.substring(i, i + FRAGMENT_LENGTH), c.id());
                    }
                } else if (normalized.length() >= MIN_VERBATIM_LENGTH) {
                    verbatimProbes.add(new Probe(c.id(), normalized));
                }
            }
        }

        List<Path> files = scanTargets();
        assertThat(files)
                .as("스캔 대상 파일이 하나도 없다 — 경로가 바뀌었는데 통과로 보이면 가드가 아니다")
                .isNotEmpty();
        assertThat(fragmentOwner)
                .as("검사할 조각이 하나도 없다")
                .isNotEmpty();

        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            String content = LockedEvalSet.normalize(read(file));
            String relative = repoRoot.relativize(file).toString();

            for (int i = 0; i + FRAGMENT_LENGTH <= content.length(); i++) {
                String owner = fragmentOwner.get(content.substring(i, i + FRAGMENT_LENGTH));
                if (owner != null) {
                    hits.add("%s ← %s".formatted(owner, relative));
                    break;
                }
            }
            for (Probe probe : verbatimProbes) {
                if (content.contains(probe.normalized())) {
                    hits.add("%s ← %s (전문 일치)".formatted(probe.caseId(), relative));
                }
            }
        }

        assertThat(new LinkedHashSet<>(hits))
                .as("잠금 케이스가 소스에 유출됐다. 잠금 세트는 프롬프트·few-shot·룰 확장·"
                        + "튜닝 어디에도 쓰지 않는다:%n  %s", String.join("\n  ", hits))
                .isEmpty();
    }

    @Test
    @DisplayName("기계로 검사할 수 없는 짧은 케이스를 숨기지 않고 보고한다")
    void reportsCasesTooShortToGuard() {
        List<String> tooShort = new ArrayList<>();
        for (LockedCase c : LockedEvalSet.CASES) {
            for (var turn : c.userTurns()) {
                if (LockedEvalSet.normalize(turn.text()).length() < MIN_VERBATIM_LENGTH) {
                    tooShort.add(c.id());
                }
            }
        }

        System.out.printf("%n[locked-guard] 문자열 검사 미적용 케이스 %d건: %s%n",
                tooShort.size(), tooShort);

        assertThat(tooShort)
                .as("짧아서 기계 판정을 포기한 케이스가 늘면 가드의 실질 커버리지가 무너진다")
                .hasSizeLessThanOrEqualTo(10);
    }

    // ── dev_gold 분리 ───────────────────────────────────────────────

    @Test
    @DisplayName("잠금 케이스는 dev_gold 케이스와 정규화 후에도 같지 않다")
    void noExactDuplicateOfDevGold() {
        Set<String> devGold = devGoldNormalized();

        List<String> duplicates = new ArrayList<>();
        for (LockedCase c : LockedEvalSet.CASES) {
            for (var turn : c.userTurns()) {
                if (devGold.contains(LockedEvalSet.normalize(turn.text()))) {
                    duplicates.add(c.id() + " : " + turn.text());
                }
            }
        }

        assertThat(duplicates)
                .as("dev_gold 와 같은 문장:%n  %s", String.join("\n  ", duplicates))
                .isEmpty();
    }

    @Test
    @DisplayName("잠금 케이스는 dev_gold 케이스의 근사 중복이 아니다 (3-gram Jaccard < 0.55)")
    void noNearDuplicateOfDevGold() {
        List<String> devGold = List.copyOf(devGoldNormalized());

        List<String> offenders = new ArrayList<>();
        double worst = 0.0;
        for (LockedCase c : LockedEvalSet.CASES) {
            for (var turn : c.userTurns()) {
                String a = LockedEvalSet.normalize(turn.text());
                for (String b : devGold) {
                    double similarity = LockedEvalSet.similarity(a, b);
                    worst = Math.max(worst, similarity);
                    if (similarity >= NEAR_DUPLICATE_THRESHOLD) {
                        offenders.add("%.2f %s : %s ↔ dev_gold".formatted(
                                similarity, c.id(), turn.text()));
                    }
                }
            }
        }

        System.out.printf("%n[locked-guard] dev_gold 최대 유사도 %.3f (임계 %.2f)%n",
                worst, NEAR_DUPLICATE_THRESHOLD);
        assertThat(offenders)
                .as("dev_gold 근사 중복:%n  %s", String.join("\n  ", offenders))
                .isEmpty();
    }

    @Test
    @DisplayName("잠금 세트 안에서도 서로 근사 중복이 아니다 — 같은 문장을 여러 번 세지 않는다")
    void noNearDuplicateWithinLockedSet() {
        record Item(String id, String text, String normalized) {}

        List<Item> items = new ArrayList<>();
        LockedEvalSet.CASES.forEach(c -> c.userTurns().forEach(
                t -> items.add(new Item(c.id(), t.text(), LockedEvalSet.normalize(t.text())))));

        List<String> offenders = new ArrayList<>();
        double worst = 0.0;
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                if (items.get(i).id().equals(items.get(j).id())) {
                    continue;
                }
                double similarity = LockedEvalSet.similarity(
                        items.get(i).normalized(), items.get(j).normalized());
                worst = Math.max(worst, similarity);
                if (similarity >= NEAR_DUPLICATE_THRESHOLD) {
                    offenders.add("%.2f %s ↔ %s".formatted(
                            similarity, items.get(i).id(), items.get(j).id()));
                }
            }
        }

        System.out.printf("[locked-guard] 세트 내부 최대 유사도 %.3f (임계 %.2f)%n",
                worst, NEAR_DUPLICATE_THRESHOLD);
        assertThat(offenders)
                .as("세트 내부 근사 중복:%n  %s", String.join("\n  ", offenders))
                .isEmpty();
    }

    @Test
    @DisplayName("dev_gold 는 잠금 gold 로 승격되지 않는다 — 두 세트의 id 공간이 겹치지 않는다")
    void devGoldStaysDevGold() {
        assertThat(CrisisCorpus.VERSION)
                .as("dev_gold 버전")
                .isEqualTo("crisis-corpus-v1");
        assertThat(LockedEvalSet.VERSION).isNotEqualTo(CrisisCorpus.VERSION);
        assertThat(LockedEvalSet.CASES)
                .allSatisfy(c -> assertThat(c.subgroup())
                        .as("dev_gold 카테고리 접두사를 그대로 쓰면 두 세트의 하위 그룹 지표가 섞인다")
                        .doesNotStartWith("FN-").doesNotStartWith("FP-").doesNotStartWith("TP-"));
    }

    // ── 보조 ────────────────────────────────────────────────────────

    private record Probe(String caseId, String normalized) {}

    private Set<String> devGoldNormalized() {
        Set<String> out = new LinkedHashSet<>();
        CrisisCorpus.PROBES.forEach(p -> out.add(LockedEvalSet.normalize(p.message())));
        return out;
    }

    private List<Path> scanTargets() {
        List<Path> files = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (String root : SCAN_ROOTS) {
            Path dir = repoRoot.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(this::hasScannableExtension)
                        .filter(p -> !repoRoot.relativize(p).toString().startsWith(LOCKED_DIR))
                        .forEach(p -> {
                            if (seen.add(p.toAbsolutePath())) {
                                files.add(p);
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException("스캔 대상을 읽지 못했다: " + dir, e);
            }
        }
        return files;
    }

    private boolean hasScannableExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SCAN_EXTENSIONS.contains(name.substring(dot));
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 저장소 루트. 테스트 작업 디렉터리가 어디든 {@code settings.gradle.kts} 를 기준으로 찾는다.
     * 찾지 못하면 조용히 빈 스캔으로 넘어가지 않고 실패한다.
     */
    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("저장소 루트를 찾지 못했다 — 오염 스캔을 건너뛸 수 없다");
    }
}
