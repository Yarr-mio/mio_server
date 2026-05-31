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
public record WeeklyReportResponse(
        @JsonProperty("report_id") UUID reportId,
        @JsonProperty("week_start") LocalDate weekStart,
        @JsonProperty("week_end") LocalDate weekEnd,
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
    public static WeeklyReportResponse insufficientData(LocalDate weekStart, LocalDate weekEnd, int checkinCount) {
        return new WeeklyReportResponse(null, weekStart, weekEnd, "INSUFFICIENT_DATA",
                null, checkinCount, 3, null, null, null, null, null, null, null,
                "아직 기록이 부족해요. 체크인을 3회 이상 완료하면 리포트를 볼 수 있어요.");
    }
}
