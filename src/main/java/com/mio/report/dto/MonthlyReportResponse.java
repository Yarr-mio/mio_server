package com.mio.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mio.report.dto.ReportCommonDto.DistortionDto;
import com.mio.report.dto.ReportCommonDto.SessionSummaryDto;
import com.mio.report.dto.ReportCommonDto.TodoSummaryDto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonthlyReportResponse(
        UUID reportId,
        LocalDate monthStart,
        LocalDate monthEnd,
        String status,
        Boolean isPartial,
        Integer checkinCount,
        Integer requiredCount,
        Double avgEmotionScore,
        List<DistortionDto> distortionTop3,
        String narrative,
        String coachingDirection,
        TodoSummaryDto todoSummary,
        SessionSummaryDto sessionSummary,
        OffsetDateTime generatedAt,
        String message
) {
    public static MonthlyReportResponse insufficientData(LocalDate monthStart, LocalDate monthEnd, int checkinCount) {
        return new MonthlyReportResponse(null, monthStart, monthEnd, "INSUFFICIENT_DATA",
                null, checkinCount, 7, null, null, null, null, null, null, null,
                "아직 기록이 부족해요. 체크인을 7회 이상 완료하면 월간 리포트를 볼 수 있어요.");
    }
}
