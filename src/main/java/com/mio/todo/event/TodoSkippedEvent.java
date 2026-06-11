package com.mio.todo.event;

import java.util.UUID;

public record TodoSkippedEvent(
        UUID userId,
        UUID behaviorTaskId,
        UUID sessionId,
        String interventionKind
) {}
