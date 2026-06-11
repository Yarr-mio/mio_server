package com.mio.ai.profile;

import java.util.List;
import java.util.Map;

/**
 * Memory Layer의 durable artifacts를 얇은 구조화 JSON으로 사전 계산한 프로파일 (§17).
 * 원문 belief text 없음 — 구조화 필드만.
 */
public record SafetyProfile(
        String userId,
        String source,                          // "default" | "personalized"
        Map<String, Double> dynamicThresholds,
        List<String> effectiveInterventions,
        List<String> ineffectiveInterventions,
        List<String> policyFlags,
        double riskPriorScore,
        int recentCrisisSeverityMax,
        List<String> commonDistortionCodes,

        // Phase 3-5 추가 필드 (§17.2)
        int activeNegativeBeliefCount,
        String copingStyle,                     // "avoidance" | "approach" | null
        List<String> dominantTriggerKinds,
        String sensitivityCap                   // "normal" | "sensitive" | "restricted"
) {
    public static final String SOURCE_DEFAULT      = "default";
    public static final String SOURCE_PERSONALIZED = "personalized";

    /** 기존 생성자와의 하위 호환 (13개 인자 → 기본값 채움) */
    public SafetyProfile(
            String userId, String source,
            Map<String, Double> dynamicThresholds,
            List<String> effectiveInterventions,
            List<String> ineffectiveInterventions,
            List<String> policyFlags,
            double riskPriorScore,
            int recentCrisisSeverityMax,
            List<String> commonDistortionCodes) {
        this(userId, source, dynamicThresholds,
                effectiveInterventions, ineffectiveInterventions, policyFlags,
                riskPriorScore, recentCrisisSeverityMax, commonDistortionCodes,
                0, null, List.of(), "sensitive");
    }

    // ── threshold accessors ──────────────────────────────────────

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

    public boolean isPersonalized() {
        return SOURCE_PERSONALIZED.equals(source);
    }
}
