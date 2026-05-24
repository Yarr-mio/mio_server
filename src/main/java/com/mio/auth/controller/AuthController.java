package com.mio.auth.controller;

import com.mio.auth.dto.*;
import com.mio.auth.service.AuthService;
import com.mio.auth.service.RefreshTokenService;
import com.mio.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(request)));
    }

    @GetMapping("/signup/status")
    public ResponseEntity<ApiResponse<SignupStatusResponse>> getSignupStatus(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.ok(authService.getSignupStatus(UUID.fromString(userId))));
    }

    @PostMapping("/signup/complete")
    public ResponseEntity<ApiResponse<SignupCompleteResponse>> completeSignup(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SignupCompleteRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.completeSignup(UUID.fromString(userId), request)));
    }

    @GetMapping("/nickname/duplicate-check")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> checkNickname(
            @RequestParam @NotBlank String nickname) {
        boolean duplicate = authService.checkNicknameDuplicate(nickname);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("duplicate", duplicate)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(
            @Valid @RequestBody TokenRefreshRequest request) {
        String newAccessToken = refreshTokenService.refresh(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok(new TokenRefreshResponse(newAccessToken, 900)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> logout(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody LogoutRequest request) {
        authService.logout(UUID.fromString(userId), request.deviceId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("success", true)));
    }

    @DeleteMapping("/withdraw")
    public ResponseEntity<ApiResponse<WithdrawResponse>> withdraw(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.ok(authService.withdraw(UUID.fromString(userId))));
    }
}