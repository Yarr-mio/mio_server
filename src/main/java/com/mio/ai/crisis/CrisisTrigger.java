package com.mio.ai.crisis;

/**
 * 위기 플로우에 진입한 경로 (이슈 #260).
 *
 * <p>도입 전에는 {@code CrisisFlowService} 가 {@code SafetyL1Result} 를 역추론해
 * {@code trigger_type} 을 정했다. 그 방식은 진입 경로가 늘 때마다 조용히 틀린 값을 남긴다 —
 * 출력 가드가 잡은 위기가 {@code moderation} 으로 기록되던 것이 그 예다. 판단한 쪽이 이유를
 * 함께 넘기도록 바꾼다.
 *
 * @param persistedType {@code crisis_events.trigger_type} 에 저장할 값.
 *                      스키마의 CHECK 제약({@code keyword|moderation|pattern|user_sos})을 벗어나면
 *                      트랜잭션이 통째로 롤백되므로 새 값을 넣지 않는다.
 */
public enum CrisisTrigger {

    /** SafetyL1 위기 키워드 매칭 (맥락 마커로 강등됐다가 검증으로 확정된 경우 포함). */
    L1_KEYWORD("keyword"),

    /** 자해·자살 수단을 묻는 질의. 규칙 패턴 매칭이므로 키워드 계열로 기록한다. */
    SELF_HARM_INQUIRY("keyword"),

    /** L0 Moderation self-harm 판정에서 출발한 위기. */
    MODERATION("moderation"),

    /** InputJudge 가 위기로 판정한 경우. 규칙·L0 어느 쪽도 확정하지 못한 의미 기반 판정이다. */
    INPUT_JUDGE("pattern"),

    /** 생성된 응답을 검사하는 과정에서 발견된 위기 (OutputPreFilter / OutputJudge). */
    OUTPUT_GUARD("pattern");

    private final String persistedType;

    CrisisTrigger(String persistedType) {
        this.persistedType = persistedType;
    }

    public String persistedType() {
        return persistedType;
    }

    /** 수단 정보를 요구받은 진입 경로인지 — 응답에 거절 문구를 함께 실어야 한다. */
    public boolean requiresMeansRefusal() {
        return this == SELF_HARM_INQUIRY;
    }
}
