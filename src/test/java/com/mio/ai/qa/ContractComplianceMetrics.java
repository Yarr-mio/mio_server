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
        /**
         * 이탈 <b>분할</b> — 계약 밖 턴 하나가 정확히 한 자리에만 들어간다 (P0-3 MEDIUM-1).
         *
         * <p>{@link Escape} 참조. 예전에는 네 개의 독립 술어로 각각 세어 한 턴이 두 자리에
         * 들어갈 수 있었다.
         */
        Map<Escape, Integer> escapes,
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
         * CBT 분류 실패 (P0-3 MEDIUM-2).
         *
         * <p><b>{@link CellCaseOutcome.ExternalFailure} 축에는 들어가지 않는다</b> — 분류 호출은
         * 전달·수용이 끝난 뒤에 일어나므로 계약 분모를 줄이지도, 계획된 행위 분포를 밀지도 않는다.
         * 그래도 manifest 에 싣는 이유는 그 축이 <b>얼마나 안 재졌는지</b>를 아카이브만 보고도 알 수
         * 있어야 하기 때문이다. {@code CellReport} 와 같은 경계를 쓴다.
         */
        int cbtClassifierFailures,
        int cbtClassifierCalls,
        Map<ResponseAct, ActStats> byAct,
        Map<String, Integer> violationTypes,
        Shape shape) {

    ContractComplianceMetrics {
        byAct = Map.copyOf(byAct);
        violationTypes = Map.copyOf(violationTypes);
        escapes = Map.copyOf(escapes);
    }

    /**
     * 계약 밖 턴 하나가 속하는 이탈 자리 (P0-3 MEDIUM-1).
     *
     * <h2>왜 분할이어야 하는가</h2>
     *
     * <p>예전에는 이탈을 네 개의 <b>독립 술어</b>로 각각 셌다 — {@code act == CRISIS_ASSESSMENT},
     * {@code act == SECURITY_REFUSAL}, {@code act == UNPLANNED}, 그리고 "본문이 없다". 앞 셋은
     * 서로 배타적이지만 <b>넷째와는 겹친다.</b>
     *
     * <p>겹치는 경로가 실재한다. Judge 가 보안을 {@code SUSPICIOUS} 로 올리고 등급이 {@code LOW}
     * 이면 {@code PolicyEngine} 6번 분기가 {@code GENERATE} + {@code allowGeneration=true} 를 내고
     * {@code ResponsePlanner.planGeneration()} 은 {@code unplanned()} 를 돌려준다. 즉 <b>계획은
     * 계약 밖인데 생성은 실제로 돈다.</b> 그 턴의 생성 호출까지 실패하면 이탈②와 이탈③을 동시에
     * 만족하고, 합계가 한 턴을 두 번 세면서 {@code notApplicable} 은 한 번 센다 →
     * {@link #unexplainedEscapes()} 가 0 이 아니라 <b>음수</b>가 된다. 가드는 fail-closed 라
     * 잘못된 수치가 나가지는 않지만, 실제로는 없는 네 번째 이탈을 쫓게 된다.
     *
     * <p>그래서 술어 하나를 고치는 대신 <b>구조를 바꾼다.</b> 턴마다 자리를 하나만 배정하면
     * 겹침이 생길 수 있는 자리 자체가 없어진다.
     *
     * <h2>배정 순서 — 먼저 이탈한 층이 이긴다</h2>
     *
     * <p>플래너 층 이탈이 생성 층 이탈보다 앞선다. {@code unplanned()} 로 계획된 턴은 생성이
     * <b>성공했더라도</b> 계약 모집단에 들어오지 않는다 — 계약이 걸리지 않은 계획이기 때문이다
     * ({@code ResponsePlan.unplanned()} 은 {@code GenerationFreedom.OPEN} 이라
     * {@code isContractEnforced()} 가 거짓이고, 검사기는 항상 {@code notApplicable()} 을 돌려준다).
     * 그 턴이 모집단을 떠난 <b>원인</b>은 플래너 이탈이고, 생성 실패는 그 위에 겹친 별개의 사실이다.
     *
     * <p>그 별개의 사실이 사라지지는 않는다 — {@link CellCaseOutcome.ExternalFailure} 축이 따로
     * 센다. 이탈 분할은 "왜 모집단을 떠났는가" 를, 외부 실패 축은 "무엇이 실패했는가" 를 답한다.
     * 두 물음을 한 칸에 밀어 넣은 것이 P0-3 의 원래 결함이었다.
     */
    enum Escape {
        /** 이탈① Judge 가 위기로 올려 고정 플로우로 빠졌다. */
        JUDGE_HARD_CRISIS,
        /** 이탈② Judge 보안 판정이 non-CLEAN + 등급 LOW 이하 → 계획 범위 밖. */
        JUDGE_SECURITY,
        /** 보안 거절 — 룰이 공격으로 확정해 고정 문구를 낸 턴. */
        SECURITY_REFUSAL,
        /** 이탈③ 생성 본문이 없어 계약 검사가 {@code notApplicable()} 을 돌려줬다. */
        NO_BODY,
        /**
         * 어느 이탈로도 설명되지 않는다 — 이름 없는 이탈이다.
         *
         * <p>0 이 아니면 리포트가 경고를 찍고 유료 실행 가드가 실패한다. 그때 고쳐야 하는 것은
         * 수치가 아니라 이탈 분류다.
         */
        UNEXPLAINED;

        /** 계약 모집단에 남은 턴은 이탈이 아니다 — 분할에 자리를 갖지 않는다. */
        static final Escape IN_POPULATION = null;
    }

    /**
     * 이 턴이 속하는 이탈 자리. 계약 모집단에 남은 턴은 {@code null} 이다.
     *
     * <p>배정은 <b>위에서 아래로 한 번</b> 일어난다. 순서의 근거는 {@link Escape} javadoc 에 있다.
     */
    static Escape escapeOf(CellCaseOutcome outcome) {
        if (outcome.contract() != ContractOutcome.NOT_APPLICABLE) {
            return Escape.IN_POPULATION;
        }
        // 플래너 층 — 계획 단계에서 이미 계약 밖이 된 턴.
        if (outcome.observedResponseAct() == ResponseAct.CRISIS_ASSESSMENT) {
            return Escape.JUDGE_HARD_CRISIS;
        }
        if (outcome.observedResponseAct() == ResponseAct.SECURITY_REFUSAL) {
            return Escape.SECURITY_REFUSAL;
        }
        if (outcome.observedResponseAct() == ResponseAct.UNPLANNED) {
            return Escape.JUDGE_SECURITY;
        }
        // 생성 층 — 계획은 계약을 걸었는데 볼 본문이 없다.
        if (outcome.externalFailure().removesBody()
                || outcome.acceptance() == CellCaseOutcome.Acceptance.REJECTED_EMPTY_RESPONSE) {
            return Escape.NO_BODY;
        }
        return Escape.UNEXPLAINED;
    }

    int escapeCount(Escape escape) {
        return escapes.getOrDefault(escape, 0);
    }

    /** 이탈① Judge 위기 승격. */
    int crisisRouted() {
        return escapeCount(Escape.JUDGE_HARD_CRISIS);
    }

    /** 이탈② Judge 보안 의심 → 계획 범위 밖. */
    int unplanned() {
        return escapeCount(Escape.JUDGE_SECURITY);
    }

    int securityRefusal() {
        return escapeCount(Escape.SECURITY_REFUSAL);
    }

    /**
     * 이탈③ 생성 본문 없음 (P0-3).
     *
     * <p>{@code ContractEvalSetTest} 의 "전 케이스가 계약 경로로 간다" 보장은 <b>플래너 층</b>에
     * 한정된다. 그 테스트는 생성을 돌리지 않으므로 이 이탈을 구조적으로 볼 수 없다.
     * {@code #305} 대조군의 {@code 계약 밖 25건} 이 정확히 이것이었고, 리포트가 그 25건을 설명하는
     * 세 줄이 모두 0 이었다.
     */
    int noBodyEscapes() {
        return escapeCount(Escape.NO_BODY);
    }

    /**
     * 외부 실패 상한 (비율). 넘으면 이 실행의 수치를 쓰지 않는다.
     *
     * <p>실패한 턴은 계약 모집단에 들어오지 않는다. 실패가 많으면 남은 모집단이 실패하지 않은
     * 쪽으로 치우쳐, 위반율이 네트워크 사정을 재는 값이 된다.
     *
     * <p>가드와 하네스 자체 검사가 <b>같은 값</b>을 읽는다. 값을 두 곳에 적으면 한쪽만 고쳐도
     * 테스트가 통과해, 가드가 실제로 어디서 트립하는지 아무도 모르게 된다.
     *
     * <p><b>값은 세트에 사전 등록돼 있다</b> ({@code runValidity.maxExternalFailureShare},
     * P0-3 MEDIUM-3). 자바 상수로 두면 실행 결과를 보고 조용히 고칠 수 있고, 그러면 상한이 아니라
     * 사후 합리화가 된다. 이 문턱이 <b>실패 모드 둘을 한 값으로 다룬다</b>는 사실과 검토했으나
     * 채택하지 않은 대안도 같은 자리에 기록돼 있다 —
     * {@link ContractEvalSet.RunValidity#tradeoff()}.
     */
    static final double MAX_EXTERNAL_FAILURE_SHARE =
            ContractEvalSet.RUN_VALIDITY.maxExternalFailureShare();

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
     *
     * <p>이탈이 <b>분할</b>이므로 이 합은 구조적으로 {@code notApplicable} 을 넘을 수 없다
     * (P0-3 MEDIUM-1). 예전에는 독립 술어의 합이라 한 턴이 두 번 세어질 수 있었고, 그러면
     * {@link #unexplainedEscapes()} 가 음수가 됐다.
     */
    int explainedEscapes() {
        return crisisRouted() + unplanned() + securityRefusal() + noBodyEscapes();
    }

    /**
     * 어느 이탈로도 설명되지 않은 계약 밖 턴. 0 이 아니면 리포트가 경고를 찍는다.
     *
     * <p>분할이므로 <b>음수가 될 수 없다.</b> {@link #escapes} 의 값이 계약 밖 턴을 정확히 한 번씩
     * 덮기 때문이다 — {@link #assertPartitions()} 가 그 불변식을 생성 시점에 확인한다.
     */
    int unexplainedEscapes() {
        return escapeCount(Escape.UNEXPLAINED);
    }

    /**
     * 이탈 분할이 실제로 분할인지 확인한다 (P0-3 MEDIUM-1).
     *
     * <p>지표를 만드는 자리에서 한 번 검사한다. 리포트나 가드가 이 불변식을 <b>가정</b>하는 대신
     * 여기서 깨지게 두는 것이 요점이다 — 배정 순서를 나중에 고치면서 자리를 겹치게 만들면 그
     * 실수가 리포트 숫자가 되기 전에 드러난다.
     */
    private void assertPartitions() {
        int partitioned = escapes.values().stream().mapToInt(Integer::intValue).sum();
        if (partitioned != notApplicable) {
            throw new IllegalStateException(
                    ("이탈 분할이 계약 밖 턴을 정확히 덮지 않는다 — 분할 합 %d ≠ 계약 밖 %d. "
                            + "자리 배정이 겹치거나 빠졌다: %s")
                            .formatted(partitioned, notApplicable, escapes));
        }
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

        ContractComplianceMetrics metrics = new ContractComplianceMetrics(
                result.variant().contractArm(),
                outcomes.size(),
                scored.size(),
                (int) scored.stream().filter(ContractComplianceMetrics::isViolated).count(),
                count(outcomes, ContractOutcome.NOT_APPLICABLE),
                count(outcomes, ContractOutcome.UNCHECKED),
                (int) outcomes.stream().filter(CellCaseOutcome::generationCalled).count(),
                escapePartition(outcomes),
                // 외부 실패는 acceptance 라벨이 아니라 관측된 사실을 센다 (P0-3). 라벨을 세면
                // 같은 턴의 판정 실패가 생성 실패를 덮어쓴 만큼이 집계에서 사라진다.
                (int) outcomes.stream().filter(CellCaseOutcome::externalFailureObserved).count(),
                (int) outcomes.stream().filter(o -> o.externalFailure().generation()).count(),
                (int) outcomes.stream().filter(o -> o.externalFailure().judge()).count(),
                (int) outcomes.stream().filter(o -> o.externalFailure().caseAborted()).count(),
                (int) outcomes.stream()
                        .filter(o -> o.acceptance() == CellCaseOutcome.Acceptance.REJECTED_EMPTY_RESPONSE)
                        .count(),
                (int) outcomes.stream().filter(CellCaseOutcome::cbtClassifierFailed).count(),
                (int) outcomes.stream().filter(CellCaseOutcome::cbtClassifierCalled).count(),
                byAct, violationTypes(scored), Shape.of(scored));
        metrics.assertPartitions();
        return metrics;
    }

    /**
     * 계약 밖 턴을 이탈 자리로 <b>분할</b>한다 (P0-3 MEDIUM-1).
     *
     * <p>턴마다 {@link #escapeOf} 를 한 번 불러 그 결과만 센다. 자리별 술어를 따로 세지 않으므로
     * 한 턴이 두 자리에 들어갈 경로가 코드상 존재하지 않는다.
     */
    private static Map<Escape, Integer> escapePartition(List<CellCaseOutcome> outcomes) {
        Map<Escape, Integer> counts = new LinkedHashMap<>();
        for (Escape escape : Escape.values()) {
            counts.put(escape, 0);
        }
        for (CellCaseOutcome outcome : outcomes) {
            Escape escape = escapeOf(outcome);
            if (escape != Escape.IN_POPULATION) {
                counts.merge(escape, 1, Integer::sum);
            }
        }
        return counts;
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
