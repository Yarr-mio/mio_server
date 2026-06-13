package com.mio.checkin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckinResponse(
        @JsonProperty("checkin_id") UUID checkinId,
        @JsonProperty("time_of_day") String timeOfDay,
        @JsonProperty("emotion_type") String emotionType,
        @JsonProperty("condition_score") int conditionScore,
        String memo,
        @JsonProperty("ai_response") String aiResponse,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {}
