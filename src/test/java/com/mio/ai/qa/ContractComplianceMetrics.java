package com.mio.ai.qa;

import com.mio.ai.plan.ResponseAct;
import com.mio.ai.qa.CellCaseOutcome.ContractOutcome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 계약 준수 실측 지표 (이슈 #305, 로드맵 §5.8).
 *
 * <h2>무엇을 세는가</h2>
 *
 * <p>세 가지다. (1) 응답 행위별 계약 위반율, (2) 위반 유형 분포, (3) 응답 길이·질문 수 분포.
 * 셋 다 {@link CellCaseOutcome} 이 이미 들고 있는 값에서 나온다 — 셀 벤치마크가 계약을 채점하는
 * 방식과 <b>같은 자</b>를 쓴다. 계약을 다르게 재는 두 번째 하네스를 만드는 것은 아무것도 재지
 * 않는 것보다 나쁘다.
 *
 * <h2>모집단</h2>
 *
 * <p>분모는 {@code contract} 가 {@code PASSED} 또는 {@code VIOLATED} 인 턴이다.
 * {@code NOT_APPLICABLE}(계획 밖·고정 응답·본문 없음)과 {@code UNCHECKED}(검사 지점 없는 전달)을
 * 분모에 넣으면 위반율이 실제보다 낮아 보인다.
 *
 * <p><b>이탈은 셋이며 셋을 모두 이름으로 센다</b> (P0-3). ① Judge 위기 승격
 * ({@link #crisisRouted}), ② Judge 보안 의심({@link #unplanned}), ③ 생성 본문 없음
 * ({@link #noBodyEscapes}). ①②는 플래너 층 이탈이라 무과금 게이트({@code ContractEvalSetTest})
 * 가 소진성을 증명하지만, ③은 <b>생성 층</b>이라 그 게이트가 구조적으로 볼 수 없다.
 * {@code #305} 대조군이 {@code 계약 밖 25건} 을 찍고 설명 줄이 모두 0 이었던 것이 ③이다.
 *
 * <p>비율은 전부 {@link ReportableRate} 를 지난다. 하한({@code minSubgroupN=30}) 미달 행위는
 * <b>비율이 계산되지 않는다</b> — P0-8 3단계가 n=12 로 겪은 상황을 다시 만들지 않기 위해서가
 * 아니라, 그 상황이 오더라도 수치가 인용되지 않게 하기 위해서다.
 */
record ContractComplianceMetrics(
        ContractPromptArm arm,
        int cases,
        int applicable,
        int violated,
        int notApplicable,
        int unchecked,
        int generationCalled,
        /** Judge 가 위기로 올려 고정 플로우로 빠진 턴 — 계약 모집단에서 빠진 이유 중 하나. */
        int crisisRouted,
        int securityRefusal,
        /** 계획 범위 밖으로 남은 턴. 룰이 Judge 로 올렸는데도 여기 오면 설계 가정이 틀린 것이다. */
        int unplanned,
        /**
         * 외부 실패가 <b>일어난</b> 턴 수 — {@code acceptance} 라벨과 독립이다 (P0-3).
         *
         * <p>{@code #305} 유료 실행은 이 값을 {@code acceptance == REJECTED_EXTERNAL_FAILURE} 로
         * 셌고, 생성 실패 25건 중 24건이 같은 턴의 판정 실패 라벨에 먹혀 <b>1</b> 을 보고했다.
         * 지금은 {@link CellCaseOutcome#externalFailureObserved()} 를 센다.
         */
        int externalFailures,
        /** 그중 생성(또는 escalation 재생성) 호출이 실패한 턴. 이 턴들이 분모를 빼앗아 간다. */
        int generationFailures,
        /** 그중 판정 호출이 실패한 턴. 분모는 남기지만 계획된 행위를 EMOTION_CHECK 쪽으로 민다. */
        int judgeFailures,
        /** 그중 타임아웃·예외로 케이스 자체가 중단된 턴. */
        int abortedCases,
        int emptyResponses,
        /**
         * 이탈③ — 생성 본문이 없어 계약 검사가 {@code notApplicable()} 을 돌려준 턴 (P0-3).
         *
         * <p>{@code ContractEvalSetTest} 의 "전 케이스가 계약 경로로 간다" 보장은 <b>플래너
         * 층</b>에 한정된다. 그 테스트는 생성을 돌리지 않으므로 이 이탈을 구조적으로 볼 수 없다.
         * {@code #305} 대조군의 {@code 계약 밖 25건} 이 정확히 이것이었고, 리포트가 그 25건을
         * 설명하는 세 줄이 모두 0 이었다.
         */
        int noBodyEscapes,
        Map<ResponseAct, ActStats> byAct,
        Map<String, Integer> violationTypes,
        Shape shape) {

    ContractComplianceMetrics {
        byAct = Map.copyOf(byAct);
        violationTypes = Map.copyOf(violationTypes);
    }

    /**
     * 외부 실패 상한 (비율). 넘으면 이 실행의 수치를 쓰지 않는다.
     *
     * <p>실패한 턴은 계약 모집단에 들어오지 않는다. 실패가 많으면 남은 모집단이 실패하지 않은
     * 쪽으로 치우쳐, 위반율이 네트워크 사정을 재는 값이 된다.
     *
     * <p>가드와 하네스 자체 검사가 <b>같은 상수</b>를 읽는다. 값을 두 곳에 적으면 한쪽만 고쳐도
     * 테스트가 통과해, 가드가 실제로 어디서 트립하는지 아무도 모르게 된다.
     */
    static final double MAX_EXTERNAL_FAILURE_SHARE = 0.10;

    /** 총계 위반율. 행위별로 하한에 못 미쳐도 총계는 낼 수 있는 경우가 있다. */
    ReportableRate violationRate() {
        return ReportableRate.of("계약 위반율(총계)", violated, applicable);
    }

    /**
     * 외부 실패로 잃은 턴의 비율 (P0-3).
     *
     * <p>분모는 시도한 전 케이스다. 남은 모집단을 분모로 쓰면 실패가 늘수록 분모도 줄어 비율이
     * 실제보다 작게 나온다 — 계량기가 자기 실패를 감추는 계산이 된다.
     */
    double externalFailureShare() {
        return cases == 0 ? 0.0 : (double) externalFailures / cases;
    }

    /**
     * 이 실행의 수치를 인용해도 되는가 (외부 실패 기준).
     *
     * <p>{@code #305} 실행은 16.3% 를 잃었고 이 검사가 0.65% 로 읽어 통과했다. 지금은
     * {@link #externalFailures} 가 라벨이 아니라 사실을 세므로 같은 데이터에서 거짓이 된다.
     */
    boolean externalFailureWithinLimit() {
        return externalFailureShare() <= MAX_EXTERNAL_FAILURE_SHARE;
    }

    /**
     * 설명된 이탈의 합 — 이 값이 {@link #notApplicable} 과 어긋나면 이름 없는 이탈이 또 생겼다.
     *
     * <p>{@code #305} 대조군은 {@code 계약 밖 25건} 을 찍고 설명 줄은 모두 0 이었다. 합계를
     * 리포트가 스스로 맞춰 보면 그 상태가 다음 실행에서 조용히 지나가지 않는다.
     */
    int explainedEscapes() {
        return crisisRouted + unplanned + securityRefusal + noBodyEscapes;
    }

    /** 어느 이탈로도 설명되지 않은 계약 밖 턴. 0 이 아니면 리포트가 경고를 찍는다. */
    int unexplainedEscapes() {
        return notApplicable - explainedEscapes();
    }

    /**
     * 한 응답 행위의 계약 성적.
     *
     * @param applicable 이 행위로 계획됐고 실제로 검사된 턴 수
     */
    record ActStats(ResponseAct act, int applicable, int violated,
                    Map<String, Integer> violationTypes, Shape shape) {

        ActStats {
            violationTypes = Map.copyOf(violationTypes);
        }

        ReportableRate violationRate() {
            return ReportableRate.of("계약 위반율(" + act.name() + ")", violated, applicable);
        }
    }

    /**
     * 응답 길이·질문 수 분포 (이슈 #305 완료 조건 셋째).
     *
     * <p>위반율만으로는 "계약이 응답 품질을 떨어뜨렸는가" 에 답할 수 없다. 위반이 0 이어도
     * 응답이 두 문장씩 짧아졌다면 그건 계약이 만든 변화이고, 제품 결정의 근거가 된다.
     *
     * <p>평균만 남기지 않는다 — 상한이 있는 분포에서 평균은 한계에 눌려 차이를 감춘다.
     */
    record Shape(int n, double meanSentences, long p50Sentences, long p90Sentences, long maxSentences,
                 double meanQuestions, long p50Questions, long p90Questions, long maxQuestions) {

        static final Shape EMPTY = new Shape(0, 0, 0, 0, 0, 0, 0, 0, 0);

        static Shape of(List<CellCaseOutcome> outcomes) {
            List<Long> sentences = outcomes.stream()
                    .map(o -> (long) o.responseSentences()).sorted().toList();
            List<Long> questions = outcomes.stream()
                    .map(o -> (long) o.responseQuestions()).sorted().toList();
            if (sentences.isEmpty()) {
                return EMPTY;
            }
            return new Shape(sentences.size(),
                    mean(sentences), CellMetrics.percentile(sentences, 50),
                    CellMetrics.percentile(sentences, 90), sentences.get(sentences.size() - 1),
                    mean(questions), CellMetrics.percentile(questions, 50),
                    CellMetrics.percentile(questions, 90), questions.get(questions.size() - 1));
        }

        private static double mean(List<Long> values) {
            return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
        }
    }

    /**
     * 실행 결과에서 지표를 만든다.
     *
     * <p>본문은 보지 않는다 — {@link CellCaseOutcome} 에 본문이 없기 때문이며, 그것은 의도된
     * 설계다. 리포트로 새어 나갈 경로를 만들지 않는다.
     */
    static ContractComplianceMetrics of(CellRunner.Result result) {
        List<CellCaseOutcome> outcomes = result.outcomes();
        List<CellCaseOutcome> scored = outcomes.stream().filter(ContractComplianceMetrics::scored).toList();

        Map<ResponseAct, ActStats> byAct = new LinkedHashMap<>();
        for (ResponseAct act : ContractEvalSet.CONTRACT_ACTS) {
            List<CellCaseOutcome> actOutcomes = scored.stream()
                    .filter(o -> o.observedResponseAct() == act).toList();
            byAct.put(act, new ActStats(act, actOutcomes.size(),
                    (int) actOutcomes.stream().filter(ContractComplianceMetrics::isViolated).count(),
                    violationTypes(actOutcomes), Shape.of(actOutcomes)));
        }

        return new ContractComplianceMetrics(
                result.variant().contractArm(),
                outcomes.size(),
                scored.size(),
                (int) scored.stream().filter(ContractComplianceMetrics::isViolated).count(),
                count(outcomes, ContractOutcome.NOT_APPLICABLE),
                count(outcomes, ContractOutcome.UNCHECKED),
                (int) outcomes.stream().filter(CellCaseOutcome::generationCalled).count(),
                (int) outcomes.stream()
                        .filter(o -> o.observedResponseAct() == ResponseAct.CRISIS_ASSESSMENT).count(),
                (int) outcomes.stream()
                        .filter(o -> o.observedResponseAct() == ResponseAct.SECURITY_REFUSAL).count(),
                (int) outcomes.stream()
                        .filter(o -> o.observedResponseAct() == ResponseAct.UNPLANNED).count(),
                // 외부 실패는 acceptance 라벨이 아니라 관측된 사실을 센다 (P0-3). 라벨을 세면
                // 같은 턴의 판정 실패가 생성 실패를 덮어쓴 만큼이 집계에서 사라진다.
                (int) outcomes.stream().filter(CellCaseOutcome::externalFailureObserved).count(),
                (int) outcomes.stream().filter(o -> o.externalFailure().generation()).count(),
                (int) outcomes.stream().filter(o -> o.externalFailure().judge()).count(),
                (int) outcomes.stream().filter(o -> o.externalFailure().caseAborted()).count(),
                (int) outcomes.stream()
                        .filter(o -> o.acceptance() == CellCaseOutcome.Acceptance.REJECTED_EMPTY_RESPONSE)
                        .count(),
                (int) outcomes.stream().filter(ContractComplianceMetrics::noBodyEscape).count(),
                byAct, violationTypes(scored), Shape.of(scored));
    }

    /**
     * 이탈③ — 생성 본문이 없어 계약 모집단에서 빠진 턴 (P0-3).
     *
     * <p>둘 중 하나다. (1) 생성 호출이 외부 오류로 실패했다(타임아웃·예외로 케이스가 중단된
     * 경우 포함), (2) 모델이 정상 응답으로 빈 본문을 돌려줬다. 어느 쪽이든
     * {@code ResponseContractValidator} 는 볼 본문이 없어 {@code notApplicable()} 을 돌려준다.
     *
     * <p>{@code contract == NOT_APPLICABLE} 조건을 함께 본다. 판정 호출만 실패한 턴은 생성이
     * 정상으로 돌아 분모에 남으므로 이탈이 아니다 — 그 턴을 여기 세면 이탈 합계가 계약 밖 건수를
     * 넘고, 그러면 합계 검산이 아무것도 잡지 못한다.
     */
    private static boolean noBodyEscape(CellCaseOutcome outcome) {
        return outcome.contract() == ContractOutcome.NOT_APPLICABLE
                && (outcome.externalFailure().removesBody()
                || outcome.acceptance() == CellCaseOutcome.Acceptance.REJECTED_EMPTY_RESPONSE);
    }

    private static boolean scored(CellCaseOutcome outcome) {
        return outcome.contract() == ContractOutcome.PASSED
                || outcome.contract() == ContractOutcome.VIOLATED;
    }

    private static boolean isViolated(CellCaseOutcome outcome) {
        return outcome.contract() == ContractOutcome.VIOLATED;
    }

    private static int count(List<CellCaseOutcome> outcomes, ContractOutcome outcome) {
        return (int) outcomes.stream().filter(o -> o.contract() == outcome).count();
    }

    /**
     * 위반 문자열을 유형으로 접는다.
     *
     * <p>{@code ResponseContractValidator} 는 상한 위반을 {@code max_questions(3>1)} 처럼 실제
     * 수치를 담아 낸다. 그 문자열을 그대로 세면 유형 분포가 아니라 값 분포가 되어, 무엇이 가장
     * 자주 깨지는지 보이지 않는다. 괄호 앞까지가 유형이다.
     */
    private static Map<String, Integer> violationTypes(List<CellCaseOutcome> outcomes) {
        Map<String, Integer> counts = new TreeMap<>();
        for (CellCaseOutcome outcome : outcomes) {
            for (String violation : outcome.contractViolations()) {
                counts.merge(typeOf(violation), 1, Integer::sum);
            }
        }
        return counts;
    }

    static String typeOf(String violation) {
        int paren = violation.indexOf('(');
        return paren < 0 ? violation : violation.substring(0, paren);
    }

    /** 유형 분포를 "많이 깨진 순" 으로 읽기 위한 정렬. 동수는 이름순으로 고정한다. */
    static List<Map.Entry<String, Integer>> sortedByCount(Map<String, Integer> types) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(types.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed().thenComparing(Map.Entry::getKey));
        return List.copyOf(entries);
    }
}
