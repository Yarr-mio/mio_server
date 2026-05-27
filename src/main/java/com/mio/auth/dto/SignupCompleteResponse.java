package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.user.domain.SignupStep;

public record SignupCompleteResponse(
        @JsonProperty("signup_step") SignupStep signupStep,
        String status
) {
}