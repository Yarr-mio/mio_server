package com.mio.character.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CharacterDto(
        @JsonProperty("character_id") String characterId,
        String name,
        String animal,
        String description,
        @JsonProperty("personality_tags") List<String> personalityTags,
        @JsonProperty("thumbnail_url") String thumbnailUrl
) {}
