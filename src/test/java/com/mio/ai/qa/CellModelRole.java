package com.mio.ai.qa;

/**
 * A~E 셀이 모델을 핀하는 단위 (로드맵 §10.3).
 *
 * <p>로드맵은 "모델 이름을 코드 상수 하나로 고정하지 않고 역할별 registry 로 관리한다" 고
 * 적었다. 그래서 셀 정의는 모델 ID 가 아니라 <b>역할</b>로 쓰고, 실제 ID 는 실행 직전에
 * {@link CellModelRegistry} 가 채운다.
 *
 * <p>{@code component} 는 프로덕션 코드가 {@code LlmRequest.withAttribution(...)} 에 넣는
 * 태그다({@code OpenAiLlmClient.METERED_COMPONENTS}). 이 값을 역할의 정체로 삼으면 셀별
 * 모델 교체를 <b>프로덕션 코드 수정 없이</b> 요청 경계에서 할 수 있다 —
 * {@link RoleModelRewritingLlmClient} 가 이 태그를 보고 {@code LlmRequest.model} 을 바꾼다.
 *
 * <p>{@code component} 가 {@code null} 인 역할은 온라인 경로에서 호출되지 않는다. 셀 C 의
 * reference judge 가 그렇다 — 운영 턴에 끼면 그건 이미 셀 C 가 아니라 셀 B 다.
 */
enum CellModelRole {

    /** 입력 안전 판정. 프로덕션 {@code InputJudge} 가 쓰는 역할이다. */
    INPUT_SAFETY("input_safety", "INPUT_JUDGE"),

    /** 대화 생성. 프로덕션 {@code ConversationOrchestrator} 의 메인 생성이다. */
    GENERATION("generation", "MAIN_GENERATION"),

    /** 출력 판정. pre-filter·계약 검사가 걸린 응답만 여기로 간다. */
    OUTPUT_JUDGE("output_judge", "OUTPUT_JUDGE"),

    /**
     * 난례 에스컬레이션 (로드맵 §10.3 cascade 마지막 단계).
     *
     * <p>온라인 호출이지만 생성과 다른 모델을 쓸 수 있어 역할을 나눈다. 셀 D·E 만 쓴다.
     *
     * <p>태그가 {@code MAIN_GENERATION} 이 아닌 이유는 <b>둘을 합치면 "몇 건이 상위 모델까지
     * 올라갔는가" 를 사후에 셀 수 없기</b> 때문이다. 이 값은 프로덕션의
     * {@code METERED_COMPONENTS} 에 없으므로 메트릭 label 로는 {@code other} 로 접히지만,
     * 비용 원장에 남는 {@code component} 원문과 {@link CellTokenLedger} 는 원래 값을 유지한다.
     */
    ESCALATION("escalation", "ESCALATION_GENERATION"),

    /**
     * offline reference judge (로드맵 §11.3 셀 C).
     *
     * <p><b>운영 경로에서 호출하지 않는다.</b> 셀 C 의 가설은 "운영비 증가 없이 오류 발견이
     * 개선되는가" 이므로, 이 역할이 턴당 원가에 들어가는 순간 가설 자체가 성립하지 않는다.
     */
    REFERENCE_JUDGE("reference_judge", null);

    private final String key;
    private final String component;

    CellModelRole(String key, String component) {
        this.key = key;
        this.component = component;
    }

    /** 실행 manifest 의 {@code model.*} 행 이름. */
    String key() {
        return key;
    }

    /** 이 역할로 나가는 요청의 비용 귀속 태그. 온라인 호출이 없으면 {@code null}. */
    String component() {
        return component;
    }

    /** 온라인 경로에서 실제로 호출되는 역할인가. */
    boolean isOnline() {
        return component != null;
    }
}
