package com.mio.notification.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.dto.DeviceTokenRegisterRequest;
import com.mio.notification.dto.DeviceTokenResponse;
import com.mio.notification.dto.StaleDeviceTokenUserResponse;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    /**
     * 디바이스 토큰을 등록하거나 갱신한다.
     *
     * <p>한 물리 기기에는 유효한 토큰 행이 하나만 존재해야 한다 (이슈 #391). 같은 기기를 여러 계정이
     * 돌려 쓰면 이전 계정 행이 유효한 채로 남아 그 계정의 알림이 지금 로그인한 사람 기기로 배달된다.
     * 그래서 본인 행을 갱신하기 전에 device_id 나 token 이 겹치는 타 행을 먼저 무효화한다.
     *
     * <p>기존 행이 무효화된 상태였다면 {@link DeviceToken#refreshToken} 이 다시 유효로 되돌린다.
     * APNs 거절로 끊긴 유저의 유일한 복구 경로이므로 (이슈 #392) 이 동작을 유지한다.
     */
    @Transactional
    public DeviceTokenResponse register(UUID userId, DeviceTokenRegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        invalidateConflicts(userId, request);

        DeviceToken token = deviceTokenRepository.findByUser_IdAndDeviceId(userId, request.deviceId())
                .map(existing -> {
                    existing.refreshToken(request.pushToken(), request.appVersion());
                    return existing;
                })
                .orElseGet(() -> deviceTokenRepository.save(
                        DeviceToken.builder()
                                .user(user)
                                .deviceId(request.deviceId())
                                .platform(request.platform())
                                .token(request.pushToken())
                                .appVersion(request.appVersion())
                                .build()
                ));

        return DeviceTokenResponse.from(token);
    }

    /**
     * 로그아웃 등으로 본인 토큰을 해제한다.
     *
     * <p>조회를 {@code userId} 로 한정하므로 같은 기기에 남아 있는 다른 계정 행에는 영향을 주지 않는다.
     * 행을 지우지 않고 무효화만 하는 이유는 재로그인 시 같은 행을 다시 유효화해 이력을 유지하기 위해서다.
     */
    @Transactional
    public void deleteByToken(UUID userId, String rawToken) {
        DeviceToken token = deviceTokenRepository.findByUser_IdAndToken(userId, rawToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_TOKEN_NOT_FOUND));
        token.invalidate();
    }

    /**
     * 토큰을 등록한 적은 있으나 현재 유효 토큰이 0개인 유저를 조회한다 (이슈 #392).
     *
     * <p>APNs 거절로 무효화된 뒤 재등록이 없어 알림이 끊긴 유저를 식별하는 용도다.
     */
    @Transactional(readOnly = true)
    public List<StaleDeviceTokenUserResponse> findUsersWithoutValidToken() {
        return deviceTokenRepository.findUsersWithoutValidToken().stream()
                .map(StaleDeviceTokenUserResponse::from)
                .toList();
    }

    private void invalidateConflicts(UUID userId, DeviceTokenRegisterRequest request) {
        List<DeviceToken> conflicts =
                deviceTokenRepository.findValidConflicts(userId, request.deviceId(), request.pushToken());
        if (conflicts.isEmpty()) {
            return;
        }

        conflicts.forEach(DeviceToken::invalidate);
        // 부분 유니크 인덱스(ux_device_tokens_token_valid / _device_id_valid) 위반을 피하려면
        // 본인 행을 유효화하기 전에 회수 UPDATE 가 먼저 DB 에 반영돼야 한다.
        deviceTokenRepository.flush();

        log.info("Reclaimed {} conflicting device token(s) for deviceId={} token={}",
                conflicts.size(), request.deviceId(), maskToken(request.pushToken()));
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 8) + "...";
    }
}
