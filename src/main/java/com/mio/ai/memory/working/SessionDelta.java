package com.mio.ai.memory.working;

import java.util.Map;
import java.util.Set;

public record SessionDelta(
        int socraticQuestionsUsed,
        String cbtInterventionState,
        Map<String, Integer> distortionCounts,
        int sessionRiskAccumulation,
        Set<String> activatedBeliefIds,
        Set<String> currentSessionTriggers
) {
    public static SessionDelta empty() {
        return new SessionDelta(0, "none", Map.of(), 0, Set.of(), Set.of());
    }

    public boolean socraticLimitReached() {
        return socraticQuestionsUsed >= 2;
    }

    public int distortionCount(String distortionCode) {
        return distortionCounts.getOrDefault(distortionCode, 0);
    }
}
