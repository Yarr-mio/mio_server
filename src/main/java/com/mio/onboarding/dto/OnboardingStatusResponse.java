package com.mio.onboarding.dto;

import com.mio.user.domain.SignupStep;

import java.util.List;

public record OnboardingStatusResponse(
        int onboardingStep,
        SignupStep signupStep,
        List<CharacterRecommendationDto> characterRecommendations
) {}
