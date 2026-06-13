package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CharacterRecommendationDto(
        @JsonProperty("character_id") String characterId,
        String name,
        @JsonProperty("match_score") double matchScore,
        String reason
) {}
