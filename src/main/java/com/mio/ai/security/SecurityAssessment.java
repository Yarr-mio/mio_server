package com.mio.ai.security;

import java.util.List;

/**
 * @param attackKind          {@code level == ATTACK} 일 때 그 성격. 그 외에는 {@link AttackKind#NONE} (이슈 #260).
 * @param unverifiableByJudge 이 판정의 근거를 InputJudge 가 <b>구조적으로 확인할 수 없는지</b>.
 *                            Judge 에는 소문자·공백 정리된 정규화본만 전달되므로, 원문에서만
 *                            드러나는 근거(Base64·제로폭)는 Judge 가 재현할 수 없다. 그 상태에서
 *                            Judge 의 CLEAN 을 근거로 등급을 낮추면 <b>원문 탐지가 통째로
 *                            무력화된다</b> — 확인하지 못한 것을 "괜찮다"로 받아들이는 것이다.
 */
public record SecurityAssessment(
        SecurityLevel level,
        AttackKind attackKind,
        List<String> attackTypes,
        SecurityAction action,
        boolean allowMainGeneration,
        boolean allowStreaming,
        boolean requireOutputGuard,
        double confidence,
        boolean unverifiableByJudge
) {
    public SecurityAssessment {
        attackTypes = List.copyOf(attackTypes);
    }

    public static SecurityAssessment clean() {
        return new SecurityAssessment(
                SecurityLevel.CLEAN,
                AttackKind.NONE,
                List.of(),
                SecurityAction.ALLOW,
                true, true, false, 1.0, false
        );
    }

    /** 모델 조작 시도 — 거절한다. */
    public static SecurityAssessment manipulation(List<String> attackTypes) {
        return new SecurityAssessment(
                SecurityLevel.ATTACK,
                AttackKind.MANIPULATION,
                attackTypes,
                SecurityAction.BLOCK,
                false, false, false, 1.0, false
        );
    }

    /**
     * 자해·자살 수단 질의 — 위기 플로우로 보낸다 (이슈 #260).
     *
     * <p>등급은 {@code ATTACK} 을 유지한다. 본문 생성을 막고 고정 응답만 내보내는 성질은 조작 시도와
     * 같고, 달라지는 것은 어떤 고정 응답을 내보내느냐뿐이기 때문이다. 등급을 낮추면 생성 경로가
     * 열려 수단 정보가 응답에 섞일 여지가 생긴다.
     */
    public static SecurityAssessment selfHarmInquiry(List<String> attackTypes) {
        return new SecurityAssessment(
                SecurityLevel.ATTACK,
                AttackKind.SELF_HARM_INQUIRY,
                attackTypes,
                SecurityAction.BLOCK,
                false, false, false, 1.0, false
        );
    }

    public static SecurityAssessment suspicious(List<String> attackTypes) {
        return suspicious(attackTypes, false);
    }

    /**
     * @param unverifiableByJudge 근거가 원문에서만 드러나 Judge 가 확인할 수 없는 경우 {@code true}
     */
    public static SecurityAssessment suspicious(List<String> attackTypes, boolean unverifiableByJudge) {
        return new SecurityAssessment(
                SecurityLevel.SUSPICIOUS,
                AttackKind.NONE,
                attackTypes,
                SecurityAction.ALLOW_WITH_GUARD,
                true, true, true, 0.7,
                unverifiableByJudge
        );
    }
}
