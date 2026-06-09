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
        @JsonProperty("message_count") Integer messageCount,
        @JsonProperty("last_summary_status") String lastSummaryStatus,
        @JsonProperty("last_ended_session_id") UUID lastEndedSessionId
) {
    public static ActiveSessionResponse fromActive(Session session) {
        return new ActiveSessionResponse(
                session.getId(),
                session.getCharacterId(),
                session.getStatus().value(),
                session.getStartedAt(),
                session.getLastMessageAt(),
                session.getMessageCount(),
                null,
                null
        );
    }

    public static ActiveSessionResponse noActiveSession(Session lastEndedSession) {
        return new ActiveSessionResponse(
                null, null, null, null, null, null,
                lastEndedSession == null ? null : lastEndedSession.getSummaryStatus().value(),
                lastEndedSession == null ? null : lastEndedSession.getId()
        );
    }
}
