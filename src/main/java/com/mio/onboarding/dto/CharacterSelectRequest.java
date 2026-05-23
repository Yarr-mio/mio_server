package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CharacterSelectRequest(
        @JsonProperty("character_id") @NotBlank String characterId
) {}
