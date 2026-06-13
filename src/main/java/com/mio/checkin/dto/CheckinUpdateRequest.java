package com.mio.checkin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CheckinUpdateRequest(
        @JsonProperty("emotion_type") String emotionType,
        @Min(1) @Max(5) @JsonProperty("condition_score") Integer conditionScore,
        @Size(max = 200) String memo
) {}
