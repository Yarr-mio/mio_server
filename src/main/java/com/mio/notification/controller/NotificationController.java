package com.mio.notification.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.NotificationHistoryItemResponse;
import com.mio.notification.dto.NotificationHistoryResponse;
import com.mio.notification.dto.NotificationReadResponse;
import com.mio.notification.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
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
    public ResponseEntity<ApiResponse<java.util.List<NotificationHistoryItemResponse>>> getNotifications(
            Principal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        NotificationHistoryResponse response =
                notificationService.getNotificationHistory(PrincipalUtils.resolveUserId(principal), cursor, limit);
        String traceId = (String) request.getAttribute("traceId");
        return ResponseEntity.ok(ApiResponse.ok(
                response.items(),
                ApiResponse.Meta.page(traceId, response.nextCursor(), response.hasMore())
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
