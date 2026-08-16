package com.mio.ai.qa;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 잠금 평가셋 오염 스캐너 — 탐지 로직 본체 (이슈 #454, 로드맵 §6.1·§6.4).
 *
 * <h2>왜 테스트에서 분리했는가</h2>
 *
 * <p>{@link LockedEvalContaminationGuardTest} 는 저장소 전체를 스캔하고 "히트 없음" 을
 * 단언한다. 그런데 히트가 없는 상태는 <b>탐지가 고장 나 아무것도 찾지 못하는 상태</b>와
 * 구분되지 않는다. 그래서 스캔 로직을 이 클래스로 빼고,
 * {@link LockedEvalContaminationSelfTest} 가 <b>같은 메서드</b>에 심어 둔 가짜 유출을 먹여
 * 실제로 잡아내는지 확인한다. 자기검증이 로직의 복사본을 검사하면 아무 의미가 없으므로,
 * 실제 스캔과 자기검증은 반드시 이 클래스의 같은 진입점({@link #scan(Path, Probes)})을 쓴다.
 *
 * <h2>탐지 규칙 (fail-closed)</h2>
 *
 * <table>
 *   <caption>규칙별 적용 구간과 작성 시점 실측 오탐</caption>
 *   <tr><th>규칙</th><th>적용</th><th>내용</th><th>클린 트리 오탐</th></tr>
 *   <tr><td>{@code LONG_FRAGMENT}</td><td>정규화 길이 ≥ 16</td>
 *       <td>모든 16자 창</td><td>0</td></tr>
 *   <tr><td>{@code SHORT_FRAGMENT}</td><td>정규화 길이 ≥ 9</td>
 *       <td>모든 9자 창</td><td>0</td></tr>
 *   <tr><td>{@code SHORT_SIMILARITY}</td><td>8 ≤ 정규화 길이 ≤ 15</td>
 *       <td>길이 L-1·L·L+1 창과 3-gram Jaccard ≥ 0.55</td><td>0</td></tr>
 * </table>
 *
 * <h2>짧은 케이스 구간을 왜 이렇게 잡았는가 (실측)</h2>
 *
 * <p>이전 구현은 8~15자 구간을 <b>전문 일치</b>로만 봤다. 그 구간이 244건 중 109턴이라,
 * 오타 하나를 고치거나 종결어미 하나를 떼면 탐지를 통째로 우회했다. 창 크기를 낮추되
 * 오탐이 늘지 않는 지점을 클린 트리(스캔 대상 705개 파일)에서 직접 재서 골랐다.
 *
 * <pre>
 *   창 8자 → 오탐 12건 (3개 조각: "무슨일이있었는지" · "불안장애초기증상" · "야할지모르겠어요")
 *   창 9자 → 오탐 0건, 109턴 중 102턴 적용
 *   창 10자 이상 → 오탐 0건이지만 적용 턴이 91 → 19턴으로 급감
 * </pre>
 *
 * <p>8자 창의 12건은 전부 한국어에서 흔한 일반 표현이라 유출이 아니다. 그런 조각을
 * 억제 목록으로 덮으면 그 목록이 곧 유출을 숨기는 통로가 되므로, 억제 대신 <b>창을 9자로</b>
 * 두고 남은 구간을 유사도 규칙으로 덮는다. 유사도 규칙은 길이 8~15 전 구간(109턴 전부)에
 * 적용되며 클린 트리 실측 최대 유사도는 0.400 으로 임계 0.55 보다 낮다 — 즉 두 규칙 모두
 * 오탐 0으로 측정됐다. 측정값은 {@code docs/eval/locked-eval-set-labeling-procedure.md} 에
 * 함께 적었고, 가드 실행 시마다 규칙별 히트 수를 출력한다.
 */
final class LockedEvalContaminationScanner {

    /** 프롬프트·템플릿·튜닝 소스로 간주하는 스캔 루트. */
    static final List<String> SCAN_ROOTS = List.of(
            "src/main/java", "src/main/resources",
            "src/test/java", "src/test/resources",
            "docs", "scripts", "ops", ".github");

    /** 텍스트로 읽을 확장자. 이 밖은 사람이 문장을 옮겨 붙일 대상이 아니다. */
    static final Set<String> SCAN_EXTENSIONS = Set.of(
            ".java", ".yml", ".yaml", ".json", ".md", ".sql", ".txt", ".py", ".sh", ".kts",
            ".properties");

    /** 잠금 세트 자신은 스캔에서 제외한다. */
    static final String LOCKED_DIR = "src/test/resources/eval/locked";

    static final int LONG_FRAGMENT_LENGTH = 16;
    static final int SHORT_FRAGMENT_LENGTH = 9;
    static final int MIN_GUARDABLE_LENGTH = 8;

    /**
     * 근사 중복·짧은 케이스 유사도 임계값 (3-gram Jaccard).
     *
     * <p>dev_gold 분리 검사와 같은 값을 쓴다. 구현도 {@link LockedEvalSet#similarity} 하나만
     * 쓴다 — 유사도 계산이 두 벌 있으면 한쪽만 고쳐져 두 검사가 다른 것을 재게 된다.
     */
    static final double SIMILARITY_THRESHOLD = 0.55;

    /** 어느 규칙이 잡았는지. 히트 없음을 보고할 때도 규칙별로 나눠 출력한다. */
    enum Rule { LONG_FRAGMENT, SHORT_FRAGMENT, SHORT_SIMILARITY }

    record Hit(String caseId, String path, Rule rule, String evidence) {
        @Override
        public String toString() {
            return "%s ← %s [%s] %s".formatted(caseId, path, rule, evidence);
        }
    }

    /** 케이스 한 턴의 검사용 텍스트. */
    record CaseText(String caseId, String normalized) {}

    /** 짧은 케이스 유사도 프로브. */
    record ShortProbe(String caseId, String normalized) {}

    /**
     * 스캔에 쓰는 조각 색인.
     *
     * @param longFragments      16자 창 → 소유 케이스 id
     * @param shortFragments     9자 창 → 소유 케이스 id
     * @param shortProbes        8~15자 케이스 전문 (유사도 검사 대상)
     * @param unguardableCaseIds 8자 미만이라 기계 판정을 포기한 케이스 id
     * @param shortBandCaseIds   8~15자 구간 케이스 id (구간 크기 상한 검사용)
     */
    record Probes(Map<String, String> longFragments, Map<String, String> shortFragments,
                  List<ShortProbe> shortProbes, List<String> unguardableCaseIds,
                  List<String> shortBandCaseIds) {

        boolean isEmpty() {
            return longFragments.isEmpty() && shortFragments.isEmpty() && shortProbes.isEmpty();
        }

        int fragmentCount() {
            return longFragments.size() + shortFragments.size();
        }
    }

    // ── 조각 만들기 ─────────────────────────────────────────────────

    static Probes probesFromLockedSet() {
        List<CaseText> texts = new ArrayList<>();
        for (LockedEvalSet.LockedCase c : LockedEvalSet.CASES) {
            for (LockedEvalSet.Turn t : c.userTurns()) {
                texts.add(new CaseText(c.id(), LockedEvalSet.normalize(t.text())));
            }
        }
        return probesFrom(texts);
    }

    static Probes probesFrom(List<CaseText> texts) {
        Map<String, String> longFragments = new HashMap<>();
        Map<String, String> shortFragments = new HashMap<>();
        List<ShortProbe> shortProbes = new ArrayList<>();
        List<String> unguardable = new ArrayList<>();
        List<String> shortBand = new ArrayList<>();

        for (CaseText text : texts) {
            String n = text.normalized();
            if (n.length() >= LONG_FRAGMENT_LENGTH) {
                index(longFragments, n, LONG_FRAGMENT_LENGTH, text.caseId());
            } else if (n.length() >= MIN_GUARDABLE_LENGTH) {
                shortBand.add(text.caseId());
                shortProbes.add(new ShortProbe(text.caseId(), n));
            } else {
                unguardable.add(text.caseId());
                continue;
            }
            if (n.length() >= SHORT_FRAGMENT_LENGTH && n.length() < LONG_FRAGMENT_LENGTH) {
                index(shortFragments, n, SHORT_FRAGMENT_LENGTH, text.caseId());
            }
        }
        return new Probes(Map.copyOf(longFragments), Map.copyOf(shortFragments),
                List.copyOf(shortProbes), List.copyOf(unguardable), List.copyOf(shortBand));
    }

    private static void index(Map<String, String> target, String text, int window, String caseId) {
        for (int i = 0; i + window <= text.length(); i++) {
            target.putIfAbsent(text.substring(i, i + window), caseId);
        }
    }

    // ── 스캔 ────────────────────────────────────────────────────────

    /**
     * {@code root} 아래 스캔 루트를 훑어 유출을 모두 보고한다.
     *
     * <p>파일당 첫 히트에서 멈추지 않는다 — 두 케이스가 함께 새어나간 파일이 하나만 보고되면
     * 정리 작업이 절반만 끝난 채 통과로 보인다.
     *
     * @throws IllegalStateException 스캔 대상이 0건이거나 조각이 0건일 때. 아무것도 검사하지
     *                               못하는 상태가 조용한 통과로 보이면 가드가 아니다
     */
    static List<Hit> scan(Path root, Probes probes) {
        List<Path> files = scanTargets(root);
        if (files.isEmpty()) {
            throw new IllegalStateException(
                    "스캔 대상 파일이 하나도 없다 — 경로가 바뀌었는데 통과로 보이면 가드가 아니다: " + root);
        }
        if (probes.isEmpty()) {
            throw new IllegalStateException("검사할 조각이 하나도 없다 — 프로브 생성이 깨졌다");
        }

        Map<String, Set<ShortProbe>> trigramIndex = trigramIndex(probes.shortProbes());
        List<Hit> hits = new ArrayList<>();
        for (Path file : files) {
            String relative = root.relativize(file).toString();
            String content = LockedEvalSet.normalize(read(file));
            hits.addAll(scanContent(relative, content, probes, trigramIndex));
        }
        return List.copyOf(hits);
    }

    /** 파일 하나(정규화된 본문)에 대한 탐지. 실제 스캔과 자기검증이 함께 쓰는 지점이다. */
    static List<Hit> scanContent(String path, String normalizedContent, Probes probes,
                                 Map<String, Set<ShortProbe>> trigramIndex) {
        Set<Hit> hits = new LinkedHashSet<>();

        collectFragmentHits(hits, path, normalizedContent,
                probes.longFragments(), LONG_FRAGMENT_LENGTH, Rule.LONG_FRAGMENT);
        collectFragmentHits(hits, path, normalizedContent,
                probes.shortFragments(), SHORT_FRAGMENT_LENGTH, Rule.SHORT_FRAGMENT);
        collectSimilarityHits(hits, path, normalizedContent, trigramIndex);

        return List.copyOf(hits);
    }

    private static void collectFragmentHits(Set<Hit> hits, String path, String content,
                                            Map<String, String> fragments, int window, Rule rule) {
        if (fragments.isEmpty()) {
            return;
        }
        for (int i = 0; i + window <= content.length(); i++) {
            String fragment = content.substring(i, i + window);
            String owner = fragments.get(fragment);
            if (owner != null) {
                hits.add(new Hit(owner, path, rule, fragment));
            }
        }
    }

    /**
     * 8~15자 케이스의 유사도 검사.
     *
     * <p>본문 전체 × 케이스 전체를 곱하면 계산량이 감당되지 않으므로 3-gram 색인으로 후보를
     * 좁힌다. 후보가 걸린 위치 주변에서 길이 L-1·L·L+1 창을 잘라 {@link LockedEvalSet#similarity}
     * 로 판정한다. 길이를 세 가지로 보는 이유는 글자 하나를 빼거나 더한 옮겨 적기를 잡기
     * 위해서다.
     */
    private static void collectSimilarityHits(Set<Hit> hits, String path, String content,
                                              Map<String, Set<ShortProbe>> trigramIndex) {
        if (trigramIndex.isEmpty() || content.length() < 3) {
            return;
        }
        Set<String> evaluated = new HashSet<>();
        for (int i = 0; i + 3 <= content.length(); i++) {
            Set<ShortProbe> candidates = trigramIndex.get(content.substring(i, i + 3));
            if (candidates == null) {
                continue;
            }
            for (ShortProbe probe : candidates) {
                int length = probe.normalized().length();
                int from = Math.max(0, i - length);
                int to = Math.min(i, content.length() - 1);
                for (int start = from; start <= to; start++) {
                    for (int size = length - 1; size <= length + 1; size++) {
                        if (size < 3 || start + size > content.length()) {
                            continue;
                        }
                        if (!evaluated.add(probe.caseId() + "@" + start + "+" + size)) {
                            continue;
                        }
                        String window = content.substring(start, start + size);
                        double similarity = LockedEvalSet.similarity(probe.normalized(), window);
                        if (similarity >= SIMILARITY_THRESHOLD) {
                            hits.add(new Hit(probe.caseId(), path, Rule.SHORT_SIMILARITY,
                                    "%.2f ↔ %s".formatted(similarity, window)));
                        }
                    }
                }
            }
        }
    }

    private static Map<String, Set<ShortProbe>> trigramIndex(List<ShortProbe> probes) {
        Map<String, Set<ShortProbe>> index = new HashMap<>();
        for (ShortProbe probe : probes) {
            String n = probe.normalized();
            for (int i = 0; i + 3 <= n.length(); i++) {
                index.computeIfAbsent(n.substring(i, i + 3), k -> new LinkedHashSet<>()).add(probe);
            }
        }
        return index;
    }

    /** 규칙별 히트 수. 히트가 0건이어도 어느 규칙이 돌았는지 남긴다. */
    static Map<Rule, Long> countByRule(List<Hit> hits) {
        Map<Rule, Long> counts = new LinkedHashMap<>();
        for (Rule rule : Rule.values()) {
            counts.put(rule, hits.stream().filter(h -> h.rule() == rule).count());
        }
        return counts;
    }

    // ── 파일 열거 ───────────────────────────────────────────────────

    static List<Path> scanTargets(Path root) {
        List<Path> files = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (String scanRoot : SCAN_ROOTS) {
            Path dir = root.resolve(scanRoot);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(LockedEvalContaminationScanner::hasScannableExtension)
                        .filter(p -> !root.relativize(p).toString().startsWith(LOCKED_DIR))
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

    static String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "(none)";
    }

    private static boolean hasScannableExtension(Path path) {
        return SCAN_EXTENSIONS.contains(extensionOf(path));
    }

    static String read(Path path) {
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
    static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("저장소 루트를 찾지 못했다 — 오염 스캔을 건너뛸 수 없다");
    }

    private LockedEvalContaminationScanner() {
    }
}
