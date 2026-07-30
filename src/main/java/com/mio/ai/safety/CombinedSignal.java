package com.mio.ai.safety;

import com.mio.ai.security.AttackKind;
import com.mio.ai.security.SecurityLevel;

/**
 * @param attackKind           {@code securityLevel == ATTACK} 일 때 그 성격. 그 외에는 {@link AttackKind#NONE} (이슈 #260)
 * @param hardCrisisUnverified 위기 키워드가 매칭됐으나 맥락 마커 때문에 InputJudge 검증이 필요한 상태 (이슈 #255)
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
        boolean securityEvidenceUnverifiableByJudge
) {
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
