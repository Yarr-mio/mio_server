package com.mio.session.dto;

import com.mio.session.domain.Session;
import com.mio.session.domain.SessionSummary;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SessionSummaryResponse(
        UUID sessionId,
        String summaryStatus,
        OffsetDateTime endedAt,
        long durationSeconds,
        int messageCount,
        String summary,
        Integer avgEmotionScore,
        String biasTypesDetected,
        Boolean cbtIntervened
) {
    public static SessionSummaryResponse pending(Session session) {
        return new SessionSummaryResponse(
                session.getId(),
                "pending",
                session.getEndedAt(),
                session.durationSeconds(),
                session.getMessageCount(),
                null, null, null, null
        );
    }

    public static SessionSummaryResponse from(Session session, SessionSummary summary) {
        return new SessionSummaryResponse(
                session.getId(),
                session.getSummaryStatus().value(),
                session.getEndedAt(),
                session.durationSeconds(),
                session.getMessageCount(),
                summary.getSummaryText(),
                session.getAvgEmotionScore(),
                summary.getBiasTypesDetected(),
                summary.isCbtIntervened()
        );
    }
}
