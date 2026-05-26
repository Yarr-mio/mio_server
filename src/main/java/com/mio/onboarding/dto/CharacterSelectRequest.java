package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CharacterSelectRequest(
        @JsonProperty("character_id") String characterId
) {}
