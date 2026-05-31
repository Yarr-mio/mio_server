package com.mio.ai.policy;

import java.util.List;

public record InterventionHints(
        List<String> suggestedCodes,
        List<String> avoidCodes,
        String targetDistortionCode
) {
    public static InterventionHints empty() {
        return new InterventionHints(List.of(), List.of(), null);
    }
}
