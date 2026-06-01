package com.mio.ai.profile;

import java.util.List;
import java.util.Map;

public record SafetyProfile(
        String userId,
        String source,
        Map<String, Double> dynamicThresholds,
        List<String> effectiveInterventions,
        List<String> ineffectiveInterventions,
        List<String> policyFlags,
        double riskPriorScore,
        int recentCrisisSeverityMax,
        List<String> commonDistortionCodes
) {
    public static final String SOURCE_DEFAULT = "default";
    public static final String SOURCE_PERSONALIZED = "personalized";

    public double emotionDropThreshold() {
        return dynamicThresholds.getOrDefault("emotion_drop_threshold", 30.0);
    }

    public int repetitiveNegativeCount() {
        return dynamicThresholds.getOrDefault("repetitive_negative_count", 3.0).intValue();
    }

    public int messageBurstCount() {
        return dynamicThresholds.getOrDefault("message_burst_count", 10.0).intValue();
    }

    public double burstWindowMinutes() {
        return dynamicThresholds.getOrDefault("burst_window_minutes", 5.0);
    }

    public boolean hasForceJudge() {
        return policyFlags != null && policyFlags.contains("force_judge");
    }
}
