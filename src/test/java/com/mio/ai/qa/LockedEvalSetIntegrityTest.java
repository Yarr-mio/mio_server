package com.mio.ai.qa;

import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.qa.LockedEvalSet.LockedCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잠금 평가셋 무결성 게이트 (이슈 #454, 로드맵 §6.4·§11.3).
 *
 * <p>"잠갔다" 는 선언은 검사가 없으면 주석에 불과하다. 여기서 잠그는 것은 세 가지다.
 *
 * <ol>
 *   <li><b>조용한 수정.</b> 파일 전체 해시와 케이스별 해시를 매니페스트와 대조한다. 라벨
 *       하나를 바꿔 게이트를 통과시키는 변경은 매니페스트 diff 없이는 불가능하다.</li>
 *   <li><b>분포 붕괴.</b> 의도한 하위 그룹 분포를 데이터에 적어 두고 실제와 대조한다.
 *       한 그룹이 전체를 지배하면 총계 지표가 그 그룹의 지표가 된다.</li>
 *   <li><b>주장 부풀리기.</b> 데이터 권리 판정과 라벨링 현황을 값으로 검사한다. 2인 독립
 *       라벨이 없는 상태에서 "합의된 라벨" 이라고 적을 수 없게 한다.</li>
 * </ol>
 *
 * <p>이 테스트는 매니페스트를 <b>절대 재생성하지 않는다.</b> 테스트가 스스로 갱신하면 잠금이
 * 아니라 자동 승인이 된다. 재생성은 사람이 {@code scripts/eval/locked_eval_manifest.py --write}
 * 로 실행하고, 그 diff 가 리뷰 대상이 된다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] 잠금 평가셋 무결성")
class LockedEvalSetIntegrityTest {

    /**
     * 로드맵 §6.4 가 정한 P0 잠금 gold 규모.
     *
     * <p>상한을 300 에서 400 으로 올렸다. 하위 그룹 8% 상한과 "하위 그룹 n 이 너무 작다" 는
     * 지적은 총계를 고정한 채로는 동시에 만족할 수 없다 — 총계가 300 이면 어떤 그룹도 24 를
     * 넘을 수 없다. 그룹을 잘게 유지하면서 n 을 키우려면 총계를 늘리는 수밖에 없고, 그래서
     * 상한을 올린다. 하한은 그대로 둔다.
     */
    private static final int MIN_CASES = 200;
    private static final int MAX_CASES = 400;

    /** 어느 하위 그룹도 이 비율을 넘지 않는다. */
    private static final double MAX_SUBGROUP_SHARE = 8.0;

    /** 축 하나가 이 수 미만이면 축 단위 비교도 할 수 없다. */
    private static final int MIN_AXIS_SIZE = 15;

    private final Map<String, String> manifest = LockedEvalManifest.scalars();

    // ── 잠금 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("파일 전체 해시가 매니페스트와 같다 — 공백 한 칸의 수정도 잡힌다")
    void fileHashMatchesManifest() {
        assertThat(LockedEvalSet.fileSha256())
                .as("잠금 세트가 매니페스트와 어긋난다. 의도한 변경이면 "
                        + "python3 scripts/eval/locked_eval_manifest.py --write 로 갱신하고 "
                        + "그 diff 를 PR 에 남겨라")
                .isEqualTo(manifest.get("set_sha256"));
    }

    @Test
    @DisplayName("케이스별 해시가 매니페스트와 같다 — 무엇이 바뀌었는지까지 드러난다")
    void everyCaseHashMatchesManifest() {
        // 대조 로직 자체가 살아 있는지는 LockedEvalContaminationSelfTest 가 메모리에서 변조한
        // 케이스를 같은 diff() 에 먹여 확인한다 — "차이 없음" 이 "비교를 안 함" 과 구분되도록.
        List<String> changed = LockedEvalManifest.diff(LockedEvalSet.CASES);

        assertThat(changed)
                .as("매니페스트와 다른 케이스:%n  %s", String.join("\n  ", changed))
                .isEmpty();
    }

    @Test
    @DisplayName("케이스 수가 매니페스트·로드맵 목표 범위와 맞는다 (200~400건)")
    void caseCountIsLockedAndWithinTarget() {
        assertThat(LockedEvalSet.CASES).hasSize(Integer.parseInt(manifest.get("case_count")));
        assertThat(LockedEvalSet.CASES.size())
                .as("로드맵 §6.4 의 첫 목표 규모")
                .isBetween(MIN_CASES, MAX_CASES);
        assertThat(manifest.get("version")).isEqualTo(LockedEvalSet.VERSION);
        assertThat(manifest.get("canonical_algo")).isEqualTo("v2");
    }

    @Test
    @DisplayName("케이스 id 는 고유하고 LOCK- 접두사를 가진다")
    void idsAreStableAndUnique() {
        List<String> ids = LockedEvalSet.CASES.stream().map(LockedCase::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id).startsWith("LOCK-"));
    }

    // ── 분포 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("실제 하위 그룹 분포가 선언한 의도 분포와 일치한다")
    void actualDistributionMatchesDeclaredIntent() {
        Map<String, Long> actual = countBySubgroup();

        List<String> mismatches = new ArrayList<>();
        LockedEvalSet.INTENDED_DISTRIBUTION.forEach((subgroup, intended) -> {
            long found = actual.getOrDefault(subgroup, 0L);
            if (found != intended) {
                mismatches.add("%s 의도 %d / 실제 %d".formatted(subgroup, intended, found));
            }
        });
        actual.keySet().stream()
                .filter(s -> !LockedEvalSet.INTENDED_DISTRIBUTION.containsKey(s))
                .forEach(s -> mismatches.add("선언되지 않은 하위 그룹: " + s));

        assertThat(mismatches)
                .as("분포 불일치:%n  %s", String.join("\n  ", mismatches))
                .isEmpty();
    }

    @Test
    @DisplayName("어느 하위 그룹도 전체의 8% 를 넘지 않는다")
    void noSubgroupDominates() {
        int total = LockedEvalSet.CASES.size();
        Map<String, Double> shares = new LinkedHashMap<>();
        countBySubgroup().forEach((k, v) -> shares.put(k, v * 100.0 / total));

        List<String> dominant = shares.entrySet().stream()
                .filter(e -> e.getValue() > MAX_SUBGROUP_SHARE)
                .map(e -> "%s %.1f%%".formatted(e.getKey(), e.getValue()))
                .toList();

        assertThat(dominant)
                .as("지배적인 하위 그룹:%n  %s", String.join("\n  ", dominant))
                .isEmpty();
    }

    @Test
    @DisplayName("다섯 평가 축이 모두 채워져 있다 — 안전만 있는 세트가 아니다")
    void everyAxisIsPopulated() {
        Map<String, Long> byAxis = new TreeMap<>();
        LockedEvalSet.CASES.forEach(c -> byAxis.merge(c.axis(), 1L, Long::sum));

        assertThat(byAxis.keySet())
                .as("SECURITY 축이 없으면 방법 요청·지시 주입·탈옥 라우팅을 비교할 수 없다(이슈 #260)")
                .containsExactlyInAnyOrder(
                        "SAFETY", "CBT_FIT", "RESPONSE_QUALITY", "BIAS", "SECURITY");
        byAxis.forEach((axis, count) -> assertThat(count)
                .as("축 %s 의 케이스 수", axis)
                .isGreaterThanOrEqualTo(MIN_AXIS_SIZE));
    }

    @Test
    @DisplayName("다중 턴 케이스가 존재한다 — 턴 단위 판정의 한계를 재려면 필요하다")
    void multiTurnCasesExist() {
        List<LockedCase> multiTurn = LockedEvalSet.CASES.stream()
                .filter(LockedCase::isMultiTurn)
                .toList();

        assertThat(multiTurn).hasSizeGreaterThanOrEqualTo(10);
    }

    // ── 라벨 어휘 ───────────────────────────────────────────────────

    @Test
    @DisplayName("모든 라벨이 지침 어휘 안에 있다 — 병렬 어휘를 만들지 않는다")
    void labelsUseDeclaredVocabulary() {
        List<String> violations = new ArrayList<>();
        List<String> truths = LockedEvalSet.VOCABULARY.get("safetyTruth");
        List<String> exposures = LockedEvalSet.VOCABULARY.get("exposure");
        List<String> acts = LockedEvalSet.VOCABULARY.get("responseAct");
        List<String> forbidden = LockedEvalSet.VOCABULARY.get("forbiddenElement");

        for (LockedCase c : LockedEvalSet.CASES) {
            var e = c.expected();
            if (!truths.contains(e.safetyTruth())) {
                violations.add(c.id() + " safetyTruth=" + e.safetyTruth());
            }
            if (!exposures.contains(e.exposure())) {
                violations.add(c.id() + " exposure=" + e.exposure());
            }
            if (!acts.contains(e.responseAct())) {
                violations.add(c.id() + " responseAct=" + e.responseAct());
            }
            e.forbiddenElements().stream()
                    .filter(f -> !forbidden.contains(f))
                    .forEach(f -> violations.add(c.id() + " forbiddenElement=" + f));
        }

        assertThat(violations)
                .as("어휘 밖 라벨:%n  %s", String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("안전 라벨 어휘는 dev_gold 라벨 지침의 어휘와 같다")
    void safetyVocabularyMatchesLabelingGuide() {
        List<String> declared = LockedEvalSet.VOCABULARY.get("safetyTruth");
        List<String> devGold = java.util.Arrays.stream(CrisisCorpus.Truth.values())
                .map(Enum::name)
                .toList();

        assertThat(declared)
                .as("잠금 세트가 dev_gold 와 다른 안전 라벨 체계를 쓰면 두 결과를 비교할 수 없다")
                .containsExactlyInAnyOrderElementsOf(devGold);
    }

    @Test
    @DisplayName("편향 짝의 변형들은 기대 판정이 완전히 같다")
    void biasPairsShareIdenticalExpectation() {
        Map<String, List<LockedCase>> pairs = new LinkedHashMap<>();
        LockedEvalSet.CASES.stream()
                .filter(c -> !c.pairKey().isEmpty())
                .forEach(c -> pairs.computeIfAbsent(c.pairKey(), k -> new ArrayList<>()).add(c));

        assertThat(pairs).as("편향 짝이 하나도 없다").isNotEmpty();

        List<String> mismatched = new ArrayList<>();
        pairs.forEach((key, variants) -> {
            if (variants.size() < 3) {
                mismatched.add(key + " 변형 수 " + variants.size());
                return;
            }
            var base = variants.get(0).expected();
            variants.stream()
                    .filter(c -> !c.expected().equals(base))
                    .forEach(c -> mismatched.add(key + " / " + c.id()));
        });

        assertThat(mismatched)
                .as("표현만 다른데 기대 판정이 갈리는 짝:%n  %s", String.join("\n  ", mismatched))
                .isEmpty();
    }

    /**
     * 라벨 일치만 검사하면 "같은 상황인가" 는 아무도 보지 않는다. 이전 편향 짝은 고3-부모 갈등
     * 과 며느리 명절 노동처럼 상황 자체가 달랐고, 그런 짝에서 판정이 갈려도 그것이 편향인지
     * 상황 차이인지 구분할 수 없었다. 그래서 <b>본문의 최소성</b>을 기계로 강제한다.
     */
    @Test
    @DisplayName("편향 짝은 표지 토큰 하나만 다른 최소대립쌍이다 — 상황이 다르면 편향을 잴 수 없다")
    void biasPairsAreTrueMinimalPairs() {
        Map<String, List<LockedCase>> pairs = biasPairs();
        assertThat(pairs).as("편향 짝이 하나도 없다").isNotEmpty();

        List<String> violations = new ArrayList<>();
        pairs.forEach((key, variants) -> {
            List<String> skeletons = new ArrayList<>();
            for (LockedCase c : variants) {
                if (c.variantToken().isEmpty()) {
                    violations.add(c.id() + " variantToken 이 비어 있다 — 무엇을 바꾼 짝인지 알 수 없다");
                    continue;
                }
                String joined = c.turns().stream().map(t -> t.role() + ":" + t.text())
                        .reduce("", (a, b) -> a + "" + b);
                if (!joined.contains(c.variantToken())) {
                    violations.add(c.id() + " 본문에 variantToken 이 없다: " + c.variantToken());
                    continue;
                }
                skeletons.add(joined.replace(c.variantToken(), " "));
            }
            if (skeletons.size() == variants.size() && new java.util.HashSet<>(skeletons).size() > 1) {
                violations.add(key + " 변형들이 표지 토큰 외에도 다르다 — 최소대립쌍이 아니다");
            }
            long distinctTokens = variants.stream().map(LockedCase::variantToken).distinct().count();
            if (distinctTokens != variants.size()) {
                violations.add(key + " 표지 토큰이 중복된다");
            }
        });

        assertThat(violations)
                .as("최소대립쌍 위반:%n  %s", String.join("\n  ", violations))
                .isEmpty();
    }

    // ── 프로덕션 계약과의 정합 ──────────────────────────────────────

    /**
     * {@code ResponsePlanner} 는 모든 계획에 {@link ResponsePlan#BASE_FORBIDDEN} 을 씌운다.
     * 데이터가 그보다 약한 계약을 기대값으로 적으면, 프로덕션이 실제로 금지하는 요소를
     * 평가가 눈감아 준다. 과단정("당신은 분명히 …한 상태입니다")은 전형적인 모델 계열 차이라
     * A~E 가 갈려야 할 축인데, 그 축이 데이터에서 꺼져 있었다.
     *
     * <p>상수는 프로덕션에서 직접 읽는다. 여기에 목록을 복사하면 다음 변경 때 또 어긋난다.
     */
    @Test
    @DisplayName("모든 케이스의 금지 요소가 프로덕션 BASE_FORBIDDEN 을 포함한다")
    void everyCaseIncludesProductionBaseForbidden() {
        List<String> weaker = new ArrayList<>();
        for (LockedCase c : LockedEvalSet.CASES) {
            List<String> missing = ResponsePlan.BASE_FORBIDDEN.stream()
                    .filter(f -> !c.expected().forbiddenElements().contains(f))
                    .toList();
            if (!missing.isEmpty()) {
                weaker.add(c.id() + " 빠짐 " + missing);
            }
        }

        assertThat(weaker)
                .as("프로덕션보다 약한 계약을 기대값으로 쓰는 케이스:%n  %s", String.join("\n  ", weaker))
                .isEmpty();
    }

    /**
     * 같은 기대 행위인데 금지 요소가 케이스마다 다르면, 그 차이가 의도인지 실수인지 사후에
     * 알 수 없다. 행위별 정본을 하나로 두고 예외를 허용하지 않는다 — 예외가 필요하면 어휘와
     * 절차 문서를 먼저 고친다.
     */
    @Test
    @DisplayName("같은 기대 행위는 같은 금지 요소 집합을 쓴다")
    void forbiddenElementsAreConsistentPerAct() {
        Map<String, Map<List<String>, List<String>>> byAct = new LinkedHashMap<>();
        for (LockedCase c : LockedEvalSet.CASES) {
            byAct.computeIfAbsent(c.expected().responseAct(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(c.expected().forbiddenElements(), k -> new ArrayList<>())
                    .add(c.id());
        }

        List<String> inconsistent = new ArrayList<>();
        byAct.forEach((act, sets) -> {
            if (sets.size() > 1) {
                inconsistent.add("%s 에 %d 종류의 금지 집합: %s".formatted(act, sets.size(),
                        sets.entrySet().stream()
                                .map(e -> e.getKey() + "×" + e.getValue().size())
                                .toList()));
            }
        });

        assertThat(inconsistent)
                .as("행위별 금지 요소 불일치:%n  %s", String.join("\n  ", inconsistent))
                .isEmpty();
    }

    /**
     * 세 라벨이 완전히 결정적으로 대응하면 축이 세 개가 아니라 하나다. 노출 축이 독립 정보를
     * 주는 지점은 <b>사용자 본인은 위험하지 않은데도 무가드로 흘려보내면 안 되는 턴</b>이다 —
     * 제3자 위험 노출, 반추가 붙은 인용, 타인 가해 요청, 보안 거절이 그렇다.
     *
     * <p>반대로 {@code HARD_CRISIS → CRISIS_FLOW} 는 데이터의 성질이 아니라 §5.5 불변식이므로
     * 여기서 깨뜨리라고 요구하지 않는다. 요구하면 정책을 어기는 케이스를 만들게 된다.
     */
    @Test
    @DisplayName("판정 라벨이 노출·행위를 결정하지 않는다 — 세 축이 한 축으로 붕괴하지 않았다")
    void labelsAreNotCollinear() {
        List<LockedCase> clear = LockedEvalSet.CASES.stream()
                .filter(c -> "CLEAR".equals(c.expected().safetyTruth()))
                .toList();
        List<LockedCase> guardedClear = clear.stream()
                .filter(c -> !"UNGUARDED".equals(c.expected().exposure()))
                .toList();

        System.out.printf("[locked-set] CLEAR %d건 중 비무가드 %d건, 노출 종류 %s%n",
                clear.size(), guardedClear.size(),
                guardedClear.stream().map(c -> c.expected().exposure()).distinct().sorted().toList());

        assertThat(guardedClear)
                .as("CLEAR 가 전부 UNGUARDED 면 노출 축은 판정 축의 복사본이고 지표는 이중 계산된다")
                .hasSizeGreaterThanOrEqualTo(12);
        assertThat(guardedClear.stream().map(c -> c.expected().exposure()).distinct().toList())
                .as("비무가드 CLEAR 가 한 종류뿐이면 노출 축의 값 공간이 사실상 둘이다")
                .hasSizeGreaterThanOrEqualTo(2);

        List<String> riskActs = LockedEvalSet.CASES.stream()
                .filter(c -> "RISK".equals(c.expected().safetyTruth()))
                .map(c -> c.expected().responseAct())
                .distinct().sorted().toList();
        assertThat(riskActs)
                .as("RISK 의 기대 행위가 한 종류뿐이면 행위 축도 판정 축에 종속된다: %s", riskActs)
                .hasSizeGreaterThanOrEqualTo(3);
    }

    // ── 보고 가능성 ─────────────────────────────────────────────────

    /**
     * n 이 작은 하위 그룹의 비율은 계산할 수는 있어도 지지할 수 없다. 그런데 계산해 두면
     * 결국 인용된다("셀 C 가 3인칭에서 나빠졌다"). 그래서 하한을 데이터에 값으로 두고,
     * 실행 하네스가 그 값을 읽어 미달 그룹의 비율 필드를 <b>비우도록</b> 만든다.
     */
    @Test
    @DisplayName("보고 하한이 값으로 있고, 미달 하위 그룹이 보고 가능으로 표시되지 않는다")
    void subgroupReportingFloorIsDeclaredAndEnforced() {
        var reporting = LockedEvalSet.REPORTING;
        assertThat(reporting.minSubgroupN())
                .as("하한이 0 이면 어떤 그룹이든 보고 가능해진다")
                .isGreaterThanOrEqualTo(30);

        Map<String, Long> counts = countBySubgroup();
        List<String> reportable = counts.entrySet().stream()
                .filter(e -> reporting.isReportable(e.getValue()))
                .map(e -> "%s n=%d".formatted(e.getKey(), e.getValue()))
                .toList();
        List<String> notReportable = counts.entrySet().stream()
                .filter(e -> !reporting.isReportable(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        System.out.printf("[locked-set] 보고 하한 n≥%d — 보고 가능 그룹 %d / 미달 %d%n",
                reporting.minSubgroupN(), reportable.size(), notReportable.size());

        assertThat(reportable)
                .as("하한을 넘는 그룹이 생기면 그 그룹만 골라 보고해도 된다는 뜻이므로, "
                        + "이 목록이 바뀌는 순간을 리뷰가 봐야 한다: %s", reportable)
                .isEmpty();
        assertThat(notReportable)
                .as("모든 하위 그룹이 하한 미달이다 — 지금 보고 가능한 단위는 축과 총계뿐이다")
                .hasSize(counts.size());
    }

    /**
     * 자모·기호 우회와 명시 위기는 {@code InputNormalizer} 의 자모 결합·구분자 제거와
     * {@code SafetyL1} 의 우회 매처가 모델 호출 이전에 해결한다. 전 셀이 같은 결과를 내므로
     * 합산하면 SAFETY 점수가 셀 차이와 무관하게 올라간다. 표식을 데이터에 두고 하네스가
     * 나눠 보고하게 한다.
     */
    @Test
    @DisplayName("모델 이전에 결정되는 케이스가 표식돼 있고 모델 변별 모집단과 분리된다")
    void deterministicLayerCasesAreFlagged() {
        List<LockedCase> deterministic = LockedEvalSet.deterministicLayerCases();
        List<LockedCase> model = LockedEvalSet.modelDiscriminatingCases();

        System.out.printf("[locked-set] 결정론 계층 %d건 / 모델 변별 %d건 (전체 %d건)%n",
                deterministic.size(), model.size(), LockedEvalSet.CASES.size());

        assertThat(deterministic)
                .as("표식이 하나도 없으면 결정론 계층이 모델 점수에 섞인 채로 보고된다")
                .isNotEmpty();
        assertThat(deterministic)
                .allSatisfy(c -> assertThat(c.axis())
                        .as("%s — 결정론 계층 표식은 안전 축의 우회·명시 위기에만 붙인다", c.id())
                        .isEqualTo("SAFETY"));
        assertThat(deterministic.size() + model.size()).isEqualTo(LockedEvalSet.CASES.size());
        assertThat(model.size())
                .as("모델이 변별하는 모집단이 전체와 같으면 표식이 의미가 없다")
                .isLessThan(LockedEvalSet.CASES.size());
    }

    // ── 데이터 권리와 라벨링 현황 ───────────────────────────────────

    @Test
    @DisplayName("데이터 권리 판정이 §6.3 게이트 값으로 기록돼 있다")
    void dataRightsAreRecordedAsData() {
        var rights = LockedEvalSet.DATA_RIGHTS;

        assertThat(rights.sourceClass()).isEqualTo("MIO_AUTHORED_SYNTHETIC");
        assertThat(rights.gateDecision())
                .as("§6.3 표의 'Mio 자체 합성' 행 판정")
                .isEqualTo("PRIORITY_USE");
        assertThat(rights.commercialEvaluationAllowed()).isTrue();
        assertThat(rights.modelTrainingAllowed())
                .as("§6.2 — 파인튜닝은 P0~P2 로드맵에서 제외됐다")
                .isFalse();
        assertThat(rights.containsRealUserData()).isFalse();
        assertThat(rights.containsPersonalData()).isFalse();
        assertThat(rights.redistribution()).isEqualTo("INTERNAL_ONLY");

        Map<String, String> fields = rights.asManifestFields();
        assertThat(fields).containsKeys("dataset", "data_rights_gate_decision",
                "data_rights_model_training", "data_rights_expert_review");
        assertThat(fields.values()).allSatisfy(v -> assertThat(v).isNotBlank());
    }

    @Test
    @DisplayName("전문가 검수·임상 타당성은 '아직 아님' 으로 기록돼 있다")
    void expertReviewIsExplicitlyNotDone() {
        assertThat(LockedEvalSet.DATA_RIGHTS.expertReviewed())
                .as("외부 전문가 검수를 받지 않았다. 받은 것처럼 기록하지 않는다")
                .isFalse();
        assertThat(LockedEvalSet.DATA_RIGHTS.expertReviewStatus()).isEqualTo("NOT_DONE");
        assertThat(LockedEvalSet.LABELING.clinicalValidation()).isEqualTo("NOT_DONE");
    }

    @Test
    @DisplayName("라벨링 현황이 로드맵 요구(2인 독립 + 이견 조정)와의 격차를 값으로 남긴다")
    void labelingStatusStatesTheGapPlainly() {
        var labeling = LockedEvalSet.LABELING;

        assertThat(labeling.requiredIndependentLabelCount())
                .as("로드맵 §11.3 이 요구하는 독립 라벨 수")
                .isEqualTo(2);
        assertThat(labeling.agreementMeasured())
                .as("이견률을 측정한 적이 없다. 측정했다고 적으면 그 순간 거짓 근거가 된다")
                .isFalse();
        assertThat(labeling.meetsRoadmapRequirement())
                .as("현재는 요구 조건을 충족하지 않는다 — 충족되면 이 단언과 함께 값을 올린다")
                .isFalse();
        assertThat(labeling.status()).isEqualTo("SINGLE_AUTHOR_PENDING_SECOND_PASS");
    }

    // ── 리포트 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("상세 리포트 — 축·하위 그룹·라벨 분포와 미완료 항목 출력")
    void printCompositionReport() {
        StringBuilder out = new StringBuilder();
        out.append("\n══════════════════════════════════════════════════════════════\n");
        out.append("  잠금 평가셋 구성 리포트 (%s)\n".formatted(LockedEvalSet.VERSION));
        out.append("══════════════════════════════════════════════════════════════\n");
        out.append("  총 %d건\n".formatted(LockedEvalSet.CASES.size()));

        out.append("\n  [축별]\n");
        Map<String, Long> byAxis = new LinkedHashMap<>();
        LockedEvalSet.CASES.forEach(c -> byAxis.merge(c.axis(), 1L, Long::sum));
        byAxis.forEach((k, v) -> out.append("    %-18s %3d  (%.1f%%)%n"
                .formatted(k, v, v * 100.0 / LockedEvalSet.CASES.size())));

        out.append("\n  [하위 그룹별 — 의도 = 실제]\n");
        Map<String, Long> bySubgroup = countBySubgroup();
        LockedEvalSet.INTENDED_DISTRIBUTION.forEach((k, intended) -> out.append(
                "    %-22s %3d = %3d  (%.1f%%)%n".formatted(
                        k, intended, bySubgroup.getOrDefault(k, 0L),
                        bySubgroup.getOrDefault(k, 0L) * 100.0 / LockedEvalSet.CASES.size())));

        out.append("\n  [안전 라벨별]\n");
        Map<String, Long> byTruth = new TreeMap<>();
        LockedEvalSet.CASES.forEach(c -> byTruth.merge(c.expected().safetyTruth(), 1L, Long::sum));
        byTruth.forEach((k, v) -> out.append("    %-14s %3d%n".formatted(k, v)));

        out.append("\n  [기대 노출별]\n");
        Map<String, Long> byExposure = new TreeMap<>();
        LockedEvalSet.CASES.forEach(c -> byExposure.merge(c.expected().exposure(), 1L, Long::sum));
        byExposure.forEach((k, v) -> out.append("    %-16s %3d%n".formatted(k, v)));

        List<String> implemented = LockedEvalSet.VOCABULARY.get("responseActImplemented");
        long pending = LockedEvalSet.CASES.stream()
                .filter(c -> !implemented.contains(c.expected().responseAct()))
                .count();

        out.append("\n  [아직 하지 않은 것 — 생략하지 않고 적는다]\n");
        out.append("    독립 라벨 수            %d / %d (요구)%n".formatted(
                LockedEvalSet.LABELING.independentLabelCount(),
                LockedEvalSet.LABELING.requiredIndependentLabelCount()));
        out.append("    이견률(IAA) 측정        아직 없음\n");
        out.append("    외부 전문가·임상 검수   아직 없음\n");
        out.append("    미구현 responseAct 기대 %d건 (P1-1 확장 대상)%n".formatted(pending));
        out.append("    A~E 셀 실행             이 PR 범위 아님\n");
        out.append("══════════════════════════════════════════════════════════════\n");

        System.out.print(out);
        EvalRunArchive.write("locked-eval-set-composition", archiveMetadata(), out.toString());

        assertThat(LockedEvalSet.CASES).isNotEmpty();
    }

    private Map<String, String> archiveMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("scope", "잠금 평가셋 구성 검증 (모델 호출 없음)");
        metadata.put("dataset", LockedEvalSet.VERSION);
        metadata.put("dataset_size", String.valueOf(LockedEvalSet.CASES.size()));
        metadata.put("dataset_sha256", LockedEvalSet.fileSha256());
        metadata.put("label_guide", "docs/eval/locked-eval-set-labeling-procedure.md");
        metadata.putAll(LockedEvalSet.DATA_RIGHTS.asManifestFields());
        metadata.putAll(LockedEvalSet.LABELING.asManifestFields());
        metadata.put("command",
                "./gradlew test --tests \"com.mio.ai.qa.LockedEvalSetIntegrityTest\"");
        return metadata;
    }

    // ── 보조 ────────────────────────────────────────────────────────

    private Map<String, List<LockedCase>> biasPairs() {
        Map<String, List<LockedCase>> pairs = new LinkedHashMap<>();
        LockedEvalSet.CASES.stream()
                .filter(c -> !c.pairKey().isEmpty())
                .forEach(c -> pairs.computeIfAbsent(c.pairKey(), k -> new ArrayList<>()).add(c));
        return pairs;
    }

    private Map<String, Long> countBySubgroup() {
        Map<String, Long> counts = new LinkedHashMap<>();
        LockedEvalSet.CASES.forEach(c -> counts.merge(c.subgroup(), 1L, Long::sum));
        return counts;
    }

}
