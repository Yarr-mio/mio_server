package com.mio.ai.qa.correction;

import java.util.List;
import java.util.Map;

/**
 * 응답 하나에 대한 채점 결과 (이슈 #551, 기준표 {@link CorrectionRubric#VERSION}).
 *
 * <p>통과 조건은 판정 항목의 조합이라 채점 모델이 아니라 <b>코드</b>가 계산한다 — 모델이
 * "통과"를 직접 말하게 하면 기준표와 다른 기준이 슬며시 들어온다.
 *
 * @param judgeFailed 채점 호출이 실패했거나 출력을 해석하지 못했다. 이 경우 {@code passed} 는
 *                    거짓이지만 <b>품질 실패가 아니라 채점 실패</b>이므로 집계에서 분모에서 뺀다
 */
public record CorrectionVerdict(String caseId, boolean correctionCase, Map<String, Boolean> items,
                                Map<String, String> evidence, boolean passed, boolean judgeFailed) {

    // 정정 케이스 항목
    public static final String ACKNOWLEDGED = "acknowledged";
    public static final String REUSED_FRAME = "reused_frame";
    public static final String REPEATED_ADVICE = "repeated_advice";
    public static final String NEW_GENERIC_ADVICE = "new_generic_advice";
    public static final String RESTATED = "restated";
    public static final String INVENTED_FACTS = "invented_facts";

    // 대조군 항목
    public static final String SPURIOUS_ACKNOWLEDGMENT = "spurious_acknowledgment";
    public static final String UNNATURAL_RESTATEMENT = "unnatural_restatement";
    public static final String AVOIDED_REQUESTED_ADVICE = "avoided_requested_advice";

    public static final List<String> CORRECTION_ITEMS = List.of(
            ACKNOWLEDGED, REUSED_FRAME, REPEATED_ADVICE, NEW_GENERIC_ADVICE, RESTATED, INVENTED_FACTS);
    public static final List<String> CONTROL_ITEMS = List.of(
            SPURIOUS_ACKNOWLEDGMENT, UNNATURAL_RESTATEMENT, AVOIDED_REQUESTED_ADVICE);

    public CorrectionVerdict {
        items = Map.copyOf(items);
        evidence = Map.copyOf(evidence);
    }

    /**
     * 정정 케이스의 통과 조건: 인정 O, 프레임 재사용 X, 같은 조언 반복 X, 새 일반 조언 X,
     * 재진술 O, 지어낸 내용 X. 조언 거절 종류는 프레임 재사용 항목을 보지 않는다(해당 없음).
     */
    static CorrectionVerdict forCorrection(CorrectionEvalCase evalCase, Map<String, Boolean> items,
                                           Map<String, String> evidence) {
        boolean frameApplies = !"advice_refusal".equals(evalCase.type());
        boolean passed = items.get(ACKNOWLEDGED)
                && (!frameApplies || !items.get(REUSED_FRAME))
                && !items.get(REPEATED_ADVICE)
                && !items.get(NEW_GENERIC_ADVICE)
                && items.get(RESTATED)
                && !items.get(INVENTED_FACTS);
        return new CorrectionVerdict(evalCase.id(), true, items, evidence, passed, false);
    }

    /**
     * 대조군의 통과 조건: 불필요한 인정 X, 요청된 조언 회피 X. 조언 회피는 사용자가 조언을 요청한 케이스
     * ({@code adviceRequested})에서만 본다.
     *
     * <p><b>어색한 재진술은 통과 조건이 아니다 (기준표 v4, 2026-09-21).</b> 채점기 사람 검증에서 이 항목의
     * 일치율이 31%(16건 중 5건)였다 — 채점기는 16건 중 12건을 문제로 봤고 사람은 2건만 그렇게 봤다. 판정이
     * 이만큼 갈리는 항목을 통과 조건에 두면 후보 프롬프트의 부작용이 아니라 채점기의 오판을 재게 된다.
     * 항목 자체는 계속 판정해 참고 지표로 리포트에 싣는다.
     */
    static CorrectionVerdict forControl(CorrectionEvalCase evalCase, Map<String, Boolean> items,
                                        Map<String, String> evidence) {
        boolean passed = !items.get(SPURIOUS_ACKNOWLEDGMENT)
                && (!evalCase.adviceRequested() || !items.get(AVOIDED_REQUESTED_ADVICE));
        return new CorrectionVerdict(evalCase.id(), false, items, evidence, passed, false);
    }

    static CorrectionVerdict failed(CorrectionEvalCase evalCase) {
        return new CorrectionVerdict(evalCase.id(), evalCase.correction(), Map.of(), Map.of(), false, true);
    }
}
