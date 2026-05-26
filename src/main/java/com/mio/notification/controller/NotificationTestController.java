package com.mio.notification.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.NotificationTestRequest;
import com.mio.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "notification.test-endpoint-enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class NotificationTestController {

    private final NotificationService notificationService;

    @PostMapping("/test")
    public ResponseEntity<ApiResponse<Void>> sendTest(
            Principal principal,
            @Valid @RequestBody NotificationTestRequest request) {
        notificationService.sendTestNotification(PrincipalUtils.resolveUserId(principal), request.title(), request.body());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
