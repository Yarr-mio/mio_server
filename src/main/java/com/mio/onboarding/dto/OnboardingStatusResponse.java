package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.user.domain.SignupStep;

import java.util.List;

public record OnboardingStatusResponse(
        @JsonProperty("onboarding_step") int onboardingStep,
        @JsonProperty("signup_step") SignupStep signupStep,
        @JsonProperty("character_recommendations") List<CharacterRecommendationDto> characterRecommendations
) {}
