package com.mio.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

public record EmotionTrendResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        List<TrendPointDto> points
) {
    public record TrendPointDto(
            LocalDate date,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            Double avgConditionScore,
            int checkinCount
    ) {}
}
