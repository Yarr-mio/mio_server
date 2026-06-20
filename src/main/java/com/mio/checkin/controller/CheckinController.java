package com.mio.checkin.controller;

import com.mio.checkin.dto.*;
import com.mio.checkin.service.CheckinService;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/checkins")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;

    @PostMapping
    public ResponseEntity<ApiResponse<CheckinCreateResponse>> submit(
            Principal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CheckinRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(checkinService.submit(resolveUserId(principal), request, idempotencyKey)));
    }

    @PutMapping("/{checkinId}")
    public ResponseEntity<ApiResponse<CheckinUpdateResponse>> update(
            Principal principal,
            @PathVariable UUID checkinId,
            @Valid @RequestBody CheckinUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                checkinService.update(resolveUserId(principal), checkinId, request)));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<CheckinTodayResponse>> getToday(Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(checkinService.getToday(resolveUserId(principal))));
    }

    @GetMapping("/{checkinId}")
    public ResponseEntity<ApiResponse<CheckinResponse>> getById(
            Principal principal,
            @PathVariable UUID checkinId) {
        return ResponseEntity.ok(ApiResponse.ok(checkinService.getById(resolveUserId(principal), checkinId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CheckinResponse>>> getHistory(
            Principal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(ApiResponse.ok(checkinService.getHistory(resolveUserId(principal), cursor, month)));
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
