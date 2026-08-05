package com.mio.ai.safety;

import java.util.List;

/**
 * @param hardCrisis           맥락 검증 없이 즉시 위기 플로우로 보낼 수 있는 확정 신호
 * @param hardCrisisUnverified 위기 키워드는 매칭됐으나 3인칭·인용·부정·과거 회복 맥락이 함께
 *                             발견됐거나, 가시 구분자 우회라 의미 확인이 필요한 상태 (이슈 #255, #258).
 *                             이 상태에서는 {@code riskCandidate}도 함께 true가 되어 Judge 호출이 보장된다.
 */
public record SafetyL1Result(
        boolean hardCrisis,
        boolean hardCrisisUnverified,
        boolean riskCandidate,
        boolean emotionSpike,
        boolean repetitiveNegative,
        boolean dependencyHint,
        boolean moderationFlagged,
        List<String> signals,
        double combinedConfidence
) {
    /** 검증 대기 개념 도입 이전 시그니처 — 기존 호출부 호환용. */
    public SafetyL1Result(
            boolean hardCrisis,
            boolean riskCandidate,
            boolean emotionSpike,
            boolean repetitiveNegative,
            boolean dependencyHint,
            boolean moderationFlagged,
            List<String> signals,
            double combinedConfidence) {
        this(hardCrisis, false, riskCandidate, emotionSpike, repetitiveNegative,
                dependencyHint, moderationFlagged, signals, combinedConfidence);
    }

    public static SafetyL1Result clear() {
        return new SafetyL1Result(false, false, false, false, false, false, false, List.of(), 0.0);
    }

    public boolean hasAnySignal() {
        return hardCrisis || hardCrisisUnverified || riskCandidate
                || emotionSpike || repetitiveNegative || dependencyHint || moderationFlagged;
    }
}
