package com.mio.character.dto;

import java.util.List;

public record CharacterListResponse(
        String currentCharacterId,
        List<CharacterItemDto> characters
) {}
