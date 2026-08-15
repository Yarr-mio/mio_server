package com.mio.memorycontrol.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.memorycontrol.dto.MemoryConsentWithdrawResponse;
import com.mio.memorycontrol.dto.MemoryListResponse;
import com.mio.memorycontrol.dto.MemoryUpdateRequest;
import com.mio.memorycontrol.dto.MemoryUpdateResponse;
import com.mio.memorycontrol.service.MemoryControlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/**
 * 사용자 장기 기억 통제 API (이슈 #453, 로드맵 §12 P0-6).
 *
 * <p>사용자는 서버가 자신에 대해 기억하는 것(세션 요약·추출된 사고·신념)을 조회하고,
 * 잘못된 기억을 정정하거나 비활성화하고, 메모리 수집·활용 동의를 철회할 수 있다.
 */
@RestController
@RequiredArgsConstructor
public class MemoryControlController {

    private final MemoryControlService memoryControlService;

    @GetMapping("/v1/users/me/memories")
    public ResponseEntity<ApiResponse<MemoryListResponse>> listMemories(
            Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                memoryControlService.listMemories(PrincipalUtils.resolveUserId(principal), page, size)));
    }

    @PatchMapping("/v1/users/me/memories/{memoryId}")
    public ResponseEntity<ApiResponse<MemoryUpdateResponse>> updateMemory(
            @PathVariable UUID memoryId,
            @Valid @RequestBody MemoryUpdateRequest request,
            Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                memoryControlService.updateMemory(PrincipalUtils.resolveUserId(principal), memoryId, request)));
    }

    @PostMapping("/v1/users/me/memory-consent/withdraw")
    public ResponseEntity<ApiResponse<MemoryConsentWithdrawResponse>> withdrawConsent(Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                memoryControlService.withdrawConsent(PrincipalUtils.resolveUserId(principal))));
    }
}
