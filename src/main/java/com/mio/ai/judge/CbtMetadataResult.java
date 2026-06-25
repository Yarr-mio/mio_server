package com.mio.ai.judge;

import java.util.Set;

public record CbtMetadataResult(
        CbtInterventionState state,
        String completionReason,
        boolean requiresEmotionScore,
        boolean socratic,
        String biasType,
        String reconstructedThought
) {
    private static final Set<String> ALLOWED_BIAS_TYPES = Set.of(
            "overgeneralization",
            "catastrophizing",
            "mind_reading",
            "all_or_nothing",
            "self_blame",
            "emotional_reasoning"
    );

    public static CbtMetadataResult none() {
        return new CbtMetadataResult(CbtInterventionState.NONE, null, false, false, null, null);
    }

    public boolean shouldCreateEmotionScoreTarget() {
        return state == CbtInterventionState.COMPLETED && requiresEmotionScore;
    }

    public static boolean isAllowedBiasType(String biasType) {
        return biasType != null && ALLOWED_BIAS_TYPES.contains(biasType);
    }
}
