package com.mio.ai.qa;

import com.mio.ai.qa.LockedEvalContaminationScanner.Hit;
import com.mio.ai.qa.LockedEvalContaminationScanner.Probes;
import com.mio.ai.qa.LockedEvalSet.LockedCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 *   <li>스캔 대상 파일이 하나도 없으면 예외로 죽는다. 경로가 바뀌어 아무것도 검사하지
 *       못하는 상태가 조용한 통과로 보이면 안 된다.</li>
 *   <li>탐지 규칙과 구간은 {@link LockedEvalContaminationScanner} 에 있고, 그 로직이 실제로
 *       유출을 잡는지는 {@link LockedEvalContaminationSelfTest} 가 심어 둔 가짜 유출로
 *       매번 확인한다 — "히트 없음" 이 "탐지가 죽었음" 과 구분되게 만드는 장치다.</li>
 *   <li>스캔 대상 확장자 허용목록의 드리프트도 검사한다({@link #scanExtensionAllowlistHasNoDrift()}).
 *       새 템플릿 형식이 들어오면 사각지대가 생기는 대신 빌드가 깨진다.</li>
 * </ul>
 *
 * <p>잡을 수 없는 것도 적어 둔다. 사람이 케이스를 <b>바꿔 쓰는 것</b>(문장 전체를 다시 쓰는
 * 패러프레이즈)은 어떤 문자열 검사로도 잡히지 않는다. 이 가드가 막는 것은 복사·붙여넣기와
 * 글자 몇 개를 고친 옮겨 적기까지다.
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

    /**
     * 8자 미만이라 기계 판정을 포기한 턴 수의 상한.
     *
     * <p>포기한 사실을 출력만 하고 상한을 두지 않으면 짧은 케이스가 늘어도 아무도 모른다.
     */
    private static final int MAX_UNGUARDABLE_TURNS = 10;

    /**
     * 8~15자 구간(전문 일치 + 9자 창 + 유사도로만 지켜지는 구간)의 상한.
     *
     * <p>16자 이상 구간은 16자 창 전수 검사로 잘라 붙이기까지 잡히지만, 8~15자 구간은
     * 창이 짧아 우연 일치 위험 때문에 규칙을 더 조일 수 없다. 즉 <b>보호가 상대적으로
     * 약한 구간</b>이다. 8자 미만 코호트와 마찬가지로 크기를 보고하고 상한을 걸어,
     * 짧은 케이스만 늘어나 가드의 실질 커버리지가 조용히 무너지는 일을 막는다.
     */
    private static final int MAX_SHORT_BAND_TURNS = 120;

    /** 짧은 구간이 전체 턴에서 차지하는 비율 상한. 세트가 커져도 구성이 짧은 쪽으로 쏠리지 않게 한다. */
    private static final double MAX_SHORT_BAND_SHARE = 50.0;

    /**
     * 근사 중복 임계값 (3-gram Jaccard).
     *
     * <p>작성 시점 실측 최대치는 dev_gold 대비 0.39, 세트 내부 0.31 이다. 임계값은 그보다
     * 위인 0.55 로 둔다 — 실측치를 그대로 임계값으로 쓰면 케이스를 하나만 추가해도 테스트가
     * 깨져 잠금이 아니라 소음이 된다. 반대로 0.8 처럼 느슨하게 두면 표현만 살짝 바꾼 복제를
     * 통과시킨다.
     */
    private static final double NEAR_DUPLICATE_THRESHOLD =
            LockedEvalContaminationScanner.SIMILARITY_THRESHOLD;

    private final Path repoRoot = LockedEvalContaminationScanner.findRepoRoot();

    // ── 소스 오염 ───────────────────────────────────────────────────

    @Test
    @DisplayName("잠금 케이스 본문이 프롬프트·템플릿·튜닝 소스에 나타나지 않는다")
    void lockedCaseTextNeverAppearsInSources() {
        Probes probes = LockedEvalContaminationScanner.probesFromLockedSet();
        List<Hit> hits = LockedEvalContaminationScanner.scan(repoRoot, probes);

        System.out.printf("%n[locked-guard] 조각 %d개 · 짧은 케이스 프로브 %d건 · 규칙별 히트 %s%n",
                probes.fragmentCount(), probes.shortProbes().size(),
                LockedEvalContaminationScanner.countByRule(hits));

        assertThat(hits)
                .as("잠금 케이스가 소스에 유출됐다. 잠금 세트는 프롬프트·few-shot·룰 확장·"
                        + "튜닝 어디에도 쓰지 않는다:%n  %s", join(hits))
                .isEmpty();
    }

    @Test
    @DisplayName("기계로 검사할 수 없는 짧은 케이스를 숨기지 않고 보고한다")
    void reportsCasesTooShortToGuard() {
        Probes probes = LockedEvalContaminationScanner.probesFromLockedSet();

        System.out.printf("[locked-guard] 문자열 검사 미적용(8자 미만) 턴 %d건: %s%n",
                probes.unguardableCaseIds().size(), probes.unguardableCaseIds());

        assertThat(probes.unguardableCaseIds())
                .as("짧아서 기계 판정을 포기한 케이스가 늘면 가드의 실질 커버리지가 무너진다")
                .hasSizeLessThanOrEqualTo(MAX_UNGUARDABLE_TURNS);
    }

    @Test
    @DisplayName("보호가 약한 8~15자 구간의 크기를 보고하고 상한을 건다")
    void shortGuardBandStaysBounded() {
        Probes probes = LockedEvalContaminationScanner.probesFromLockedSet();
        int totalTurns = (int) LockedEvalSet.CASES.stream()
                .mapToLong(c -> c.userTurns().size())
                .sum();
        int band = probes.shortBandCaseIds().size();
        double share = totalTurns == 0 ? 0.0 : band * 100.0 / totalTurns;

        System.out.printf("[locked-guard] 8~15자 구간 %d턴 / 전체 %d턴 (%.1f%%, 상한 %d턴·%.0f%%)%n",
                band, totalTurns, share, MAX_SHORT_BAND_TURNS, MAX_SHORT_BAND_SHARE);

        assertThat(band)
                .as("8~15자 구간은 16자 창 전수 검사를 걸 수 없어 보호가 약하다. "
                        + "이 구간이 커지면 가드는 통과하는데 실제로는 지켜지지 않는다")
                .isLessThanOrEqualTo(MAX_SHORT_BAND_TURNS);
        assertThat(share)
                .as("짧은 케이스 쏠림 비율")
                .isLessThanOrEqualTo(MAX_SHORT_BAND_SHARE);
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

        System.out.printf("[locked-guard] dev_gold 최대 유사도 %.3f (임계 %.2f)%n",
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
        // dev_gold 버전을 여기에 적어 두는 이유: CrisisCorpus.VERSION 이 올라가면(= 케이스가
        // 추가·변경되면) 그 세트는 더 이상 이 잠금 세트가 분리를 확인한 그 세트가 아니다.
        // 값을 자동으로 따라가면 dev_gold 가 바뀌어도 분리 검사가 통과해 버린다. 버전을 올릴
        // 때는 dev_gold 중복·근사 중복 검사를 다시 돌리고 이 상수를 손으로 갱신한다.
        assertThat(CrisisCorpus.VERSION)
                .as("dev_gold 버전 — 올릴 때는 이 파일의 중복 검사를 다시 확인하고 함께 갱신한다")
                .isEqualTo("crisis-corpus-v1");
        assertThat(LockedEvalSet.VERSION).isNotEqualTo(CrisisCorpus.VERSION);
        assertThat(LockedEvalSet.CASES)
                .allSatisfy(c -> assertThat(c.subgroup())
                        .as("dev_gold 카테고리 접두사를 그대로 쓰면 두 세트의 하위 그룹 지표가 섞인다")
                        .doesNotStartWith("FN-").doesNotStartWith("FP-").doesNotStartWith("TP-"));
    }

    // ── 보조 ────────────────────────────────────────────────────────

    private static String join(List<Hit> hits) {
        Set<String> lines = new LinkedHashSet<>();
        hits.forEach(h -> lines.add(h.toString()));
        return String.join("\n  ", lines);
    }

    private Set<String> devGoldNormalized() {
        Set<String> out = new LinkedHashSet<>();
        CrisisCorpus.PROBES.forEach(p -> out.add(LockedEvalSet.normalize(p.message())));
        return out;
    }
}
