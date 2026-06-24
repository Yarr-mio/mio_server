package com.mio.session.controller;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.session.dto.CbtEmotionScoreResponse;
import com.mio.session.dto.EmotionScoreRequest;
import com.mio.session.service.CbtReconstructionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/v1/cbt/reconstructions")
@RequiredArgsConstructor
public class CbtReconstructionController {

    private final CbtReconstructionService cbtReconstructionService;

    @PostMapping("/{reconstructionId}/emotion-score")
    public ResponseEntity<ApiResponse<CbtEmotionScoreResponse>> submitEmotionScore(
            Principal principal,
            @PathVariable UUID reconstructionId,
            @Valid @RequestBody EmotionScoreRequest request) {
        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(
                cbtReconstructionService.submitEmotionScore(userId, reconstructionId, request)));
    }

    private UUID resolveUserId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효한 사용자 식별자가 필요합니다.");
        }
    }
}
