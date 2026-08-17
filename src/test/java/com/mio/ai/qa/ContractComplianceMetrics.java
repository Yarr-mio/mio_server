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
        int externalFailures,
        int emptyResponses,
        Map<ResponseAct, ActStats> byAct,
        Map<String, Integer> violationTypes,
        Shape shape) {

    ContractComplianceMetrics {
        byAct = Map.copyOf(byAct);
        violationTypes = Map.copyOf(violationTypes);
    }

    /** 총계 위반율. 행위별로 하한에 못 미쳐도 총계는 낼 수 있는 경우가 있다. */
    ReportableRate violationRate() {
        return ReportableRate.of("계약 위반율(총계)", violated, applicable);
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
                (int) outcomes.stream()
                        .filter(o -> o.acceptance() == CellCaseOutcome.Acceptance.REJECTED_EXTERNAL_FAILURE)
                        .count(),
                (int) outcomes.stream()
                        .filter(o -> o.acceptance() == CellCaseOutcome.Acceptance.REJECTED_EMPTY_RESPONSE)
                        .count(),
                byAct, violationTypes(scored), Shape.of(scored));
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
