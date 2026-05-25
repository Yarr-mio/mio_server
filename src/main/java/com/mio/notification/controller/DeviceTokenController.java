package com.mio.notification.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.DeviceTokenRegisterRequest;
import com.mio.notification.dto.DeviceTokenResponse;
import com.mio.notification.service.DeviceTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping({"/v1/user/device-token", "/v1/notifications/device-token"})
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping
    public ResponseEntity<ApiResponse<DeviceTokenResponse>> register(
            Principal principal,
            @Valid @RequestBody DeviceTokenRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(deviceTokenService.register(PrincipalUtils.resolveUserId(principal), request)));
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            Principal principal,
            @PathVariable UUID tokenId) {
        deviceTokenService.delete(PrincipalUtils.resolveUserId(principal), tokenId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> deleteCurrentDevice(Authentication authentication) {
        UUID userId = UUID.fromString((String) authentication.getPrincipal());
        String deviceId = (String) authentication.getCredentials();
        deviceTokenService.deleteCurrentDevice(userId, deviceId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
