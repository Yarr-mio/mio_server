package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.session.domain.Session;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SessionResponse(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("character_id") String characterId,
        String status,
        @JsonProperty("started_at") OffsetDateTime startedAt
) {
    public static SessionResponse from(Session session) {
        return new SessionResponse(
                session.getId(),
                session.getCharacterId(),
                session.getStatus(),
                session.getStartedAt()
        );
    }
}
