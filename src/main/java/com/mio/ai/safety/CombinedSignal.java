package com.mio.ai.safety;

import com.mio.ai.security.SecurityLevel;

/**
 * @param hardCrisisUnverified 위기 키워드가 매칭됐으나 맥락 마커 때문에 InputJudge 검증이 필요한 상태 (이슈 #255)
 */
public record CombinedSignal(
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
        double confidence
) {
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
        this(securityLevel, hardCrisis, false, riskCandidate, emotionSpike,
                repetitiveNegative, dependencyHint, l0Flagged, requiresJudge, l1Result, confidence);
    }
}
