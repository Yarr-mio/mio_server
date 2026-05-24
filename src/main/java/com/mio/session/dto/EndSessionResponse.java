package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.session.domain.Session;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EndSessionResponse(
        @JsonProperty("session_id") UUID sessionId,
        String status,
        @JsonProperty("ended_at") OffsetDateTime endedAt,
        @JsonProperty("message_count") int messageCount,
        @JsonProperty("duration_seconds") long durationSeconds,
        @JsonProperty("summary_status") String summaryStatus
) {
    public static EndSessionResponse from(Session session) {
        if (session.getEndedAt() == null) {
            throw new IllegalStateException("종료되지 않은 세션으로 EndSessionResponse를 생성할 수 없습니다.");
        }
        return new EndSessionResponse(
                session.getId(),
                session.getStatus(),
                session.getEndedAt(),
                session.getMessageCount(),
                session.durationSeconds(),
                "pending"
        );
    }
}
