package com.mio.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

public record EmotionTrendResponse(
        @JsonProperty("period_start") LocalDate periodStart,
        @JsonProperty("period_end") LocalDate periodEnd,
        List<TrendPointDto> points
) {
    public record TrendPointDto(
            LocalDate date,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @JsonProperty("avg_condition_score") Double avgConditionScore,
            @JsonProperty("checkin_count") int checkinCount
    ) {}
}
