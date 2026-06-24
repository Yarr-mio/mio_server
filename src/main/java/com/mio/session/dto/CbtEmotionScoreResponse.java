package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.session.domain.CbtReconstruction;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CbtEmotionScoreResponse(
        @JsonProperty("reconstruction_id") UUID reconstructionId,
        @JsonProperty("emotion_score_after") Integer emotionScoreAfter,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {
    public static CbtEmotionScoreResponse from(CbtReconstruction reconstruction) {
        return new CbtEmotionScoreResponse(
                reconstruction.getId(),
                reconstruction.getEmotionScoreAfter(),
                reconstruction.getUpdatedAt()
        );
    }
}
