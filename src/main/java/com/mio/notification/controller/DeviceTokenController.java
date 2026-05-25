package com.mio.notification.controller;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.DeviceTokenRegisterRequest;
import com.mio.notification.dto.DeviceTokenResponse;
import com.mio.notification.service.DeviceTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications/device-token")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping
    public ResponseEntity<ApiResponse<DeviceTokenResponse>> register(
            Principal principal,
            @Valid @RequestBody DeviceTokenRegisterRequest request) {
        UUID userId = resolveUserId(principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(deviceTokenService.register(userId, request)));
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            Principal principal,
            @PathVariable UUID tokenId) {
        UUID userId = resolveUserId(principal);
        deviceTokenService.delete(userId, tokenId);
        return ResponseEntity.ok(ApiResponse.ok(null));
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
