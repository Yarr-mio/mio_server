package com.mio.ai.safety;

import java.util.List;

public record SafetyL1Result(
        boolean hardCrisis,
        boolean riskCandidate,
        boolean emotionSpike,
        boolean repetitiveNegative,
        boolean dependencyHint,
        boolean moderationFlagged,
        List<String> signals,
        double combinedConfidence
) {
    public static SafetyL1Result clear() {
        return new SafetyL1Result(false, false, false, false, false, false, List.of(), 0.0);
    }

    public boolean hasAnySignal() {
        return hardCrisis || riskCandidate || emotionSpike || repetitiveNegative || dependencyHint || moderationFlagged;
    }
}
