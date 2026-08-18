package com.mio.ai.qa;

import com.mio.ai.plan.ResponsePlan;

/**
 * 프롬프트에 {@code [응답 계약]} 블록을 넣는가 (이슈 #305, 로드맵 §5.8).
 *
 * <h2>왜 프롬프트만 가르는가</h2>
 *
 * <p>{@code #303} 은 계약을 <b>측정 가능</b>하게 만들었을 뿐, 계약 지시가 위반율을 실제로
 * 낮추는지는 재지 않았다. 그 물음에 답하려면 같은 입력·같은 계획·같은 채점 기준 위에서
 * <b>프롬프트 블록 하나만</b> 빼고 비교해야 한다.
 *
 * <p>그래서 이 enum 이 바꾸는 것은 {@code PromptBuilder} 에 넘기는 계획 인자뿐이다.
 * {@code ResponseContractValidator.validate(plan, text)} 에는 <b>양쪽 팔 모두 진짜 계획</b>이
 * 그대로 들어간다. 채점 기준이 팔마다 다르면 두 수치는 같은 자를 쓴 값이 아니고, 그러면
 * 차이가 계약 지시의 효과인지 채점의 차이인지 구별할 수 없다.
 *
 * <p>블록을 없애는 방법으로 {@code plan = null} 을 쓴다. 프로덕션
 * {@code PromptBuilder.buildPlanInstruction} 이 이미 {@code null}·{@code UNPLANNED} 에서 빈
 * 문자열을 돌려주므로, 이 A/B 를 위해 프로덕션에 토글을 새로 넣지 않는다 — 실험용 분기가
 * 프로덕션 프롬프트 조립부에 남으면 실험이 끝나도 그 분기는 남는다.
 */
enum ContractPromptArm {

    /** 현행 프로덕션. 계획이 프롬프트에 그대로 실린다. */
    WITH_CONTRACT_BLOCK("계약 지시 있음 (현행)", "with-contract"),

    /** 계약 지시만 빠진 대조군. 검사는 그대로 돈다. */
    WITHOUT_CONTRACT_BLOCK("계약 지시 없음 (대조군)", "without-contract");

    private final String label;
    private final String fileToken;

    ContractPromptArm(String label, String fileToken) {
        this.label = label;
        this.fileToken = fileToken;
    }

    String label() {
        return label;
    }

    String fileToken() {
        return fileToken;
    }

    /** 현행 벤치마크가 쓰는 팔. 이 값이면 라벨·파일명이 예전과 한 글자도 다르지 않다. */
    boolean isDefault() {
        return this == WITH_CONTRACT_BLOCK;
    }

    /**
     * 이 팔이 {@code PromptBuilder} 에 넘길 계획.
     *
     * <p>{@code null} 은 "계획이 없다" 가 아니라 "계획을 프롬프트에 알리지 않는다" 는 뜻이다.
     * 계획 자체는 호출부가 그대로 들고 있고 검사에 쓴다.
     */
    ResponsePlan promptPlan(ResponsePlan plan) {
        return this == WITH_CONTRACT_BLOCK ? plan : null;
    }
}
