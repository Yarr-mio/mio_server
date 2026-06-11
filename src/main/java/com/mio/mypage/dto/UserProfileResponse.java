package com.mio.mypage.dto;

import java.util.List;
import java.util.UUID;

public record UserProfileResponse(
        UUID userId,
        String nickname,
        String ageRange,
        PreferredCharacterDto preferredCharacter,
        UserStatsDto stats,
        List<EmotionDistributionDto> monthlyEmotionDistribution,
        String signupStep
) {
    public record PreferredCharacterDto(
            String characterId,
            String name,
            String animal,
            String description
    ) {}

    public record UserStatsDto(
            long totalCheckins,
            int consecutiveDays,
            long todoCompleted
    ) {}

    public record EmotionDistributionDto(
            String emotionType,
            String label,
            int percentage
    ) {}
}
