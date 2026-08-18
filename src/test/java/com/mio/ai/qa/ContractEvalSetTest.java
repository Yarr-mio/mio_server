package com.mio.ai.qa;

import com.mio.ai.input.InputNormalizer;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.plan.ResponsePlanner;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.policy.PolicyEngine;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 * <h2>주석이 아니라 실행으로 증명한다</h2>
 *
 * <p>이 테스트의 초판은 룰 레이어까지만 재구성하고 그 뒤는 <b>주석 수준 사례 분석</b>으로
 * 때웠다. 그건 현재 브랜치의 분기 순서를 스냅숏으로 옮겨 적은 것이지 검사가 아니다.
 * 지금은 케이스마다 합성 {@link InputJudgeResult} 로 <b>실제</b> {@link PolicyEngine} 과
 * {@link ResponsePlanner} 를 통과시킨다 — 등급({@code HARD_CRISIS·HIGH·MEDIUM·LOW·CLEAR_LOW})
 * × Judge 보안 판정({@code CLEAN·SUSPICIOUS}) + 호출 실패, 총 11칸.
 *
 * <h2>보장의 실제 범위 — <b>플래너 층까지</b>이고, 그 층의 이탈은 둘이다</h2>
 *
 * <p><b>이 테스트가 검사하는 층을 먼저 적는다.</b> 여기서 도는 것은 정규화 → 보안 룰 →
 * SafetyL1 → 신호 결합 → (합성 Judge) → {@link PolicyEngine} → {@link ResponsePlanner} 까지다.
 * <b>생성은 돌지 않는다.</b> 그래서 이 테스트의 소진성 증명은 "계획이 계약 행위로 가는가" 에
 * 대한 것이고, "그 계획으로 생성한 응답이 계약 검사까지 도달하는가" 는 다루지 않는다.
 * 그 구별은 {@code #305} 실행이 실제로 요구했다 — 아래 "이 테스트가 볼 수 없는 이탈" 을 본다.
 *
 * <p>Judge 가 위기로 올리지도 의심하지도 않으면({@link JudgeCell#benign()}) 전 케이스가 세 계약
 * 행위 중 하나로 간다.
 *
 * <ul>
 *   <li>Judge HIGH → {@code EMPATHIC_REFLECTION}</li>
 *   <li>Judge MEDIUM 또는 판정 실패 → {@code EMOTION_CHECK}</li>
 *   <li>Judge LOW 이하 → 룰 승격이 남아 {@code SUPPORTIVE} → {@code CLARIFY_CONTEXT}</li>
 * </ul>
 *
 * <p>이탈은 정확히 둘이며 둘 다 <b>Judge 가 내리는 판정</b>이라 무과금으로 닫을 수 없다.
 *
 * <ol>
 *   <li><b>Judge {@code HARD_CRISIS}</b> → 위기 고정 플로우. 실행이 {@code crisis_routed} 로 센다.</li>
 *   <li><b>Judge 보안 판정 non-CLEAN</b> → 룰이 {@code CLEAN} 이어도
 *       {@code EffectiveSecurityResolver} 가 {@code SUSPICIOUS} 로 올리고, 등급이 {@code LOW}
 *       이하면 {@code PolicyEngine} 6번 분기가 {@code GUARDED} 를 만들어
 *       {@code planGeneration} 이 {@code unplanned()} 로 떨어진다. 실행이 {@code unplanned_turns}
 *       로 센다. (등급이 HIGH·MEDIUM 이면 5·6번 분기가 먼저 걸려 계약이 유지된다.)</li>
 * </ol>
 *
 * <p>그래서 이 세트의 보장은 <b>"플래너 층까지, Judge 의 위기 승격과 보안 판정 modulo"</b> 다.
 * 무조건적 보장이 아니다. 대신 {@link #theJudgeMatrixHasExactlyTwoNamedEscapes()} 가 <b>플래너
 * 층에 이름 없는 이탈이 생기면 실패</b>하므로, 분기 순서가 바뀌어 이 층의 보장이 조용히 좁아지는
 * 일은 막는다.
 *
 * <h2>이 테스트가 볼 수 없는 이탈 — 이탈③ 생성 본문 없음 (P0-3)</h2>
 *
 * <p>이 테스트는 생성을 돌리지 않으므로, 계획까지 정상으로 갔다가 <b>생성이 본문을 내지 못해</b>
 * 모집단에서 빠지는 이탈을 <b>구조적으로</b> 잡을 수 없다. 본문이 없으면
 * {@code ResponseContractValidator} 가 {@code notApplicable()} 을 돌려주고 그 턴은 위반도 준수도
 * 아닌 채 분모에서 사라진다. 이것은 이 테스트의 결함이 아니라 <b>범위</b>다 — 생성을 돌리려면
 * 모델을 불러야 하고, 그러면 이 테스트는 "지불 전 게이트" 가 아니게 된다.
 *
 * <p>{@code #305} 유료 실행의 대조군이 정확히 그 상태였다. {@code 계약 밖 25건} 을 찍었는데
 * 리포트가 그 25건을 설명하는 세 줄이 모두 0 이었고, 25건 전부가 생성 호출 실패였다. 그래서
 * <b>실행 쪽</b>이 그 이탈에 이름을 붙여 세고({@code no_body_escapes}) 이탈 합계를 계약 밖
 * 건수와 검산한다({@code ContractComplianceMetrics.unexplainedEscapes}). 이 테스트가 보장하는
 * 것과 실행이 관측해야 하는 것을 이렇게 나눠 둔다 — 여기서 "세 번째 이탈이 없다" 를 주장하면
 * 그 주장은 생성 층에 대해 근거가 없다.
 *
 * <p>행위별 분포는 여전히 보장하지 않는다 — 그것은 Judge 판정이 정하며, 하한 미달 행위의
 * 비율은 {@link ReportableRate} 가 막는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] 계약 준수 평가셋 — 모집단 설계와 무결성 (모델 호출 없음)")
class ContractEvalSetTest {

    private final InputNormalizer normalizer = new InputNormalizer();
    private final SecurityRuleFilter securityFilter = new SecurityRuleFilter();
    private final SafetyL1 safetyL1 = new SafetyL1(normalizer);
    private final SafetySignalCombiner combiner = new SafetySignalCombiner();
    private final UserMessageSignalAnalyzer signalAnalyzer = new UserMessageSignalAnalyzer();
    private final PolicyEngine policyEngine =
            new PolicyEngine(new com.mio.ai.security.EffectiveSecurityResolver());
    private final ResponsePlanner responsePlanner = new ResponsePlanner();

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

    // ── 실행 가능한 소진성 증명 ─────────────────────────────────────

    /**
     * 케이스 하나가 한 Judge 판정에서 도달하는 <b>플래너 층</b>의 자리.
     *
     * <p>{@link #OTHER} 가 하나라도 나오면 이 세트의 모집단 논증에 <b>플래너 층의 이름 없는
     * 이탈</b>이 생겼다는 뜻이다. 그때 고쳐야 하는 것은 테스트가 아니라 논증이다.
     *
     * <p>여기 없는 이탈이 하나 더 있다 — 생성이 본문을 내지 못해 분모에서 빠지는 이탈③. 이
     * enum 은 생성을 돌지 않는 층의 어휘라 그 값을 담지 않는다. 실행 쪽이
     * {@code no_body_escapes} 로 센다 (P0-3, 클래스 javadoc).
     */
    private enum PlanOutcome {
        /** 세 계약 행위 중 하나로 계획됐고 계약 검사가 걸린다. */
        CONTRACT_ACT,
        /** Judge 가 위기로 올려 고정 플로우로 빠졌다. 실행이 {@code crisis_routed} 로 센다. */
        ESCAPE_JUDGE_HARD_CRISIS,
        /** Judge 자신의 보안 판정이 non-CLEAN 이라 GUARDED 로 가고 계획 범위 밖이 됐다. */
        ESCAPE_JUDGE_SECURITY,
        /** 위 어디에도 속하지 않는다 — 논증에 없는 경로다. */
        OTHER
    }

    /** 합성 Judge 판정 한 칸. 등급 × 보안 판정 × 호출 실패의 전 조합을 만든다. */
    private record JudgeCell(String label, InputJudgeResult result,
                             RiskLevel risk, SecurityLevel judgeSecurity, boolean failed) {

        /**
         * 두 이탈 조건 어디에도 해당하지 않는 칸.
         *
         * <p>호출 실패({@code failed})는 <b>이탈이 아니다.</b> {@code PolicyEngine} 4번 분기가
         * 다른 모든 생성 분기보다 먼저 {@code MEDIUM} 을 세우므로 {@code EMOTION_CHECK} 로
         * 확정된다. 판정을 못 받은 턴을 이탈로 세면 보장 범위를 실제보다 좁게 적게 된다.
         */
        boolean benign() {
            return risk != RiskLevel.HARD_CRISIS && judgeSecurity == SecurityLevel.CLEAN;
        }
    }

    /**
     * Judge 가 낼 수 있는 판정의 전 조합.
     *
     * <p>{@code ATTACK} 은 risk 축의 값이 아니라 보안 축의 값이며 {@code hasUsableJudgeResult} 가
     * 판정 실패로 접으므로, 실패 칸이 그 경우를 대표한다.
     */
    private static List<JudgeCell> judgeMatrix() {
        List<RiskLevel> risks = List.of(RiskLevel.HARD_CRISIS, RiskLevel.HIGH, RiskLevel.MEDIUM,
                RiskLevel.LOW, RiskLevel.CLEAR_LOW);
        List<SecurityLevel> securities = List.of(SecurityLevel.CLEAN, SecurityLevel.SUSPICIOUS);
        List<JudgeCell> cells = new ArrayList<>();
        for (RiskLevel risk : risks) {
            for (SecurityLevel security : securities) {
                cells.add(new JudgeCell(
                        "%s/%s".formatted(risk, security),
                        new InputJudgeResult(
                                new SecurityVerdict(security, List.of(), security != SecurityLevel.CLEAN),
                                new RiskVerdict(risk, List.of(), GenerationMode.SUPPORTIVE,
                                        DeliveryMode.CAUTIOUS_SPECULATIVE, false, null),
                                0.9),
                        risk, security, false));
            }
        }
        // 호출 실패 폴백. PolicyEngine 4번 분기가 다른 어떤 분기보다 먼저 걸린다.
        cells.add(new JudgeCell("FAILED", InputJudgeResult.fallback(),
                RiskLevel.CLEAR_LOW, SecurityLevel.CLEAN, true));
        return List.copyOf(cells);
    }

    @Test
    @DisplayName("등급×보안판정 전 조합을 실제 PolicyEngine·ResponsePlanner 에 통과시킨다 — 플래너 층의 이탈은 딱 둘뿐이다")
    void theJudgeMatrixHasExactlyTwoNamedEscapes() {
        List<JudgeCell> matrix = judgeMatrix();
        Map<PlanOutcome, Integer> census = new EnumMap<>(PlanOutcome.class);
        Map<String, String> unexplained = new LinkedHashMap<>();
        Set<String> escapeCells = new TreeSet<>();

        for (LockedCase c : ContractEvalSet.CASES) {
            CombinedSignal combined = ruleLayer(c);
            for (JudgeCell cell : matrix) {
                PlanOutcome outcome = outcomeOf(combined, cell);
                census.merge(outcome, 1, Integer::sum);
                if (outcome == PlanOutcome.OTHER) {
                    unexplained.put("%s @ %s".formatted(c.id(), cell.label()), describe(combined, cell));
                } else if (outcome != PlanOutcome.CONTRACT_ACT) {
                    escapeCells.add("%s → %s".formatted(cell.label(), outcome));
                }
            }
        }

        System.out.printf("%n[contract-set] 판정 행렬 %d칸 × %d건 = %d조합 · 결과 %s%n",
                matrix.size(), ContractEvalSet.CASES.size(),
                matrix.size() * ContractEvalSet.CASES.size(), census);
        escapeCells.forEach(cell -> System.out.printf("  이탈 칸: %s%n", cell));

        assertThat(unexplained)
                .as("플래너 층 논증에 이름이 없는 이탈 경로가 생겼다. PolicyEngine·ResponsePlanner 의 "
                        + "분기 순서가 바뀌었을 가능성이 높고, 그러면 '지불 전 보장' 문장을 먼저 고쳐야 "
                        + "한다. (생성 층의 이탈③은 이 테스트의 범위가 아니다 — 클래스 javadoc):%n  %s",
                        unexplained)
                .isEmpty();
        assertThat(census.getOrDefault(PlanOutcome.ESCAPE_JUDGE_HARD_CRISIS, 0))
                .as("Judge 위기 승격 이탈이 사라졌다면 그것도 논증이 바뀐 것이다")
                .isPositive();
        assertThat(census.getOrDefault(PlanOutcome.ESCAPE_JUDGE_SECURITY, 0))
                .as("Judge 보안 판정 이탈이 사라졌다면 EffectiveSecurityResolver 나 "
                        + "PolicyEngine 6번 분기가 바뀐 것이다 — 보장 문구를 다시 넓힐 수 있다")
                .isPositive();
    }

    @Test
    @DisplayName("Judge 가 CLEAN·비위기로 판정하면 전 케이스가 계약 행위로 간다 — 이것이 보장의 실제 범위다")
    void benignJudgeVerdictsAlwaysLandOnAContractAct() {
        List<JudgeCell> benign = judgeMatrix().stream().filter(JudgeCell::benign).toList();
        Map<String, String> offenders = new LinkedHashMap<>();

        for (LockedCase c : ContractEvalSet.CASES) {
            CombinedSignal combined = ruleLayer(c);
            for (JudgeCell cell : benign) {
                if (outcomeOf(combined, cell) != PlanOutcome.CONTRACT_ACT) {
                    offenders.put("%s @ %s".formatted(c.id(), cell.label()), describe(combined, cell));
                }
            }
        }

        System.out.printf("[contract-set] 비위기·CLEAN 판정 %d칸에서 계약 이탈 %d건%n",
                benign.size(), offenders.size());

        assertThat(offenders)
                .as("Judge 가 위기로 올리지도 의심하지도 않았는데 계약 밖으로 나가는 케이스가 있다:%n  %s",
                        offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("보장 모집단이 총계와 하위 그룹 모두에서 보고 하한을 넘는다")
    void guaranteedPopulationClearsTheFloorPerSubgroupToo() {
        List<JudgeCell> benign = judgeMatrix().stream().filter(JudgeCell::benign).toList();
        Map<String, Long> guaranteedBySubgroup = new TreeMap<>();
        long total = 0;

        for (LockedCase c : ContractEvalSet.CASES) {
            CombinedSignal combined = ruleLayer(c);
            boolean guaranteed = benign.stream()
                    .allMatch(cell -> outcomeOf(combined, cell) == PlanOutcome.CONTRACT_ACT);
            if (guaranteed) {
                guaranteedBySubgroup.merge(c.subgroup(), 1L, Long::sum);
                total++;
            }
        }

        System.out.printf("[contract-set] 보장 모집단 총계 %d건 · 하위 그룹별 %s (보고 하한 %d)%n",
                total, guaranteedBySubgroup, LockedEvalSet.REPORTING.minSubgroupN());

        assertThat(ReportableRate.of("보장 모집단(총계)", 0, total))
                .as("총계조차 하한을 못 넘는 세트는 P0-8 3단계와 같은 결과(건수만 인용)로 끝난다")
                .isInstanceOf(ReportableRate.Reported.class);
        assertThat(guaranteedBySubgroup)
                .as("하위 그룹 하나가 하한 아래로 내려가면 그 행위의 위반율만 조용히 사라진다. "
                        + "총계만 보면 그 소실이 보이지 않는다")
                .allSatisfy((subgroup, n) -> assertThat(n)
                        .as("하위 그룹 %s", subgroup)
                        .isGreaterThanOrEqualTo(LockedEvalSet.REPORTING.minSubgroupN()));
    }

    private PlanOutcome outcomeOf(CombinedSignal combined, JudgeCell cell) {
        PolicyDecision decision = policyEngine.decide(combined, cell.result(), null, null);
        ResponsePlan plan = responsePlanner.plan(decision);
        if (plan.isContractEnforced() && ContractEvalSet.CONTRACT_ACTS.contains(plan.responseAct())) {
            return PlanOutcome.CONTRACT_ACT;
        }
        if (decision.action() == DecisionAction.CRISIS_FLOW && cell.risk() == RiskLevel.HARD_CRISIS) {
            return PlanOutcome.ESCAPE_JUDGE_HARD_CRISIS;
        }
        if (cell.judgeSecurity() != SecurityLevel.CLEAN
                && plan.responseAct() == ResponseAct.UNPLANNED) {
            return PlanOutcome.ESCAPE_JUDGE_SECURITY;
        }
        return PlanOutcome.OTHER;
    }

    private String describe(CombinedSignal combined, JudgeCell cell) {
        PolicyDecision decision = policyEngine.decide(combined, cell.result(), null, null);
        return "action=%s mode=%s risk=%s → act=%s".formatted(
                decision.action(), decision.generationMode(), decision.riskLevel(),
                responsePlanner.plan(decision).responseAct());
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

    /**
     * 문체가 한 템플릿으로 수렴하지 않았는지 (리뷰 MEDIUM-2).
     *
     * <p>초판 120건은 존댓말 98.3%·이모지 0·전부 단일 턴·길이 18~46자였다 — 잠금셋 리뷰가
     * 이미 지적한 것과 같은 패턴이고, 그런 세트로 잰 위반율은 실제 트래픽으로 일반화되기
     * 어렵다. 문체를 섞은 뒤 그 사실을 <b>파생값으로 검사</b>한다. 라벨로 적어 두면 본문을
     * 고쳐도 라벨은 그대로 남아 검사가 거짓말을 하게 된다.
     *
     * <p>하한만 건다. 실제 트래픽 분포를 재현했다고 주장하지 않으며, 그 한계는 리포트와 운영
     * 문서가 명시한다.
     */
    @Test
    @DisplayName("문체가 한 템플릿으로 수렴하지 않았다 — 반말·이모지·장문·멀티턴이 섞여 있다")
    void registerIsNotCollapsedIntoOneTemplate() {
        long informal = 0;
        long withEmoji = 0;
        long multiTurn = 0;
        long longForm = 0;
        for (LockedCase c : ContractEvalSet.CASES) {
            String last = c.userTurns().get(c.userTurns().size() - 1).text();
            if (!endsPolitely(last)) {
                informal++;
            }
            if (containsEmoji(last)) {
                withEmoji++;
            }
            if (c.turns().size() > 1) {
                multiTurn++;
            }
            if (last.length() >= 80) {
                longForm++;
            }
        }
        int total = ContractEvalSet.CASES.size();

        System.out.printf("[contract-set] 문체 구성 — 반말 %d/%d(%.1f%%) · 이모지 %d · 멀티턴 %d · 80자 이상 %d%n",
                informal, total, informal * 100.0 / total, withEmoji, multiTurn, longForm);

        assertThat(informal)
                .as("존댓말만 있는 세트로 잰 길이·질문 수 분포는 반말 트래픽으로 일반화되지 않는다")
                .isGreaterThanOrEqualTo(20);
        assertThat(withEmoji)
                .as("이모지는 문장 종결 판정과 길이 계수에 영향을 줄 수 있는 실제 입력 형태다")
                .isGreaterThanOrEqualTo(8);
        assertThat(multiTurn)
                .as("전부 단일 턴이면 이전 맥락이 있는 실제 대화의 계약 준수를 재지 못한다")
                .isGreaterThanOrEqualTo(9);
        assertThat(longForm)
                .as("짧은 발화만 있으면 '길게 쓴 사용자에게도 계약을 지키는가' 를 재지 못한다")
                .isGreaterThanOrEqualTo(6);
    }

    /** 존댓말 종결인지. 이모지·구두점을 걷어낸 마지막 글자만 본다. */
    private static boolean endsPolitely(String text) {
        String trimmed = text.replaceAll("[\\s\\p{Punct}\\p{So}\\p{Cf}]+$", "");
        return trimmed.endsWith("요") || trimmed.endsWith("니다") || trimmed.endsWith("까");
    }

    private static boolean containsEmoji(String text) {
        return text.codePoints().anyMatch(cp ->
                (cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF));
    }

    @Test
    @DisplayName("케이스 id 와 본문에 중복이 없다")
    void casesAreUnique() {
        assertThat(ContractEvalSet.CASES.stream().map(LockedCase::id).distinct().count())
                .isEqualTo(ContractEvalSet.CASES.size());
        // 중복 판정은 <b>마지막 턴</b> 기준이다 — 룰 레이어가 보는 발화가 그것이고,
        // 앞선 턴이 겹치는 것은 자연스러운 대화 맥락이지 중복이 아니다.
        assertThat(ContractEvalSet.CASES.stream()
                .map(c -> LockedEvalSet.normalize(
                        c.userTurns().get(c.userTurns().size() - 1).text()))
                .distinct().count())
                .isEqualTo(ContractEvalSet.CASES.size());
    }

    @Test
    @DisplayName("잠금 gold 와 근사 중복이 아니다 — 잠금셋의 튜닝 미노출 진술을 지킨다")
    void doesNotNearDuplicateTheLockedSet() {
        double worst = 0.0;
        String worstPair = "없음";
        for (LockedCase mine : ContractEvalSet.CASES) {
            // 멀티턴 케이스는 모든 턴을 검사한다 — 앞 턴만 베껴 와도 오염이다.
            for (LockedEvalSet.Turn mineTurn : mine.userTurns()) {
                String a = LockedEvalSet.normalize(mineTurn.text());
                for (LockedCase locked : LockedEvalSet.CASES) {
                    for (LockedEvalSet.Turn turn : locked.userTurns()) {
                        double similarity =
                                LockedEvalSet.similarity(a, LockedEvalSet.normalize(turn.text()));
                        if (similarity > worst) {
                            worst = similarity;
                            worstPair = "%s ↔ %s".formatted(mine.id(), locked.id());
                        }
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
