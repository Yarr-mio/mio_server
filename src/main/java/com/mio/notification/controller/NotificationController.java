package com.mio.notification.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.NotificationHistoryResponse;
import com.mio.notification.dto.NotificationReadResponse;
import com.mio.notification.dto.NotificationTestRequest;
import com.mio.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "notification.test-endpoint-enabled",
        havingValue = "true",
        matchIfMissing = true
)
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

    @PostMapping("/test")
    public ResponseEntity<ApiResponse<Void>> sendTest(
            Principal principal,
            @Valid @RequestBody NotificationTestRequest request) {
        notificationService.sendTestNotification(PrincipalUtils.resolveUserId(principal), request.title(), request.body());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
