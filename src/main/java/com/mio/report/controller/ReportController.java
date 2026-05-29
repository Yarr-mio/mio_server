package com.mio.report.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.report.dto.EmotionTrendResponse;
import com.mio.report.dto.WeeklyReportResponse;
import com.mio.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/weekly")
    public ResponseEntity<ApiResponse<WeeklyReportResponse>> getWeekly(
            Principal principal,
            @RequestParam(value = "week_start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) String weekStartStr) {

        LocalDate weekStart = parseDate(weekStartStr);
        WeeklyReportResponse response = reportService.getWeeklyReport(
                PrincipalUtils.resolveUserId(principal), weekStart);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/monthly")
    public ResponseEntity<ApiResponse<Void>> getMonthly(Principal principal) {
        // post-MVP
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @GetMapping("/emotion-trend")
    public ResponseEntity<ApiResponse<EmotionTrendResponse>> getEmotionTrend(
            Principal principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Integer days) {

        EmotionTrendResponse response = reportService.getEmotionTrend(
                PrincipalUtils.resolveUserId(principal), period, days);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
