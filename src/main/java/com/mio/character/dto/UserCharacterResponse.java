package com.mio.character.dto;

public record UserCharacterResponse(
        String characterId,
        String name,
        String animal
) {}
