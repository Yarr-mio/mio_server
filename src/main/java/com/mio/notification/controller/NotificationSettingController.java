package com.mio.notification.controller;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.NotificationSettingResponse;
import com.mio.notification.dto.NotificationSettingUpdateRequest;
import com.mio.notification.service.NotificationSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications/settings")
@RequiredArgsConstructor
public class NotificationSettingController {

    private final NotificationSettingService notificationSettingService;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationSettingResponse>> getSettings(Principal principal) {
        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(notificationSettingService.getOrCreate(userId)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<NotificationSettingResponse>> updateSettings(
            Principal principal,
            @Valid @RequestBody NotificationSettingUpdateRequest request) {
        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(notificationSettingService.update(userId, request)));
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
