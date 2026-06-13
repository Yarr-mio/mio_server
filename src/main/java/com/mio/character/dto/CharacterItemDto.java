package com.mio.character.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CharacterItemDto(
        @JsonProperty("character_id") String characterId,
        String name,
        String animal,
        String description,
        List<String> tags,
        @JsonProperty("is_current") boolean isCurrent
) {}
