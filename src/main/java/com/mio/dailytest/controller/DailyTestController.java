package com.mio.dailytest.controller;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
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

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/v1/daily-test")
@RequiredArgsConstructor
public class DailyTestController {

    private final DailyTestService dailyTestService;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<DailyTestTodayResponse>> getTodayTest(
            Principal principal) {
        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(dailyTestService.getTodayTest(userId)));
    }

    @PostMapping("/{testId}/answer")
    public ResponseEntity<ApiResponse<DailyTestResultResponse>> submitAnswer(
            Principal principal,
            @PathVariable UUID testId,
            @Valid @RequestBody AnswerSubmitRequest request) {
        UUID userId = resolveUserId(principal);
        DailyTestResultResponse result = dailyTestService.submitAnswer(userId, testId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }

    private UUID resolveUserId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
