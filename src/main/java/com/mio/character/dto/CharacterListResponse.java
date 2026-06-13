package com.mio.character.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CharacterListResponse(
        @JsonProperty("current_character_id") String currentCharacterId,
        List<CharacterItemDto> characters
) {}
