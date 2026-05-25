package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record TokenRefreshRequest(
        @JsonProperty("refresh_token") @NotBlank String refreshToken
) {
}
