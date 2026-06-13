package com.mio.mypage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record UserProfileResponse(
        @JsonProperty("user_id") UUID userId,
        String nickname,
        @JsonProperty("age_range") String ageRange,
        @JsonProperty("preferred_character") PreferredCharacterDto preferredCharacter,
        UserStatsDto stats,
        @JsonProperty("monthly_emotion_distribution") List<EmotionDistributionDto> monthlyEmotionDistribution,
        @JsonProperty("signup_step") String signupStep
) {
    public record PreferredCharacterDto(
            @JsonProperty("character_id") String characterId,
            String name,
            String animal,
            String description
    ) {}

    public record UserStatsDto(
            @JsonProperty("total_checkins") long totalCheckins,
            @JsonProperty("consecutive_days") int consecutiveDays,
            @JsonProperty("todo_completed") long todoCompleted
    ) {}

    public record EmotionDistributionDto(
            @JsonProperty("emotion_type") String emotionType,
            String label,
            int percentage
    ) {}
}
