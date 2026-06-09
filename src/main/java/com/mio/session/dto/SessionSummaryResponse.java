package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.session.domain.Session;
import com.mio.session.domain.SessionSummary;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SessionSummaryResponse(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("summary_status") String summaryStatus,
        @JsonProperty("ended_at") OffsetDateTime endedAt,
        @JsonProperty("duration_seconds") long durationSeconds,
        @JsonProperty("message_count") int messageCount,
        String summary,
        @JsonProperty("avg_emotion_score") Integer avgEmotionScore,
        @JsonProperty("bias_types_detected") String biasTypesDetected,
        @JsonProperty("cbt_intervened") Boolean cbtIntervened
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
