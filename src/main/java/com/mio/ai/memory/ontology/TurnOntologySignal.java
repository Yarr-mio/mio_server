package com.mio.ai.memory.ontology;

/** 현재 발화에서만 추출한 온톨로지 활성화 후보. 영속 데이터 생성에는 사용하지 않는다. */
public record TurnOntologySignal(
        String distortionCode,
        String beliefKind,
        String polarity
) {
    public static TurnOntologySignal empty() {
        return new TurnOntologySignal(null, null, null);
    }
}
