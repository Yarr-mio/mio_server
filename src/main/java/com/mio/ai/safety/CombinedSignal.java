package com.mio.ai.safety;

import com.mio.ai.security.SecurityLevel;

public record CombinedSignal(
        SecurityLevel securityLevel,
        boolean hardCrisis,
        boolean riskCandidate,
        boolean emotionSpike,
        boolean repetitiveNegative,
        boolean dependencyHint,
        boolean l0Flagged,
        boolean requiresJudge,
        SafetyL1Result l1Result,
        double confidence
) {
}
