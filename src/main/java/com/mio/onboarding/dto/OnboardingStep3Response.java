package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OnboardingStep3Response(
        @JsonProperty("onboarding_step") int onboardingStep,
        @JsonProperty("character_recommendations") List<CharacterRecommendationDto> characterRecommendations
) {}
