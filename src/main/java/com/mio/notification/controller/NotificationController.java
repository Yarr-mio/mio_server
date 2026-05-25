package com.mio.notification.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.NotificationHistoryResponse;
import com.mio.notification.dto.NotificationReadResponse;
import com.mio.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationHistoryResponse>> getNotifications(
            Principal principal,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                notificationService.getNotificationHistory(PrincipalUtils.resolveUserId(principal), cursor, limit)
        ));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationReadResponse>> markAsRead(
            Principal principal,
            @PathVariable UUID notificationId) {
        return ResponseEntity.ok(ApiResponse.ok(
                notificationService.markNotificationAsRead(PrincipalUtils.resolveUserId(principal), notificationId)
        ));
    }
}
