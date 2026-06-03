package com.mio.todo.event;

import java.util.UUID;

public record TodoCompletedEvent(
        UUID userId,
        UUID behaviorTaskId,
        UUID sessionId,
        String interventionKind,
        Integer beforeEmotionScore,
        Integer afterEmotionScore,
        String characterId
) {}
