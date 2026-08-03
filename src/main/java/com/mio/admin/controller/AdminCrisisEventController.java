package com.mio.admin.controller;

import com.mio.admin.dto.CrisisEventReviewRequest;
import com.mio.admin.service.AdminCrisisEventService;
import com.mio.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 운영자용 위기 이벤트 검토 처리.
 *
 * <p>{@code ROLE_ADMIN} 필요 (이슈 #279).
 */
@RestController
@RequestMapping("/v1/admin/crisis-events")
@RequiredArgsConstructor
public class AdminCrisisEventController {

    private final AdminCrisisEventService adminCrisisEventService;

    @PostMapping("/{eventId}/review")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> review(
            @PathVariable UUID eventId,
            @Valid @RequestBody CrisisEventReviewRequest request) {
        adminCrisisEventService.review(eventId, request);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("reviewed", true)));
    }
}
