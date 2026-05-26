package com.mio.character.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CharacterChangeRequest(
        @JsonProperty("character_id") @NotBlank String characterId
) {}
