package com.mio.ai.moderation;

import java.util.Map;

public record ModerationResult(
        boolean flagged,
        Map<String, Boolean> categories,
        Map<String, Double> categoryScores
) {
    public static ModerationResult failOpen() {
        return new ModerationResult(false, Map.of(), Map.of());
    }

    public boolean isSelfHarmFlagged() {
        return Boolean.TRUE.equals(categories.get("self-harm"))
                || Boolean.TRUE.equals(categories.get("self-harm/intent"))
                || Boolean.TRUE.equals(categories.get("self-harm/instructions"));
    }

    public double selfHarmScore() {
        return categoryScores.getOrDefault("self-harm", 0.0);
    }
}
