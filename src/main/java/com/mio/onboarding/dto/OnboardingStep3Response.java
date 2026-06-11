package com.mio.onboarding.dto;

import java.util.List;

public record OnboardingStep3Response(
        int onboardingStep,
        List<CharacterRecommendationDto> characterRecommendations
) {}
