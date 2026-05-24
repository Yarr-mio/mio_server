package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SignupStatusResponse(
        @JsonProperty("signup_step") String signupStep,
        @JsonProperty("onboarding_step") int onboardingStep
) {
}