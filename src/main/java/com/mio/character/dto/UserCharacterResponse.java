package com.mio.character.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserCharacterResponse(
        @JsonProperty("character_id") String characterId,
        String name,
        @JsonProperty("thumbnail_url") String thumbnailUrl
) {}
