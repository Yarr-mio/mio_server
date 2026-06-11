package com.mio.character.dto;

public record CharacterChangeResponse(
        String characterId,
        String name,
        boolean changed,
        String greetingMessage
) {}
