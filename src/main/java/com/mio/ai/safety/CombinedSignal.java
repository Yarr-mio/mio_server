package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationStatus;
import com.mio.ai.security.AttackKind;
import com.mio.ai.security.SecurityLevel;

import java.util.Objects;

/**
 * @param attackKind           {@code securityLevel == ATTACK} 일 때 그 성격. 그 외에는 {@link AttackKind#NONE} (이슈 #260)
 * @param hardCrisisUnverified 위기 키워드가 매칭됐으나 맥락 마커 또는 가시 구분자 우회 때문에
 *                             InputJudge 검증이 필요한 상태 (이슈 #255, #258)
 * @param moderationStatus     L0 판정을 실제로 받아왔는지. {@code l0Flagged=false} 하나로는
 *                             "위험 없음"과 "판정 부재"가 구분되지 않는다 (이슈 #294)
 */
public record CombinedSignal(
        SecurityLevel securityLevel,
        AttackKind attackKind,
        boolean hardCrisis,
        boolean hardCrisisUnverified,
        boolean riskCandidate,
        boolean emotionSpike,
        boolean repetitiveNegative,
        boolean dependencyHint,
        boolean l0Flagged,
        boolean requiresJudge,
        SafetyL1Result l1Result,
        double confidence,
        boolean securityEvidenceUnverifiableByJudge,
        ModerationStatus moderationStatus
) {
    public CombinedSignal {
        // 판정 부재를 안전해 보이는 기본값으로 축약하지 않는 것이 이 필드의 존재 이유다.
        // null 을 RESOLVED 로 바꾸면 바로 그 결함을 이 생성자가 다시 만든다.
        // 구 시그니처 호출부는 아래 호환 생성자에서 명시적으로 RESOLVED 를 넣는다.
        Objects.requireNonNull(moderationStatus, "moderationStatus");
    }

    /** L0 판정 상태 도입 이전 시그니처 — 기존 호출부 호환용 (이슈 #294). */
    public CombinedSignal(
            SecurityLevel securityLevel,
            AttackKind attackKind,
            boolean hardCrisis,
            boolean hardCrisisUnverified,
            boolean riskCandidate,
            boolean emotionSpike,
            boolean repetitiveNegative,
            boolean dependencyHint,
            boolean l0Flagged,
            boolean requiresJudge,
            SafetyL1Result l1Result,
            double confidence,
            boolean securityEvidenceUnverifiableByJudge) {
        this(securityLevel, attackKind, hardCrisis, hardCrisisUnverified, riskCandidate,
                emotionSpike, repetitiveNegative, dependencyHint, l0Flagged, requiresJudge,
                l1Result, confidence, securityEvidenceUnverifiableByJudge,
                ModerationStatus.RESOLVED);
    }

    /** 원문 근거 개념 도입 이전 시그니처 — 기존 호출부 호환용 (이슈 #262). */
    public CombinedSignal(
            SecurityLevel securityLevel,
            AttackKind attackKind,
            boolean hardCrisis,
            boolean hardCrisisUnverified,
            boolean riskCandidate,
            boolean emotionSpike,
            boolean repetitiveNegative,
            boolean dependencyHint,
            boolean l0Flagged,
            boolean requiresJudge,
            SafetyL1Result l1Result,
            double confidence) {
        this(securityLevel, attackKind, hardCrisis, hardCrisisUnverified, riskCandidate,
                emotionSpike, repetitiveNegative, dependencyHint, l0Flagged, requiresJudge,
                l1Result, confidence, false);
    }

    /** 공격 성격 분리 이전 시그니처 — 기존 호출부 호환용 (이슈 #260). */
    public CombinedSignal(
            SecurityLevel securityLevel,
            boolean hardCrisis,
            boolean hardCrisisUnverified,
            boolean riskCandidate,
            boolean emotionSpike,
            boolean repetitiveNegative,
            boolean dependencyHint,
            boolean l0Flagged,
            boolean requiresJudge,
            SafetyL1Result l1Result,
            double confidence) {
        this(securityLevel, AttackKind.NONE, hardCrisis, hardCrisisUnverified, riskCandidate,
                emotionSpike, repetitiveNegative, dependencyHint, l0Flagged, requiresJudge,
                l1Result, confidence);
    }

    /** 검증 대기 개념 도입 이전 시그니처 — 기존 호출부 호환용. */
    public CombinedSignal(
            SecurityLevel securityLevel,
            boolean hardCrisis,
            boolean riskCandidate,
            boolean emotionSpike,
            boolean repetitiveNegative,
            boolean dependencyHint,
            boolean l0Flagged,
            boolean requiresJudge,
            SafetyL1Result l1Result,
            double confidence) {
        this(securityLevel, AttackKind.NONE, hardCrisis, false, riskCandidate, emotionSpike,
                repetitiveNegative, dependencyHint, l0Flagged, requiresJudge, l1Result, confidence);
    }

    /** 자해 질의로 위기 플로우에 진입해야 하는 입력인지 (이슈 #260). */
    public boolean selfHarmInquiry() {
        return attackKind == AttackKind.SELF_HARM_INQUIRY;
    }
}
