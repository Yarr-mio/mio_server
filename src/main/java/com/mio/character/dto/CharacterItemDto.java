package com.mio.character.dto;

import java.util.List;

public record CharacterItemDto(
        String characterId,
        String name,
        String animal,
        String description,
        List<String> tags,
        boolean isCurrent
) {}
