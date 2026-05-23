package com.mio.onboarding.controller;

import com.mio.common.response.ApiResponse;
import com.mio.onboarding.dto.*;
import com.mio.onboarding.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @PostMapping("/step/1")
    public ResponseEntity<ApiResponse<OnboardingStepResponse>> submitStep1(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody OnboardingStep1Request request) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.submitStep1(userId, request)));
    }

    @PostMapping("/step/2")
    public ResponseEntity<ApiResponse<OnboardingStepResponse>> submitStep2(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody OnboardingStep2Request request) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.submitStep2(userId, request)));
    }

    @PostMapping("/step/3")
    public ResponseEntity<ApiResponse<OnboardingStep3Response>> submitStep3(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody OnboardingStep3Request request) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.submitStep3(userId, request)));
    }

    @PostMapping("/character")
    public ResponseEntity<ApiResponse<CharacterSelectResponse>> selectCharacter(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody CharacterSelectRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.selectCharacter(userId, request)));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<OnboardingStatusResponse>> getStatus(
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.getStatus(userId)));
    }
}
