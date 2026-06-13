package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
        @JsonProperty("device_id") @NotBlank String deviceId
) {
}
