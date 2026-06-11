package com.mio.session.dto;

import com.mio.session.domain.Session;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EndSessionResponse(
        UUID sessionId,
        String status,
        OffsetDateTime endedAt,
        int messageCount,
        long durationSeconds,
        String summaryStatus
) {
    public static EndSessionResponse from(Session session) {
        if (session.getEndedAt() == null) {
            throw new IllegalStateException("종료되지 않은 세션으로 EndSessionResponse를 생성할 수 없습니다.");
        }
        return new EndSessionResponse(
                session.getId(),
                session.getStatus().value(),
                session.getEndedAt(),
                session.getMessageCount(),
                session.durationSeconds(),
                session.getSummaryStatus().value()
        );
    }
}
