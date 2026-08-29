package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.session.domain.Session;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SessionResponse(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("character_id") String characterId,
        String status,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        /** 선제 인사 (이슈 #530). 신규 세션 생성 응답에는 항상 존재한다. */
        @JsonProperty("initial_message") InitialAssistantMessageResponse initialMessage
) {
    public static SessionResponse from(Session session, InitialAssistantMessageResponse initialMessage) {
        return new SessionResponse(
                session.getId(),
                session.getCharacterId(),
                session.getStatus().value(),
                session.getStartedAt(),
                initialMessage
        );
    }
}
