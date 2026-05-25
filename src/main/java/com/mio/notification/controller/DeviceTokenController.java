package com.mio.notification.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.DeviceTokenRegisterRequest;
import com.mio.notification.dto.DeviceTokenResponse;
import com.mio.notification.service.DeviceTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping({"/v1/notifications/devices", "/v1/user/device-token"})
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping
    public ResponseEntity<ApiResponse<DeviceTokenResponse>> register(
            Principal principal,
            @Valid @RequestBody DeviceTokenRegisterRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(deviceTokenService.register(PrincipalUtils.resolveUserId(principal), request)));
    }

    @DeleteMapping("/{token}")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> delete(
            Principal principal,
            @PathVariable String token) {
        deviceTokenService.deleteByToken(PrincipalUtils.resolveUserId(principal), token);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("success", true)));
    }
}
