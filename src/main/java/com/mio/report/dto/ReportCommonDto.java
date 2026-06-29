package com.mio.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class ReportCommonDto {

    public record DistortionDto(String type, String label, long count) {}

    public record TodoSummaryDto(
            int total,
            int completed,
            @JsonProperty("partial_completed") int partialCompleted,
            int skipped,
            int expired,
            @JsonProperty("completion_rate") double completionRate,
            @JsonProperty("category_distribution") Map<String, Integer> categoryDistribution
    ) {}

    public record SessionSummaryDto(
            int total,
            @JsonProperty("total_minutes") long totalMinutes
    ) {}

    private ReportCommonDto() {}
}
