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
     * CBT 메타데이터 분류 — 프로덕션 {@code CbtMetadataClassifier}.
     *
     * <p>{@code ConversationOrchestrator.sendDoneEvent()} 가 응답 전달 직후 <b>매 턴 동기로</b>
     * 부르는 실호출이다. 하네스가 이 호출을 빼면 전 셀이 같은 상수만큼 턴당 원가·지연을 과소
     * 보고하고, 15%/20% 같은 <b>비율 게이트의 경계에서 판정이 뒤집힐 수 있다.</b> 그래서 제외
     * 목록에 적어 두는 대신 온라인 역할로 넣는다.
     */
    CBT_CLASSIFIER("cbt_classifier", "CBT_CLASSIFIER"),

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
     * 그래서 {@link #component()} 는 {@code null} 이고 {@link #isOnline()} 은 거짓이다 —
     * 온라인 원장({@link CellTokenLedger})이 이 역할의 호출을 받을 수 있는 경로 자체가 없다.
     *
     * <p>대신 offline 채점 pass 는 별도 원장·별도 클라이언트로 돌고, 그 요청에는
     * {@link #OFFLINE_COMPONENT} 태그가 붙는다. 온라인 태그와 이름이 다르므로 두 원장이
     * 실수로 섞여도 어느 쪽 호출인지 사후에 구별할 수 있다.
     */
    REFERENCE_JUDGE("reference_judge", null);

    /**
     * offline reference judge 요청의 귀속 태그.
     *
     * <p>온라인 역할의 {@code component} 어느 것과도 겹치지 않는다. 온라인 원장에서 이 태그가
     * 하나라도 발견되면 그건 셀 C 가 오염됐다는 뜻이고, {@link CellParity} 가 그것을 단언한다.
     */
    static final String OFFLINE_COMPONENT = "REFERENCE_JUDGE_OFFLINE";

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
