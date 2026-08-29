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
        @JsonProperty("last_ended_session_id") UUID lastEndedSessionId,
        /**
         * 이 세션의 선제 인사 (이슈 #530). {@code null} 일 수 있다 — 이 기능 배포 이전에
         * 이미 열려 있던 세션에는 인사가 없고, 소급 생성하지 않는다.
         */
        @JsonProperty("initial_message") InitialAssistantMessageResponse initialMessage
) {
    public static ActiveSessionResponse fromActive(Session session,
                                                   InitialAssistantMessageResponse initialMessage) {
        return new ActiveSessionResponse(
                session.getId(),
                session.getCharacterId(),
                session.getStatus().value(),
                session.getStartedAt(),
                session.getLastMessageAt(),
                session.getMessageCount(),
                null,
                null,
                initialMessage
        );
    }

    public static ActiveSessionResponse noActiveSession(Session lastEndedSession) {
        return new ActiveSessionResponse(
                null, null, null, null, null, null,
                lastEndedSession == null ? null : lastEndedSession.getSummaryStatus().value(),
                lastEndedSession == null ? null : lastEndedSession.getId(),
                null
        );
    }
}
