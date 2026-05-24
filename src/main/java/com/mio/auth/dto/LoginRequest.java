package com.mio.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String provider,
        String idToken,
        String accessToken,
        @NotBlank String deviceId
) {
}