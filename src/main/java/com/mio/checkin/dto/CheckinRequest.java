package com.mio.checkin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record CheckinRequest(
        @NotBlank @JsonProperty("time_of_day") String timeOfDay,
        @NotBlank @JsonProperty("emotion_type") String emotionType,
        @NotNull @Min(1) @Max(5) @JsonProperty("condition_score") Integer conditionScore,
        @Size(max = 200) String memo
) {}
