package com.mio.ai.memory.consolidation;

import java.util.UUID;

public record SessionEndedEvent(
        UUID sessionId,
        UUID userId,
        String characterId
) {}
