package com.mio.ai.security;

import java.util.List;

public record SecurityAssessment(
        SecurityLevel level,
        List<String> attackTypes,
        SecurityAction action,
        boolean allowMainGeneration,
        boolean allowStreaming,
        boolean requireOutputGuard,
        double confidence
) {
    public static SecurityAssessment clean() {
        return new SecurityAssessment(
                SecurityLevel.CLEAN,
                List.of(),
                SecurityAction.ALLOW,
                true, true, false, 1.0
        );
    }

    public static SecurityAssessment attack(List<String> attackTypes) {
        return new SecurityAssessment(
                SecurityLevel.ATTACK,
                attackTypes,
                SecurityAction.BLOCK,
                false, false, false, 1.0
        );
    }

    public static SecurityAssessment suspicious(List<String> attackTypes) {
        return new SecurityAssessment(
                SecurityLevel.SUSPICIOUS,
                attackTypes,
                SecurityAction.ALLOW_WITH_GUARD,
                true, true, true, 0.7
        );
    }
}
