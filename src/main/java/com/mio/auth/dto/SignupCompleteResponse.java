package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SignupCompleteResponse(
        @JsonProperty("signup_step") String signupStep,
        @JsonProperty("onboarding_step") int onboardingStep,
        String nickname
) {
}