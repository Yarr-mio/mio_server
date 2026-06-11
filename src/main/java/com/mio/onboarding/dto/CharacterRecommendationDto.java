package com.mio.onboarding.dto;

public record CharacterRecommendationDto(
        String characterId,
        String name,
        double matchScore,
        String reason
) {}
