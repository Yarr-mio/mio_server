package com.mio.character.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CharacterChangeResponse(
        @JsonProperty("character_id") String characterId,
        String name,
        boolean changed,
        @JsonProperty("greeting_message") String greetingMessage
) {}
