package com.mio.ai.profile;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SafetyProfileBuilder {

    private static final Map<String, Double> DEFAULT_THRESHOLDS = Map.of(
            "emotion_drop_threshold", 30.0,
            "repetitive_negative_count", 3.0,
            "message_burst_count", 10.0,
            "burst_window_minutes", 5.0
    );

    public SafetyProfile buildDefault(String userId) {
        return new SafetyProfile(
                userId,
                SafetyProfile.SOURCE_DEFAULT,
                DEFAULT_THRESHOLDS,
                List.of(),
                List.of(),
                List.of(),
                0.0,
                0,
                List.of()
        );
    }

    /**
     * Phase 2: always returns default profile.
     * Phase 3-5: Redis cache lookup + personalized build.
     */
    public SafetyProfile getOrDefault(String userId) {
        return buildDefault(userId);
    }
}
