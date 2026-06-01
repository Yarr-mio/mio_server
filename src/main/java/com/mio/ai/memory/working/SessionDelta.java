package com.mio.ai.memory.working;

import java.util.HashMap;
import java.util.Map;

public record SessionDelta(
        int socraticQuestionsUsed,
        Map<String, Integer> distortionCounts
) {
    public static SessionDelta empty() {
        return new SessionDelta(0, new HashMap<>());
    }

    public boolean socraticLimitReached() {
        return socraticQuestionsUsed >= 2;
    }

    public int distortionCount(String distortionCode) {
        return distortionCounts.getOrDefault(distortionCode, 0);
    }
}
