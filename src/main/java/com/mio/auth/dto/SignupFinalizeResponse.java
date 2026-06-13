package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.user.domain.SignupStep;

public record SignupFinalizeResponse(
        @JsonProperty("signup_step") SignupStep signupStep,
        String status
) {}
