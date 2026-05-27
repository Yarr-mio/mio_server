package com.mio.onboarding.controller;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.onboarding.dto.*;
import com.mio.onboarding.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @PostMapping("/step/1")
    public ResponseEntity<ApiResponse<OnboardingStepResponse>> submitStep1(
            Principal principal,
            @Valid @RequestBody OnboardingStep1Request request) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.submitStep1(resolveUserId(principal), request)));
    }

    @PostMapping("/step/2")
    public ResponseEntity<ApiResponse<OnboardingStepResponse>> submitStep2(
            Principal principal,
            @Valid @RequestBody OnboardingStep2Request request) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.submitStep2(resolveUserId(principal), request)));
    }

    @PostMapping("/step/3")
    public ResponseEntity<ApiResponse<OnboardingStep3Response>> submitStep3(
            Principal principal,
            @Valid @RequestBody OnboardingStep3Request request) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.submitStep3(resolveUserId(principal), request)));
    }

    @PostMapping("/character")
    public ResponseEntity<ApiResponse<CharacterSelectResponse>> selectCharacter(
            Principal principal,
            @Valid @RequestBody CharacterSelectRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.selectCharacter(resolveUserId(principal), request)));
    }

    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<SignupCompleteResponse>> completeSignup(
            Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.completeSignup(resolveUserId(principal))));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<OnboardingStatusResponse>> getStatus(
            Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.getStatus(resolveUserId(principal))));
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
