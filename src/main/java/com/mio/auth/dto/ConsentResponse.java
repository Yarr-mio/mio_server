package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ConsentResponse(
        @JsonProperty("signup_step") String signupStep
) {}
