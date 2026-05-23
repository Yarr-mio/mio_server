package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OnboardingStepResponse(
        @JsonProperty("onboarding_step") int onboardingStep
) {}
