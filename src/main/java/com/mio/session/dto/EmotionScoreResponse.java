package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.session.domain.Session;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EmotionScoreResponse(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("emotion_score_ai") Integer emotionScoreAi,
        @JsonProperty("emotion_score_user") Integer emotionScoreUser,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {
    public static EmotionScoreResponse from(Session session) {
        return new EmotionScoreResponse(
                session.getId(),
                session.getEmotionScoreAi(),
                session.getEmotionScoreUser(),
                session.getUpdatedAt()
        );
    }
}
