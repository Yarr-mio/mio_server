package com.mio.checkin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckinResponse(
        UUID checkinId,
        String timeOfDay,
        String emotionType,
        int conditionScore,
        String memo,
        String aiResponse,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
