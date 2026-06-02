package com.mio.ai.memory.working;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record SessionDelta(
        int socraticQuestionsUsed,
        Map<String, Integer> distortionCounts,
        int sessionRiskAccumulation,
        Set<String> activatedBeliefIds,
        Set<String> currentSessionTriggers
) {
    public static SessionDelta empty() {
        return new SessionDelta(0, new HashMap<>(), 0, new HashSet<>(), new HashSet<>());
    }

    public boolean socraticLimitReached() {
        return socraticQuestionsUsed >= 2;
    }

    public int distortionCount(String distortionCode) {
        return distortionCounts.getOrDefault(distortionCode, 0);
    }
}
