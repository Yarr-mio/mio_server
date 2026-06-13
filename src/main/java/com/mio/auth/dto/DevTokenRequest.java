package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DevTokenRequest(
        @NotNull @JsonProperty("user_id") UUID userId
) {
}
