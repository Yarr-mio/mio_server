package com.mio.user.controller;

import com.mio.common.PrincipalUtils;
import com.mio.common.response.ApiResponse;
import com.mio.user.dto.DeletionStatusResponse;
import com.mio.user.service.DataDeletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 데이터 삭제 상태 조회 (이슈 #373, 로드맵 §12 P0-6).
 *
 * <p>지금까지 사용자는 탈퇴 응답에 적힌 {@code hard_delete_scheduled_at} 하나만 볼 수
 * 있었다. 그 값은 접수 시점에 계산한 <b>약속</b>이지 상태가 아니라서, 실제로 지워졌는지
 * 물어볼 방법이 없었다.
 */
@RestController
@RequestMapping("/v1/users/me")
@RequiredArgsConstructor
public class DataDeletionController {

    private final DataDeletionService dataDeletionService;

    @GetMapping("/deletion-status")
    public ResponseEntity<ApiResponse<DeletionStatusResponse>> getDeletionStatus(Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                dataDeletionService.findLatest(PrincipalUtils.resolveUserId(principal))
                        .map(DeletionStatusResponse::from)
                        .orElseGet(DeletionStatusResponse::none)
        ));
    }
}
