package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.session.domain.Session;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ActiveSessionResponse(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("character_id") String characterId,
        String status,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        @JsonProperty("last_message_at") OffsetDateTime lastMessageAt,
        @JsonProperty("message_count") int messageCount
) {
    public static ActiveSessionResponse from(Session session) {
        return new ActiveSessionResponse(
                session.getId(),
                session.getCharacterId(),
                session.getStatus(),
                session.getStartedAt(),
                session.getLastMessageAt(),
                session.getMessageCount()
        );
    }
}
