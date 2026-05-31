package com.mio.character.dto;

import java.util.List;

public record CharacterDto(
        String characterId,
        String name,
        String animal,
        String description,
        List<String> personalityTags,
        List<String> tags
) {}
