package com.mio.ai.plan;

import java.util.List;
import java.util.Objects;

/**
 * 생성 전에 확정하는 응답 계약 (이슈 #303, 로드맵 §5.2).
 *
 * <p>지금까지 "어떤 질문을 할지"는 프롬프트 힌트로만 전달됐다. 힌트는 계약이 아니므로 모델이
 * 질문을 늘리거나, 질문 앞에 단정·진단을 붙이거나, 다른 개입으로 이동해도 검출되지 않았다.
 * 이 record 는 그 기대를 <b>검사 가능한 값</b>으로 만든다.
 *
 * @param maxQuestions      허용 질문 수. 초과는 계약 위반이다
 * @param maxSentences      허용 문장 수
 * @param forbiddenElements 금지 요소 코드. {@code ResponseContractValidator} 가 해석한다
 */
public record ResponsePlan(
        ResponseAct responseAct,
        GenerationFreedom generationFreedom,
        int maxQuestions,
        int maxSentences,
        List<String> forbiddenElements
) {

    /** 모든 응답에 공통으로 금지되는 요소. */
    public static final List<String> BASE_FORBIDDEN =
            List.of("diagnosis", "certainty_about_user", "guaranteed_outcome");

    public ResponsePlan {
        Objects.requireNonNull(responseAct, "responseAct");
        Objects.requireNonNull(generationFreedom, "generationFreedom");
        forbiddenElements = forbiddenElements != null ? List.copyOf(forbiddenElements) : List.of();
    }

    /**
     * 계획 범위 밖의 턴.
     *
     * <p>계약 없이 기존 동작을 유지하되, 계획되지 않았다는 사실이 로그에 남는다. 이 값을
     * "계획된 응답"으로 세면 도입 효과를 과대평가하게 된다.
     */
    public static ResponsePlan unplanned() {
        return new ResponsePlan(ResponseAct.UNPLANNED, GenerationFreedom.OPEN,
                Integer.MAX_VALUE, Integer.MAX_VALUE, BASE_FORBIDDEN);
    }

    /** 서버가 문구를 고정하는 응답 — 모델 생성이 없으므로 계약 검사 대상이 아니다. */
    public static ResponsePlan fixed(ResponseAct responseAct) {
        return new ResponsePlan(responseAct, GenerationFreedom.TEMPLATE_ONLY, 0, 0, BASE_FORBIDDEN);
    }

    /**
     * 서버가 문장 일부를 먼저 전달한 계획 (P0-4, 로드맵 §5.6).
     *
     * <p>safe prefix 는 사용자에게 보이는 응답의 일부다. 상한을 그대로 두면 사용자가 읽는
     * 문장 수가 계약보다 하나 많아진다 — 계약이 정한 것은 "모델이 쓴 문장 수"가 아니라
     * "이 턴에 나가는 문장 수"이기 때문이다. 상한은 줄이기만 한다. 계획은 위험 등급도,
     * 그에 따른 제약도 완화할 수 없다.
     */
    public ResponsePlan reservingSentences(int reserved) {
        if (reserved <= 0 || maxSentences == Integer.MAX_VALUE) {
            return this;
        }
        return new ResponsePlan(responseAct, generationFreedom, maxQuestions,
                Math.max(1, maxSentences - reserved), forbiddenElements);
    }

    /** 계약 검사를 적용할 수 있는 계획인지. */
    public boolean isContractEnforced() {
        return generationFreedom == GenerationFreedom.CONSTRAINED
                || generationFreedom == GenerationFreedom.SLOT_FILLING;
    }
}
