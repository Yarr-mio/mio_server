package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OnboardingStatusResponse(
        @JsonProperty("onboarding_step") int onboardingStep,
        @JsonProperty("signup_step") String signupStep,
        @JsonProperty("character_recommendations") List<CharacterRecommendationDto> characterRecommendations
) {}
