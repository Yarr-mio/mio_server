package com.mio.notification.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.NotificationSettingResponse;
import com.mio.notification.dto.NotificationSettingUpdateRequest;
import com.mio.notification.service.NotificationSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping({"/v1/user/notification-settings", "/v1/notifications/settings"})
@RequiredArgsConstructor
public class NotificationSettingController {

    private final NotificationSettingService notificationSettingService;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationSettingResponse>> getSettings(Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationSettingService.getOrCreate(PrincipalUtils.resolveUserId(principal))));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<NotificationSettingResponse>> updateSettings(
            Principal principal,
            @Valid @RequestBody NotificationSettingUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(notificationSettingService.update(PrincipalUtils.resolveUserId(principal), request)));
    }
}
