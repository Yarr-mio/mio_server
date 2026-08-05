package com.mio.ai.plan;

/**
 * 이번 턴에 수행할 응답 행위 (이슈 #303, 로드맵 §5.3).
 *
 * <p>{@code GenerationMode} 는 응답의 톤과 안전 강도를 말하지만 <b>무엇을 할지</b>는 말하지
 * 않는다. 그래서 질문 수·개입 종류·단정 여부가 전부 문장 생성에 맡겨졌고, 응답을 행위별로
 * 평가할 수도 없었다.
 *
 * <p>MVP 범위는 자유도가 낮고 평가하기 쉬운 행위만이다. {@code SOCRATIC_QUESTION} 이나
 * {@code REFRAME} 처럼 자유도가 높은 행위는 계약 검사와 평가 기준을 갖춘 뒤에 추가한다.
 */
public enum ResponseAct {
    /** 감정을 인정하고 반영한다. 질문 없음. */
    EMPATHIC_REFLECTION,
    /** 감정과 강도를 확인한다. 질문 1개. */
    EMOTION_CHECK,
    /** 사건·맥락을 확인한다. 질문 1개. */
    CLARIFY_CONTEXT,
    /** 현재성·계획·수단을 확인하는 검토된 위기 흐름. */
    CRISIS_ASSESSMENT,
    /** 위기·전문가 자원 연결. 고정 템플릿. */
    RESOURCE_HANDOFF,
    /** 보안 거절 고정 응답. */
    SECURITY_REFUSAL,
    /**
     * 아직 계획 범위에 들어오지 않은 턴.
     *
     * <p>계획되지 않았다는 사실 자체를 기록하기 위한 값이다. 이 값을 "계획됨"으로 세면
     * 도입 효과를 과대평가하게 된다.
     */
    UNPLANNED
}
