package com.mio.admin.controller;

import com.mio.common.response.ApiResponse;
import com.mio.notification.dto.StaleDeviceTokenUserResponse;
import com.mio.notification.service.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 운영자용 디바이스 토큰 상태 조회 (이슈 #392).
 *
 * <p>{@code ROLE_ADMIN} 필요 ({@code /v1/admin/**}).
 *
 * <p>APNs 400/410 응답에 따른 토큰 무효화는 정상 동작이지만, 앱이 재등록을 호출하지 않으면 그 유저는
 * 영구히 알림을 받지 못한 채 발송만 유령 SENT 로 기록된다. 끊긴 유저를 집계해 재등록 유도가 가능하도록 한다.
 */
@RestController
@RequestMapping("/v1/admin/notifications/device-tokens")
@RequiredArgsConstructor
public class AdminDeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    /** 토큰을 등록한 적은 있으나 현재 유효 토큰이 0개인 유저 목록. */
    @GetMapping("/stale-users")
    public ResponseEntity<ApiResponse<List<StaleDeviceTokenUserResponse>>> staleUsers() {
        return ResponseEntity.ok(ApiResponse.ok(deviceTokenService.findUsersWithoutValidToken()));
    }
}
