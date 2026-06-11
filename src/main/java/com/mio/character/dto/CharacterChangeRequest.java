package com.mio.character.dto;

import jakarta.validation.constraints.NotBlank;

public record CharacterChangeRequest(
        @NotBlank String characterId
) {}
