package com.mio.session.dto;

import com.mio.session.domain.Session;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SessionResponse(
        UUID sessionId,
        String characterId,
        String status,
        OffsetDateTime startedAt
) {
    public static SessionResponse from(Session session) {
        return new SessionResponse(
                session.getId(),
                session.getCharacterId(),
                session.getStatus().value(),
                session.getStartedAt()
        );
    }
}
