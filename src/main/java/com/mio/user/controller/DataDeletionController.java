package com.mio.user.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.response.ApiResponse;
import com.mio.user.dto.DeletionStatusResponse;
import com.mio.user.service.DataDeletionService;
import com.mio.user.service.DataDeletionStatusRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/**
 * 데이터 삭제 상태 조회 (이슈 #373, 로드맵 §12 P0-6).
 *
 * <p>지금까지 사용자는 탈퇴 응답에 적힌 {@code hard_delete_scheduled_at} 하나만 볼 수
 * 있었다. 그 값은 접수 시점에 계산한 <b>약속</b>이지 상태가 아니라서, 실제로 지워졌는지
 * 물어볼 방법이 없었다.
 */
@RestController
@RequiredArgsConstructor
public class DataDeletionController {

    private final DataDeletionService dataDeletionService;
    private final DataDeletionStatusRateLimiter dataDeletionStatusRateLimiter;

    @GetMapping("/v1/users/me/deletion-status")
    public ResponseEntity<ApiResponse<DeletionStatusResponse>> getDeletionStatus(Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                dataDeletionService.findLatest(PrincipalUtils.resolveUserId(principal))
                        .map(DeletionStatusResponse::from)
                        .orElseGet(DeletionStatusResponse::none)
        ));
    }

    /**
     * 탈퇴 후 인증 토큰이 만료되거나 계정이 하드 삭제된 뒤에도 operation id로 조회한다.
     * 응답은 사용자 ID나 내부 오류를 노출하지 않는다.
     */
    @GetMapping("/v1/data-deletions/{operationId}")
    public ResponseEntity<ApiResponse<DeletionStatusResponse>> getDeletionStatusByOperationId(
            @PathVariable UUID operationId,
            HttpServletRequest request) {
        dataDeletionStatusRateLimiter.check(request.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(
                dataDeletionService.findByOperationId(operationId)
                        .map(DeletionStatusResponse::from)
                        .orElseThrow(() -> new BusinessException(
                        ErrorCode.DATA_DELETION_REQUEST_NOT_FOUND))
        ));
    }
}
