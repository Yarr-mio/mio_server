package com.mio.report.dto;

import java.util.Map;

public class ReportCommonDto {

    public record DistortionDto(String type, String label, long count) {}

    public record TodoSummaryDto(
            int total,
            int completed,
            int skipped,
            int expired,
            double completionRate,
            Map<String, Integer> categoryDistribution
    ) {}

    public record SessionSummaryDto(
            int total,
            long totalMinutes
    ) {}

    private ReportCommonDto() {}
}
