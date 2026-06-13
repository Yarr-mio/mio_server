package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String provider,
        @JsonProperty("idToken") String idToken,
        @JsonProperty("accessToken") String accessToken,
        @JsonProperty("deviceId") @NotBlank String deviceId
) {
}