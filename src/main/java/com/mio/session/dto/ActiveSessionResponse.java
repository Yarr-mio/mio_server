package com.mio.session.dto;

import com.mio.session.domain.Session;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ActiveSessionResponse(
        UUID sessionId,
        String characterId,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime lastMessageAt,
        Integer messageCount,
        String lastSummaryStatus,
        UUID lastEndedSessionId
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
