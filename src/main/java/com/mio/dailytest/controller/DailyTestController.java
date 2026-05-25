package com.mio.dailytest.controller;

import com.mio.common.response.ApiResponse;
import com.mio.dailytest.dto.AnswerSubmitRequest;
import com.mio.dailytest.dto.DailyTestResultResponse;
import com.mio.dailytest.dto.DailyTestTodayResponse;
import com.mio.dailytest.service.DailyTestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/daily-test")
@RequiredArgsConstructor
public class DailyTestController {

    private final DailyTestService dailyTestService;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<DailyTestTodayResponse>> getTodayTest(
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(dailyTestService.getTodayTest(userId)));
    }

    @PostMapping("/{testId}/answer")
    public ResponseEntity<ApiResponse<DailyTestResultResponse>> submitAnswer(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID testId,
            @Valid @RequestBody AnswerSubmitRequest request) {
        DailyTestResultResponse result = dailyTestService.submitAnswer(userId, testId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }
}
