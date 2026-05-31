package com.mio.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.report.dto.ReportCommonDto.DistortionDto;
import com.mio.report.dto.ReportCommonDto.SessionSummaryDto;
import com.mio.report.dto.ReportCommonDto.TodoSummaryDto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonthlyReportResponse(
        @JsonProperty("report_id") UUID reportId,
        @JsonProperty("month_start") LocalDate monthStart,
        @JsonProperty("month_end") LocalDate monthEnd,
        String status,
        @JsonProperty("is_partial") Boolean isPartial,
        @JsonProperty("checkin_count") Integer checkinCount,
        @JsonProperty("required_count") Integer requiredCount,
        @JsonProperty("avg_emotion_score") Double avgEmotionScore,
        @JsonProperty("distortion_top3") List<DistortionDto> distortionTop3,
        String narrative,
        @JsonProperty("coaching_direction") String coachingDirection,
        @JsonProperty("todo_summary") TodoSummaryDto todoSummary,
        @JsonProperty("session_summary") SessionSummaryDto sessionSummary,
        @JsonProperty("generated_at") OffsetDateTime generatedAt,
        String message
) {
    public static MonthlyReportResponse insufficientData(LocalDate monthStart, LocalDate monthEnd, int checkinCount) {
        return new MonthlyReportResponse(null, monthStart, monthEnd, "INSUFFICIENT_DATA",
                null, checkinCount, 7, null, null, null, null, null, null, null,
                "아직 기록이 부족해요. 체크인을 7회 이상 완료하면 월간 리포트를 볼 수 있어요.");
    }
}
