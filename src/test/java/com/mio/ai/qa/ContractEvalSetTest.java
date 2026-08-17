package com.mio.ai.qa;

import com.mio.ai.input.InputNormalizer;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.qa.LockedEvalSet.LockedCase;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1;
import com.mio.ai.safety.SafetyL1HistoryMessage;
import com.mio.ai.safety.SafetyL1Input;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.safety.SafetySignalCombiner;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.safety.UserMessageSignalAnalyzer;
import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.SecurityLevel;
import com.mio.ai.input.SecurityRuleFilter;
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
 * 계약 평가셋의 무결성과 <b>모집단 설계</b> 검사 (이슈 #305). 모델을 한 번도 부르지 않는다.
 *
 * <h2>왜 무과금 테스트가 모집단을 검사해야 하는가</h2>
 *
 * <p>P0-8 3단계는 잠금 323건을 실제로 돌린 <b>뒤에야</b> 계약 적용 턴이 12건이라는 것을 알았다.
 * 그때는 이미 돈을 썼고, 얻은 것은 "비율을 낼 수 없다" 는 문장이었다. 계약 적용 여부를 정하는
 * 조건 중 <b>룰 레이어 부분은 결정론적</b>이므로, 그 부분은 실행 전에 검사할 수 있다.
 *
 * <p>검사하는 명제는 이것이다 — <b>"이 케이스는 룰 레이어가 InputJudge 로 올리고, 위기·보안
 * 공격으로 확정하지 않는다."</b> 그것이 참이면 {@code PolicyEngine} 은 {@code GENERATE} 로 가고
 * {@code ResponsePlanner} 는 Judge 등급에 따라 세 계약 행위 중 하나를 계획한다.
 *
 * <ul>
 *   <li>Judge HIGH → {@code EMPATHIC_REFLECTION}</li>
 *   <li>Judge MEDIUM 또는 판정 실패 → {@code EMOTION_CHECK}</li>
 *   <li>Judge LOW 이하 → 룰 승격이 남아 {@code SUPPORTIVE} → {@code CLARIFY_CONTEXT}</li>
 * </ul>
 *
 * <p>이탈은 Judge 가 {@code HARD_CRISIS} 로 올리는 경우 하나뿐이고, 그건 실행이 세어 보고한다.
 * 즉 이 테스트는 <b>총계 모집단의 하한</b>을 실행 전에 보장한다. 행위별 분포는 보장하지 않는다 —
 * 그것은 Judge 판정이 정하며, 하한 미달 행위의 비율은 {@link ReportableRate} 가 막는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] 계약 준수 평가셋 — 모집단 설계와 무결성 (모델 호출 없음)")
class ContractEvalSetTest {

    private final InputNormalizer normalizer = new InputNormalizer();
    private final SecurityRuleFilter securityFilter = new SecurityRuleFilter();
    private final SafetyL1 safetyL1 = new SafetyL1(normalizer);
    private final SafetySignalCombiner combiner = new SafetySignalCombiner();
    private final UserMessageSignalAnalyzer signalAnalyzer = new UserMessageSignalAnalyzer();

    @Test
    @DisplayName("모든 케이스가 룰 레이어에서 계약 대상 경로로 간다 — 실행 전에 확인한다")
    void everyCaseReachesTheContractPath() {
        Map<String, String> offenders = new LinkedHashMap<>();
        for (LockedCase c : ContractEvalSet.CASES) {
            CombinedSignal combined = ruleLayer(c);
            String reason = disqualification(combined);
            if (reason != null) {
                offenders.put(c.id(), reason);
            }
        }

        System.out.printf("%n[contract-set] %d건 중 룰 레이어 실격 %d건%n",
                ContractEvalSet.CASES.size(), offenders.size());

        assertThat(offenders)
                .as("계약 대상이 되지 못하는 케이스는 청구서만 늘리고 모집단에는 들어오지 않는다. "
                        + "실격 사유:%n  %s", offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("보장되는 모집단이 보고 하한을 넘는다 — 총계 위반율은 낼 수 있다")
    void guaranteedPopulationClearsTheReportingFloor() {
        long qualified = ContractEvalSet.CASES.stream()
                .filter(c -> disqualification(ruleLayer(c)) == null)
                .count();

        System.out.printf("[contract-set] 룰 레이어가 보장하는 계약 모집단 하한 %d건 (보고 하한 %d)%n",
                qualified, LockedEvalSet.REPORTING.minSubgroupN());

        assertThat(ReportableRate.of("보장 모집단", 0, qualified))
                .as("총계조차 하한을 못 넘는 세트는 P0-8 3단계와 같은 결과(건수만 인용)로 끝난다")
                .isInstanceOf(ReportableRate.Reported.class);
    }

    @Test
    @DisplayName("설계 의도 행위마다 하한 이상을 배정했다 — 행위별 비율의 필요조건")
    void eachIntendedActIsOversampledPastTheFloor() {
        Map<String, Long> byIntent = new TreeMap<>();
        ContractEvalSet.CASES.forEach(c ->
                byIntent.merge(c.expected().responseAct(), 1L, Long::sum));

        System.out.printf("[contract-set] 설계 의도 행위별 배정 %s%n", byIntent);

        assertThat(byIntent.keySet())
                .as("계약이 걸리지 않는 행위를 의도로 적으면 그 케이스는 모집단을 만들지 못한다")
                .containsExactlyInAnyOrderElementsOf(
                        ContractEvalSet.CONTRACT_ACTS.stream().map(Enum::name).toList());
        assertThat(byIntent.values())
                .as("배정이 하한 미만이면 그 행위의 비율은 애초에 나올 수 없다. "
                        + "다만 배정은 필요조건일 뿐 — 실제 행위는 InputJudge 가 정한다")
                .allSatisfy(n -> assertThat(n)
                        .isGreaterThanOrEqualTo(LockedEvalSet.REPORTING.minSubgroupN()));
    }

    @Test
    @DisplayName("선언한 분포와 실제 케이스 수가 같다")
    void declaredDistributionMatchesTheCases() {
        Map<String, Long> actual = new TreeMap<>();
        ContractEvalSet.CASES.forEach(c -> actual.merge(c.subgroup(), 1L, Long::sum));

        assertThat(actual)
                .containsExactlyInAnyOrderEntriesOf(
                        new TreeMap<>(ContractEvalSet.intendedDistribution().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, e -> (long) e.getValue()))));
    }

    @Test
    @DisplayName("케이스 id 와 본문에 중복이 없다")
    void casesAreUnique() {
        assertThat(ContractEvalSet.CASES.stream().map(LockedCase::id).distinct().count())
                .isEqualTo(ContractEvalSet.CASES.size());
        assertThat(ContractEvalSet.CASES.stream()
                .map(c -> LockedEvalSet.normalize(c.turns().get(0).text()))
                .distinct().count())
                .isEqualTo(ContractEvalSet.CASES.size());
    }

    @Test
    @DisplayName("잠금 gold 와 근사 중복이 아니다 — 잠금셋의 튜닝 미노출 진술을 지킨다")
    void doesNotNearDuplicateTheLockedSet() {
        double worst = 0.0;
        String worstPair = "없음";
        for (LockedCase mine : ContractEvalSet.CASES) {
            String a = LockedEvalSet.normalize(mine.turns().get(0).text());
            for (LockedCase locked : LockedEvalSet.CASES) {
                for (LockedEvalSet.Turn turn : locked.userTurns()) {
                    double similarity = LockedEvalSet.similarity(a, LockedEvalSet.normalize(turn.text()));
                    if (similarity > worst) {
                        worst = similarity;
                        worstPair = "%s ↔ %s".formatted(mine.id(), locked.id());
                    }
                }
            }
        }

        System.out.printf("[contract-set] 잠금 gold 대비 최대 유사도 %.3f (%s, 임계 %.2f)%n",
                worst, worstPair, LockedEvalContaminationScanner.SIMILARITY_THRESHOLD);

        assertThat(worst)
                .as("이 세트는 프롬프트 튜닝에 쓴다. 잠금 케이스를 베껴 오면 잠금셋이 "
                        + "프롬프트 피팅에 노출되고, NEVER_USED 진술이 거짓이 된다 (%s)", worstPair)
                .isLessThan(LockedEvalContaminationScanner.SIMILARITY_THRESHOLD);
    }

    @Test
    @DisplayName("출처 진술이 실행 manifest 어휘로 그대로 옮겨진다")
    void provenanceTranslatesIntoManifestVocabulary() {
        assertThat(ContractEvalSet.tuningExposure())
                .as("이 세트의 결과는 프롬프트 결정의 근거다 — 용도가 튜닝이면 튜닝 노출로 적는다. "
                        + "사유: %s", ContractEvalSet.tuningExposureReason())
                .isEqualTo(EvalRunManifest.TuningExposure.USED_FOR_TUNING);
        assertThat(ContractEvalSet.DATA_RIGHTS.asManifestDataRights())
                .isEqualTo(EvalRunManifest.DataRights.PRIORITY_USE);
        assertThat(ContractEvalSet.LABELING.meetsRoadmapRequirement())
                .as("2인 독립 라벨과 이견률은 아직 없다. 그 사실이 기록에 남아야 한다")
                .isFalse();
        assertThat(ContractEvalSet.lockState()).isEqualTo("UNLOCKED");
    }

    @Test
    @DisplayName("이 세트로는 잠금 split 을 주장할 수 없다 — manifest 가 생성 단계에서 막는다")
    void cannotBeDeclaredAsLockedGold() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new EvalRunManifest(
                "계약 준수 실측", "계약 준수 실측", ContractEvalSet.VERSION,
                EvalRunManifest.DatasetSplit.LOCKED_GOLD, ContractEvalSet.CASES.size(),
                ContractEvalSet.LABEL_GUIDE,
                ContractEvalSet.DATA_RIGHTS.asManifestDataRights(),
                ContractEvalSet.tuningExposure(),
                Map.of("generation", "gpt-4o"), EvalRunManifest.UNVERSIONED, "v2.0-phase2",
                "2026-08-17", "454", "./gradlew test -PllmTests", Map.of(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locked_gold");
    }

    // ── 룰 레이어 재구성 ───────────────────────────────────────────

    /**
     * {@link CellRunner#evaluate} 의 앞부분과 같은 순서로 룰 레이어만 돌린다.
     *
     * <p>여기서 하네스를 흉내 내는 것이 아니라 <b>같은 프로덕션 컴포넌트</b>를 같은 순서로
     * 부른다. 이 테스트가 통과하는데 실행에서 다른 경로로 가면 그건 하네스의 결함이다.
     */
    private CombinedSignal ruleLayer(LockedCase c) {
        List<LockedEvalSet.Turn> userTurns = c.userTurns();
        String current = userTurns.get(userTurns.size() - 1).text();
        String normalized = normalizer.normalize(current);
        UserMessageSignal signal = signalAnalyzer.analyze(normalized);
        SecurityAssessment security = securityFilter.check(normalized, current);
        SafetyL1Result l1 = safetyL1.check(new SafetyL1Input(normalized, history(userTurns),
                ModerationResult.clear(), null, signal.emotionScore(), signal.biasType()));
        return combiner.combine(security, l1, ModerationResult.clear(), null);
    }

    private List<SafetyL1HistoryMessage> history(List<LockedEvalSet.Turn> userTurns) {
        List<SafetyL1HistoryMessage> history = new ArrayList<>();
        for (int i = 0; i < userTurns.size() - 1; i++) {
            String normalized = normalizer.normalize(userTurns.get(i).text());
            UserMessageSignal signal = signalAnalyzer.analyze(normalized);
            history.add(new SafetyL1HistoryMessage(normalized, signal.emotionScore(), signal.biasType()));
        }
        return history;
    }

    /** 계약 모집단에 못 들어가는 사유. {@code null} 이면 어떤 Judge 등급에서도 계약이 걸린다. */
    private String disqualification(CombinedSignal combined) {
        if (combined.hardCrisis()) {
            return "룰이 위기로 확정 — 고정 플로우라 생성이 없다";
        }
        if (combined.securityLevel() == SecurityLevel.ATTACK) {
            return "보안 ATTACK — 고정 거절 또는 위기 플로우";
        }
        if (combined.securityLevel() == SecurityLevel.SUSPICIOUS) {
            return "보안 SUSPICIOUS — GUARDED 로 가지만 계획 범위 밖(unplanned)이다";
        }
        if (combined.hardCrisisUnverified()) {
            return "위기 후보 미검증 — Judge 가 해제하지 않으면 위기 플로우로 간다";
        }
        if (!combined.requiresJudge()) {
            return "룰이 Judge 로 올리지 않는다 — CLEAR_LOW·NORMAL 로 흘러 계획 범위 밖이다";
        }
        return null;
    }

    /** 계약 행위 어휘가 프로덕션 enum 과 어긋나면 세트가 조용히 빈 모집단을 만든다. */
    @Test
    @DisplayName("설계 의도 행위가 프로덕션 ResponseAct 어휘 안이다")
    void intendedActsExistInProduction() {
        assertThat(ContractEvalSet.CASES.stream().map(c -> c.expected().responseAct()).distinct())
                .allSatisfy(act -> assertThat(ResponseAct.valueOf(act)).isNotNull());
    }
}
