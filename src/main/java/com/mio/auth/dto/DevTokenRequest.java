package com.mio.auth.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DevTokenRequest(
        @NotNull UUID userId
) {
}
