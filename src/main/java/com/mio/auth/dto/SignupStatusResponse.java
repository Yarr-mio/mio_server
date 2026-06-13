package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.user.domain.SignupStep;

public record SignupStatusResponse(
        @JsonProperty("signup_step") SignupStep signupStep,
        @JsonProperty("onboarding_step") int onboardingStep
) {
}